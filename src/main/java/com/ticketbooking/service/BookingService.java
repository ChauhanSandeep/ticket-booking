package com.ticketbooking.service;

import com.ticketbooking.dto.request.ConfirmBookingRequest;
import com.ticketbooking.dto.response.BookingResponse;
import com.ticketbooking.entity.Booking;
import com.ticketbooking.entity.Seat;
import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.BookingStatus;
import com.ticketbooking.entity.enums.HoldStatus;
import com.ticketbooking.entity.enums.SeatStatus;
import com.ticketbooking.exception.*;
import com.ticketbooking.repository.BookingRepository;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatHoldRepository;
import com.ticketbooking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final BookingRepository bookingRepository;
    private final Clock clock;

    @Transactional
    public BookingResponse confirmBooking(ConfirmBookingRequest request) {
        // Look up the hold
        SeatHold initialHold = seatHoldRepository.findByHoldId(request.getHoldId())
                .orElseThrow(() -> new ResourceNotFoundException("Hold", request.getHoldId()));

        Long eventId = initialHold.getEvent().getId();
        Long holdInternalId = initialHold.getId();

        // Acquire pessimistic lock on event row - same lock as hold creation
        eventRepository.findByIdWithLock(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));

        // Re-fetch hold after lock acquisition to protect against cleanup race condition
        SeatHold hold = seatHoldRepository.findById(holdInternalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hold", request.getHoldId()));

        // Validate hold status
        if (hold.getStatus() == HoldStatus.CONFIRMED) {
            throw new InvalidHoldStateException(hold.getHoldId(), hold.getStatus());
        }
        if (hold.getStatus() == HoldStatus.EXPIRED) {
            throw new HoldExpiredException(hold.getHoldId());
        }

        // Check if hold has expired by time (cleanup may not have run yet)
        if (hold.getExpiresAt().isBefore(LocalDateTime.now(clock))) {
            hold.setStatus(HoldStatus.EXPIRED);
            seatHoldRepository.save(hold);
            releaseSeatsForHold(hold);
            throw new HoldExpiredException(hold.getHoldId());
        }

        // Prevent double booking for same user and event
        if (bookingRepository.existsByEventIdAndUserIdAndStatus(
                hold.getEvent().getId(), hold.getUserId(), BookingStatus.CONFIRMED)) {
            throw new DuplicateBookingException(hold.getEvent().getId(), hold.getUserId());
        }

        // Transition hold to CONFIRMED
        hold.setStatus(HoldStatus.CONFIRMED);
        seatHoldRepository.save(hold);

        // Create booking record
        Booking booking = Booking.builder()
                .bookingReference(UUID.randomUUID())
                .event(hold.getEvent())
                .userId(hold.getUserId())
                .status(BookingStatus.CONFIRMED)
                .hold(hold)
                .build();
        booking = bookingRepository.save(booking);

        // Transition seats from HELD to BOOKED
        List<Seat> seats = seatRepository.findByHoldId(hold.getId());
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.BOOKED);
            seat.setBooking(booking);
        }
        seatRepository.saveAll(seats);

        List<String> seatNumbers = seats.stream()
                .map(Seat::getSeatNumber)
                .sorted(Comparator.comparingInt(Integer::parseInt))
                .toList();

        return toBookingResponse(booking, seatNumbers);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID bookingReference) {
        Booking booking = bookingRepository.findByBookingReference(bookingReference)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingReference));

        List<String> seatNumbers = seatRepository.findByBookingId(booking.getId()).stream()
                .map(Seat::getSeatNumber)
                .sorted(Comparator.comparingInt(Integer::parseInt))
                .toList();

        return toBookingResponse(booking, seatNumbers);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getUserBookings(String userId) {
        return bookingRepository.findByUserId(userId).stream()
                .map(booking -> {
                    List<String> seatNumbers = seatRepository.findByBookingId(booking.getId()).stream()
                            .map(Seat::getSeatNumber)
                            .sorted(Comparator.comparingInt(Integer::parseInt))
                            .toList();
                    return toBookingResponse(booking, seatNumbers);
                })
                .toList();
    }

    @Transactional
    public BookingResponse cancelBooking(UUID bookingReference) {
        Booking initialBooking = bookingRepository.findByBookingReference(bookingReference)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingReference));

        Long eventId = initialBooking.getEvent().getId();
        Long bookingId = initialBooking.getId();

        // Acquire pessimistic lock on event row before checking status
        eventRepository.findByIdWithLock(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));

        // Re-fetch after lock to protect against concurrent cancel requests
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingReference));

        if (booking.getStatus() == BookingStatus.CANCELED) {
            throw new BookingAlreadyCanceledException(bookingReference);
        }

        // Soft delete: mark as canceled
        booking.setStatus(BookingStatus.CANCELED);
        booking.setCanceledAt(LocalDateTime.now(clock));
        bookingRepository.save(booking);

        // Release seats back to AVAILABLE
        List<Seat> seats = seatRepository.findByBookingId(booking.getId());
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.AVAILABLE);
            seat.setHold(null);
            seat.setBooking(null);
        }
        seatRepository.saveAll(seats);

        List<String> seatNumbers = seats.stream()
                .map(Seat::getSeatNumber)
                .sorted(Comparator.comparingInt(Integer::parseInt))
                .toList();

        return toBookingResponse(booking, seatNumbers);
    }

    private void releaseSeatsForHold(SeatHold hold) {
        List<Seat> seats = seatRepository.findByHoldId(hold.getId());
        for (Seat seat : seats) {
            if (seat.getStatus() == SeatStatus.HELD) {
                seat.setStatus(SeatStatus.AVAILABLE);
                seat.setHold(null);
            }
        }
        seatRepository.saveAll(seats);
    }

    private BookingResponse toBookingResponse(Booking booking, List<String> seatNumbers) {
        return BookingResponse.builder()
                .bookingReference(booking.getBookingReference())
                .eventId(booking.getEvent().getId())
                .userId(booking.getUserId())
                .bookedSeats(seatNumbers)
                .status(booking.getStatus())
                .createdAt(booking.getCreatedAt())
                .canceledAt(booking.getCanceledAt())
                .build();
    }
}
