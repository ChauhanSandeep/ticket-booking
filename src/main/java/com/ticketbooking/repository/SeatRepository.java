package com.ticketbooking.repository;

import com.ticketbooking.entity.Seat;
import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventId(Long eventId);

    List<Seat> findByEventIdAndSeatNumberIn(Long eventId, List<String> seatNumbers);

    List<Seat> findByHoldId(Long holdId);

    List<Seat> findByBookingId(Long bookingId);

    List<Seat> findByBookingIdIn(List<Long> bookingIds);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    @Modifying
    @Query("DELETE FROM Seat s WHERE s.event.id = :eventId")
    void deleteByEventId(@Param("eventId") Long eventId);

    // Atomic conditional UPDATE: claims only currently-AVAILABLE seats in one
    // statement. The affected-row count tells the caller whether every
    // requested seat was actually claimed.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Seat s SET s.status = com.ticketbooking.entity.enums.SeatStatus.HELD, s.hold = :hold " +
           "WHERE s.event.id = :eventId AND s.seatNumber IN :seatNumbers " +
           "AND s.status = com.ticketbooking.entity.enums.SeatStatus.AVAILABLE")
    int claimSeats(@Param("eventId") Long eventId,
                   @Param("seatNumbers") List<String> seatNumbers,
                   @Param("hold") SeatHold hold);
}
