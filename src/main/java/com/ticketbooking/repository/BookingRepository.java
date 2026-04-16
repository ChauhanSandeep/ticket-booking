package com.ticketbooking.repository;

import com.ticketbooking.entity.Booking;
import com.ticketbooking.entity.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(UUID bookingReference);

    boolean existsByEventIdAndUserIdAndStatus(Long eventId, String userId, BookingStatus status);

    List<Booking> findByUserId(String userId);

    boolean existsByEventIdAndStatus(Long eventId, BookingStatus status);
}
