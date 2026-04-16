package com.ticketbooking.service;

import com.ticketbooking.entity.Seat;
import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.HoldStatus;
import com.ticketbooking.entity.enums.SeatStatus;
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

    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    /**
     * Runs every 60 seconds to clean up expired holds. Each hold is processed
     * in its own transaction with a row-level lock on that specific hold,
     * so cleanup of one hold never blocks confirmation of unrelated holds.
     */
    @Scheduled(fixedRate = 60000)
    public void releaseExpiredHolds() {
        log.info("Starting expired hold cleanup");

        List<Long> expiredHoldIds = seatHoldRepository.findExpiredActiveHoldIds(
                HoldStatus.ACTIVE, LocalDateTime.now(clock));

        int totalReleased = 0;
        for (Long holdId : expiredHoldIds) {
            totalReleased += releaseHoldIfExpired(holdId);
        }

        if (totalReleased > 0) {
            log.info("Expired hold cleanup completed: released {} holds", totalReleased);
        }
    }

    /**
     * Releases a single expired hold in its own transaction. The pessimistic
     * lock on the hold row serializes with confirmBooking; the status re-check
     * after the lock handles the case where confirm won the race.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseHoldIfExpired(Long holdId) {
        SeatHold hold = seatHoldRepository.findByIdWithLock(holdId).orElse(null);
        if (hold == null || hold.getStatus() != HoldStatus.ACTIVE) {
            return 0;
        }
        if (hold.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            return 0;
        }

        hold.setStatus(HoldStatus.EXPIRED);
        seatHoldRepository.save(hold);

        List<Seat> seats = seatRepository.findByHoldId(hold.getId());
        for (Seat seat : seats) {
            if (seat.getStatus() == SeatStatus.HELD) {
                seat.setStatus(SeatStatus.AVAILABLE);
                seat.setHold(null);
            }
        }
        seatRepository.saveAll(seats);
        return 1;
    }
}
