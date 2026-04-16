package com.ticketbooking.repository;

import com.ticketbooking.entity.Booking;
import com.ticketbooking.entity.enums.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(UUID bookingReference);

    List<Booking> findByUserId(String userId);

    boolean existsByEventIdAndStatus(Long eventId, BookingStatus status);

    /**
     * Acquires a pessimistic write lock on a single booking row. Used by
     * cancelBooking to serialize cancellation on the same booking without
     * affecting unrelated bookings.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdWithLock(@Param("id") Long id);
}
