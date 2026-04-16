package com.ticketbooking.repository;

import com.ticketbooking.entity.Seat;
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
}
