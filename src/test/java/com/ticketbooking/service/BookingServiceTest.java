package com.ticketbooking.service;

import com.ticketbooking.dto.request.ConfirmBookingRequest;
import com.ticketbooking.dto.response.BookingResponse;
import com.ticketbooking.entity.Booking;
import com.ticketbooking.entity.Event;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private Clock clock;

    @InjectMocks
    private BookingService bookingService;

    private void setupClock() {
        Instant fixedInstant = Instant.parse("2026-06-01T12:00:00Z");
        when(clock.instant()).thenReturn(fixedInstant);
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
    }

    private Event createEvent() {
        return Event.builder().id(1L).name("Concert").location("NYC")
                .eventDate(LocalDateTime.now().plusDays(30)).totalSeats(10)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private SeatHold createActiveHold(Event event) {
        return SeatHold.builder().id(1L).holdId(UUID.randomUUID()).event(event)
                .userId("user-1").status(HoldStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .createdAt(LocalDateTime.now()).build();
    }

    @Test
    void confirmBooking_shouldSucceed() {
        setupClock();
        Event event = createEvent();
        SeatHold hold = createActiveHold(event);
        UUID holdUuid = hold.getHoldId();

        Seat seat1 = Seat.builder().id(1L).event(event).seatNumber("1").status(SeatStatus.HELD).hold(hold).build();
        Seat seat2 = Seat.builder().id(2L).event(event).seatNumber("2").status(SeatStatus.HELD).hold(hold).build();

        when(seatHoldRepository.findByHoldId(holdUuid)).thenReturn(Optional.of(hold));
        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.findById(1L)).thenReturn(Optional.of(hold));
        when(bookingRepository.existsByEventIdAndUserIdAndStatus(1L, "user-1", BookingStatus.CONFIRMED)).thenReturn(false);
        when(seatHoldRepository.save(any(SeatHold.class))).thenReturn(hold);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(1L);
            b.setCreatedAt(LocalDateTime.now());
            return b;
        });
        when(seatRepository.findByHoldId(1L)).thenReturn(List.of(seat1, seat2));
        when(seatRepository.saveAll(anyList())).thenReturn(List.of(seat1, seat2));

        BookingResponse response = bookingService.confirmBooking(
                ConfirmBookingRequest.builder().holdId(holdUuid).build());

        assertThat(response.getBookingReference()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.getBookedSeats()).containsExactly("1", "2");
    }

    @Test
    void confirmBooking_shouldThrowWhenHoldExpiredByTime() {
        setupClock();
        Event event = createEvent();
        SeatHold hold = SeatHold.builder().id(1L).holdId(UUID.randomUUID()).event(event)
                .userId("user-1").status(HoldStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().minusMinutes(1)) // Already expired
                .createdAt(LocalDateTime.now().minusMinutes(6)).build();

        when(seatHoldRepository.findByHoldId(hold.getHoldId())).thenReturn(Optional.of(hold));
        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.findById(1L)).thenReturn(Optional.of(hold));
        when(seatHoldRepository.save(any(SeatHold.class))).thenReturn(hold);
        when(seatRepository.findByHoldId(1L)).thenReturn(List.of());
        when(seatRepository.saveAll(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> bookingService.confirmBooking(
                ConfirmBookingRequest.builder().holdId(hold.getHoldId()).build()))
                .isInstanceOf(HoldExpiredException.class);
    }

    @Test
    void confirmBooking_shouldThrowWhenHoldAlreadyConfirmed() {
        Event event = createEvent();
        SeatHold hold = SeatHold.builder().id(1L).holdId(UUID.randomUUID()).event(event)
                .userId("user-1").status(HoldStatus.CONFIRMED)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .createdAt(LocalDateTime.now()).build();

        when(seatHoldRepository.findByHoldId(hold.getHoldId())).thenReturn(Optional.of(hold));
        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.findById(1L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> bookingService.confirmBooking(
                ConfirmBookingRequest.builder().holdId(hold.getHoldId()).build()))
                .isInstanceOf(InvalidHoldStateException.class);
    }

    @Test
    void confirmBooking_shouldThrowOnDuplicateBooking() {
        Event event = createEvent();
        SeatHold hold = createActiveHold(event);

        when(seatHoldRepository.findByHoldId(hold.getHoldId())).thenReturn(Optional.of(hold));
        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.findById(1L)).thenReturn(Optional.of(hold));
        when(bookingRepository.existsByEventIdAndUserIdAndStatus(1L, "user-1", BookingStatus.CONFIRMED)).thenReturn(true);

        assertThatThrownBy(() -> bookingService.confirmBooking(
                ConfirmBookingRequest.builder().holdId(hold.getHoldId()).build()))
                .isInstanceOf(DuplicateBookingException.class);
    }

    @Test
    void cancelBooking_shouldSoftDelete() {
        setupClock();
        Event event = createEvent();
        UUID bookingRef = UUID.randomUUID();
        Booking booking = Booking.builder().id(1L).bookingReference(bookingRef)
                .event(event).userId("user-1").status(BookingStatus.CONFIRMED)
                .createdAt(LocalDateTime.now()).build();

        Seat seat = Seat.builder().id(1L).event(event).seatNumber("1").status(SeatStatus.BOOKED).booking(booking).build();

        when(bookingRepository.findByBookingReference(bookingRef)).thenReturn(Optional.of(booking));
        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(seatRepository.findByBookingId(1L)).thenReturn(List.of(seat));
        when(seatRepository.saveAll(anyList())).thenReturn(List.of(seat));

        BookingResponse response = bookingService.cancelBooking(bookingRef);

        assertThat(response.getStatus()).isEqualTo(BookingStatus.CANCELED);
        assertThat(booking.getCanceledAt()).isNotNull();
        verify(bookingRepository).save(booking);
    }

    @Test
    void cancelBooking_shouldThrowWhenAlreadyCanceled() {
        UUID bookingRef = UUID.randomUUID();
        Booking booking = Booking.builder().id(1L).bookingReference(bookingRef)
                .event(createEvent()).userId("user-1").status(BookingStatus.CANCELED)
                .createdAt(LocalDateTime.now()).canceledAt(LocalDateTime.now()).build();

        when(bookingRepository.findByBookingReference(bookingRef)).thenReturn(Optional.of(booking));
        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(createEvent()));
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBooking(bookingRef))
                .isInstanceOf(BookingAlreadyCanceledException.class);
    }
}
