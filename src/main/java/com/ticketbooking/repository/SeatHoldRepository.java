package com.ticketbooking.repository;

import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    Optional<SeatHold> findByHoldId(UUID holdId);

    boolean existsByEventIdAndUserIdAndStatus(Long eventId, String userId, HoldStatus status);

    List<SeatHold> findByStatusAndExpiresAtBefore(HoldStatus status, LocalDateTime cutoff);

    @Query("SELECT DISTINCT h.event.id FROM SeatHold h WHERE h.status = :status AND h.expiresAt < :cutoff")
    List<Long> findDistinctEventIdsWithExpiredHolds(@Param("status") HoldStatus status,
                                                    @Param("cutoff") LocalDateTime cutoff);

    List<SeatHold> findByEventIdAndStatusAndExpiresAtBefore(Long eventId, HoldStatus status, LocalDateTime cutoff);

    boolean existsByEventIdAndStatus(Long eventId, HoldStatus status);
}
