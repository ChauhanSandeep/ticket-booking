package com.ticketbooking.service;

import com.ticketbooking.dto.request.HoldSeatsRequest;
import com.ticketbooking.dto.response.HoldResponse;
import com.ticketbooking.entity.Event;
import com.ticketbooking.entity.Seat;
import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.HoldStatus;
import com.ticketbooking.entity.enums.SeatStatus;
import com.ticketbooking.exception.DuplicateHoldException;
import com.ticketbooking.exception.InvalidSeatException;
import com.ticketbooking.exception.ResourceNotFoundException;
import com.ticketbooking.exception.SeatsUnavailableException;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatHoldRepository;
import com.ticketbooking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldService {

    @Value("${booking.hold.duration-minutes:5}")
    private int holdDurationMinutes;

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final Clock clock;

    @Transactional
    public HoldResponse holdSeats(Long eventId, HoldSeatsRequest request) {
        // Validate no duplicate seat numbers in request
        List<String> seatNumbers = request.getSeatNumbers();
        if (seatNumbers.size() != new HashSet<>(seatNumbers).size()) {
            throw new IllegalArgumentException("Duplicate seat numbers in request");
        }

        // Acquire pessimistic write lock on event row - serializes all seat operations for this event
        Event event = eventRepository.findByIdWithLock(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));

        // Check for duplicate active hold for same user and event
        if (seatHoldRepository.existsByEventIdAndUserIdAndStatus(eventId, request.getUserId(), HoldStatus.ACTIVE)) {
            throw new DuplicateHoldException(eventId, request.getUserId());
        }

        // Fetch requested seats and validate they exist
        List<Seat> seats = seatRepository.findByEventIdAndSeatNumberIn(eventId, seatNumbers);
        Set<String> foundNumbers = seats.stream().map(Seat::getSeatNumber).collect(Collectors.toSet());
        List<String> missingSeats = seatNumbers.stream()
                .filter(num -> !foundNumbers.contains(num))
                .toList();
        if (!missingSeats.isEmpty()) {
            throw new InvalidSeatException(missingSeats);
        }

        // Check all requested seats are available
        List<String> unavailableSeats = seats.stream()
                .filter(s -> s.getStatus() != SeatStatus.AVAILABLE)
                .map(Seat::getSeatNumber)
                .toList();
        if (!unavailableSeats.isEmpty()) {
            throw new SeatsUnavailableException(unavailableSeats);
        }

        // Create the hold record
        SeatHold hold = SeatHold.builder()
                .holdId(UUID.randomUUID())
                .event(event)
                .userId(request.getUserId())
                .status(HoldStatus.ACTIVE)
                .expiresAt(LocalDateTime.now(clock).plusMinutes(holdDurationMinutes))
                .build();
        hold = seatHoldRepository.save(hold);
        log.info("Hold created: holdId={}, userId={}, eventId={}, seats={}, expiresAt={}",
                hold.getHoldId(), request.getUserId(), eventId, seatNumbers, hold.getExpiresAt());

        // Mark seats as HELD
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.HELD);
            seat.setHold(hold);
        }
        seatRepository.saveAll(seats);

        return toHoldResponse(hold, seatNumbers);
    }

    @Transactional(readOnly = true)
    public HoldResponse getHold(UUID holdId) {
        SeatHold hold = seatHoldRepository.findByHoldId(holdId)
                .orElseThrow(() -> new ResourceNotFoundException("Hold", holdId));

        List<String> seatNumbers = seatRepository.findByHoldId(hold.getId()).stream()
                .map(Seat::getSeatNumber)
                .sorted(Comparator.comparingInt(Integer::parseInt))
                .toList();

        return toHoldResponse(hold, seatNumbers);
    }

    private HoldResponse toHoldResponse(SeatHold hold, List<String> seatNumbers) {
        return HoldResponse.builder()
                .holdId(hold.getHoldId())
                .eventId(hold.getEvent().getId())
                .userId(hold.getUserId())
                .heldSeats(seatNumbers)
                .status(hold.getStatus())
                .expiresAt(hold.getExpiresAt())
                .createdAt(hold.getCreatedAt())
                .build();
    }
}
