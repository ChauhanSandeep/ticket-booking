package com.ticketbooking.service;

import com.ticketbooking.entity.Seat;
import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.HoldStatus;
import com.ticketbooking.entity.enums.SeatStatus;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatHoldRepository;
import com.ticketbooking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldCleanupService {

    private final EventRepository eventRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    /**
     * Runs every 60 seconds to clean up expired holds.
     * Groups expired holds by event and processes each event in its own
     * transaction with the same pessimistic lock used by hold/confirm operations,
     * ensuring no race conditions with concurrent seat modifications.
     */
    @Scheduled(fixedRate = 60000)
    public void releaseExpiredHolds() {
        log.info("Starting expired hold cleanup");

        List<Long> eventIds = seatHoldRepository.findDistinctEventIdsWithExpiredHolds(
                HoldStatus.ACTIVE, LocalDateTime.now(clock));

        int totalReleased = 0;
        for (Long eventId : eventIds) {
            totalReleased += releaseExpiredHoldsForEvent(eventId);
        }

        if (totalReleased > 0) {
            log.info("Expired hold cleanup completed: released {} holds", totalReleased);
        }
    }

    /**
     * Releases expired holds for a single event within its own transaction.
     * Acquires the same pessimistic lock on the event row that hold/confirm
     * operations use, ensuring mutual exclusion.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseExpiredHoldsForEvent(Long eventId) {
        // Acquire the same pessimistic lock as hold/confirm operations
        eventRepository.findByIdWithLock(eventId);

        List<SeatHold> expiredHolds = seatHoldRepository.findByEventIdAndStatusAndExpiresAtBefore(
                eventId, HoldStatus.ACTIVE, LocalDateTime.now(clock));

        int released = 0;
        for (SeatHold hold : expiredHolds) {
            // Re-check status after lock acquisition - may have been confirmed
            // between the initial scan and acquiring the lock
            if (hold.getStatus() != HoldStatus.ACTIVE) {
                continue;
            }

            hold.setStatus(HoldStatus.EXPIRED);
            seatHoldRepository.save(hold);

            // Release seats back to AVAILABLE
            List<Seat> seats = seatRepository.findByHoldId(hold.getId());
            for (Seat seat : seats) {
                if (seat.getStatus() == SeatStatus.HELD) {
                    seat.setStatus(SeatStatus.AVAILABLE);
                    seat.setHold(null);
                }
            }
            seatRepository.saveAll(seats);
            released++;
        }

        return released;
    }
}
