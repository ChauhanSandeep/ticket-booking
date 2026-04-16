package com.ticketbooking.service;

import com.ticketbooking.dto.request.HoldSeatsRequest;
import com.ticketbooking.dto.response.HoldResponse;
import com.ticketbooking.entity.Event;
import com.ticketbooking.entity.Seat;
import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.HoldStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
        List<String> seatNumbers = request.getSeatNumbers();
        if (seatNumbers.size() != new HashSet<>(seatNumbers).size()) {
            throw new IllegalArgumentException("Duplicate seat numbers in request");
        }

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));

        // Insert the hold up-front. The UNIQUE (event_id, active_hold_key)
        // constraint enforces "at most one ACTIVE hold per user per event"
        // atomically — no need to hold a pessimistic lock on the event row.
        SeatHold hold = SeatHold.builder()
                .holdId(UUID.randomUUID())
                .event(event)
                .userId(request.getUserId())
                .status(HoldStatus.ACTIVE)
                .expiresAt(LocalDateTime.now(clock).plusMinutes(holdDurationMinutes))
                .build();
        try {
            // Flush to DB immediately to ensure the hold is created before the seats are
            // claimed
            hold = seatHoldRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateHoldException(eventId, request.getUserId());
        }

        // Atomic compare-and-set: claim only seats still AVAILABLE. Concurrent
        // hold attempts on overlapping seats see the same row lock only for the
        // duration of this single UPDATE statement.
        int claimed = seatRepository.claimSeats(eventId, seatNumbers, hold);
        if (claimed != seatNumbers.size()) {
            // Rollback the hold and throw an exception
            throwClaimFailure(eventId, seatNumbers, hold);
        }

        log.info("Hold created: holdId={}, userId={}, eventId={}, seats={}, expiresAt={}",
                hold.getHoldId(), request.getUserId(), eventId, seatNumbers, hold.getExpiresAt());

        return toHoldResponse(hold, seatNumbers);
    }

    private void throwClaimFailure(Long eventId, List<String> seatNumbers, SeatHold hold) {
        List<Seat> existing = seatRepository.findByEventIdAndSeatNumberIn(eventId, seatNumbers);
        Set<String> foundNumbers = existing.stream().map(Seat::getSeatNumber).collect(Collectors.toSet());
        List<String> missing = seatNumbers.stream()
                .filter(num -> !foundNumbers.contains(num))
                .toList();
        if (!missing.isEmpty()) {
            throw new InvalidSeatException(missing);
        }
        // All seats exist — anything not tied to our hold was taken by someone else.
        List<String> unavailable = existing.stream()
                .filter(s -> s.getHold() == null || !hold.getId().equals(s.getHold().getId()))
                .map(Seat::getSeatNumber)
                .toList();
        throw new SeatsUnavailableException(unavailable);
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
