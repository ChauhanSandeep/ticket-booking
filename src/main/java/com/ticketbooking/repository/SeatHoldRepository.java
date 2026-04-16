package com.ticketbooking.repository;

import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.HoldStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    Optional<SeatHold> findByHoldId(UUID holdId);

    /**
     * Acquires a pessimistic write lock on a single hold row. Used by
     * the expiration cleanup (which already has the internal id).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM SeatHold h WHERE h.id = :id")
    Optional<SeatHold> findByIdWithLock(@Param("id") Long id);

    /**
     * Acquires a pessimistic write lock on a hold row addressed by its
     * public UUID. Used by confirmBooking, which receives the UUID from
     * the client — saves a round-trip vs. separate lookup + locked refetch.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM SeatHold h WHERE h.holdId = :holdId")
    Optional<SeatHold> findByHoldIdWithLock(@Param("holdId") UUID holdId);

    boolean existsByEventIdAndUserIdAndStatus(Long eventId, String userId, HoldStatus status);

    @Query("SELECT DISTINCT h.event.id FROM SeatHold h WHERE h.status = :status AND h.expiresAt < :cutoff")
    List<Long> findDistinctEventIdsWithExpiredHolds(@Param("status") HoldStatus status,
                                                    @Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT h.id FROM SeatHold h WHERE h.status = :status AND h.expiresAt < :cutoff")
    List<Long> findExpiredActiveHoldIds(@Param("status") HoldStatus status,
                                        @Param("cutoff") LocalDateTime cutoff);

    List<SeatHold> findByEventIdAndStatusAndExpiresAtBefore(Long eventId, HoldStatus status, LocalDateTime cutoff);

    boolean existsByEventIdAndStatus(Long eventId, HoldStatus status);
}
