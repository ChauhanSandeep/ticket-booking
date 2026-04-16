package com.ticketbooking.concurrency;

import com.ticketbooking.dto.request.ConfirmBookingRequest;
import com.ticketbooking.dto.request.CreateEventRequest;
import com.ticketbooking.dto.request.HoldSeatsRequest;
import com.ticketbooking.dto.response.EventResponse;
import com.ticketbooking.dto.response.HoldResponse;
import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.HoldStatus;
import com.ticketbooking.entity.enums.SeatStatus;
import com.ticketbooking.repository.BookingRepository;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatHoldRepository;
import com.ticketbooking.repository.SeatRepository;
import com.ticketbooking.service.BookingService;
import com.ticketbooking.service.EventService;
import com.ticketbooking.service.HoldCleanupService;
import com.ticketbooking.service.HoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the race condition between confirming a booking and the cleanup
 * service expiring the same hold. Regardless of which thread wins the
 * pessimistic lock, the system should remain consistent: either the
 * booking is confirmed and the hold is CONFIRMED, or the hold is
 * EXPIRED and the confirm fails.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfirmVsCleanupRaceTest {

    @Autowired
    private HoldService holdService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private EventService eventService;

    @Autowired
    private HoldCleanupService holdCleanupService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private SeatHoldRepository seatHoldRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EventRepository eventRepository;

    private Long eventId;

    @BeforeEach
    void setUp() {
        seatRepository.deleteAll();
        bookingRepository.deleteAll();
        seatHoldRepository.deleteAll();
        eventRepository.deleteAll();

        EventResponse event = eventService.createEvent(CreateEventRequest.builder()
                .name("Race Test Event")
                .location("Test")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(5)
                .build());
        eventId = event.getId();
    }

    @Test
    @Timeout(30)
    void confirmAndCleanupRace_noDataCorruption() throws InterruptedException {
        // Create a hold and immediately expire it
        HoldResponse holdResponse = holdService.holdSeats(eventId, HoldSeatsRequest.builder()
                .userId("user-1")
                .seatNumbers(List.of("1", "2"))
                .build());

        // Manually set hold to expire immediately
        SeatHold hold = seatHoldRepository.findByHoldId(holdResponse.getHoldId()).orElseThrow();
        hold.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        seatHoldRepository.save(hold);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicBoolean confirmSucceeded = new AtomicBoolean(false);
        AtomicBoolean cleanupRan = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Try to confirm
        executor.submit(() -> {
            try {
                startLatch.await();
                bookingService.confirmBooking(ConfirmBookingRequest.builder()
                        .holdId(holdResponse.getHoldId()).build());
                confirmSucceeded.set(true);
            } catch (Exception e) {
                // Expected - hold may be expired
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Run cleanup
        executor.submit(() -> {
            try {
                startLatch.await();
                holdCleanupService.releaseExpiredHoldsForEvent(eventId);
                cleanupRan.set(true);
            } catch (Exception e) {
                // Unexpected
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Verify consistency: the hold should be in exactly one final state
        SeatHold finalHold = seatHoldRepository.findByHoldId(holdResponse.getHoldId()).orElseThrow();
        assertThat(finalHold.getStatus()).isIn(HoldStatus.CONFIRMED, HoldStatus.EXPIRED);

        // Verify seat states are consistent with the hold state
        long availableSeats = seatRepository.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
        long heldSeats = seatRepository.countByEventIdAndStatus(eventId, SeatStatus.HELD);
        long bookedSeats = seatRepository.countByEventIdAndStatus(eventId, SeatStatus.BOOKED);

        // Total must always equal totalSeats
        assertThat(availableSeats + heldSeats + bookedSeats).isEqualTo(5);

        // No seats should be in HELD state (either confirmed->BOOKED or expired->AVAILABLE)
        assertThat(heldSeats).isEqualTo(0);

        if (finalHold.getStatus() == HoldStatus.CONFIRMED) {
            assertThat(bookedSeats).isEqualTo(2);
            assertThat(availableSeats).isEqualTo(3);
        } else {
            assertThat(bookedSeats).isEqualTo(0);
            assertThat(availableSeats).isEqualTo(5);
        }
    }
}
