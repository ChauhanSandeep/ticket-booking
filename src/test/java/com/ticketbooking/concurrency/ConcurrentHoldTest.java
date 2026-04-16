package com.ticketbooking.concurrency;

import com.ticketbooking.dto.request.HoldSeatsRequest;
import com.ticketbooking.entity.enums.SeatStatus;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatHoldRepository;
import com.ticketbooking.repository.SeatRepository;
import com.ticketbooking.service.EventService;
import com.ticketbooking.service.HoldService;
import com.ticketbooking.dto.request.CreateEventRequest;
import com.ticketbooking.dto.response.EventResponse;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ConcurrentHoldTest {

    @Autowired
    private HoldService holdService;

    @Autowired
    private EventService eventService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private SeatHoldRepository seatHoldRepository;

    @Autowired
    private EventRepository eventRepository;

    private Long eventId;

    @BeforeEach
    void setUp() {
        seatRepository.deleteAll();
        seatHoldRepository.deleteAll();
        eventRepository.deleteAll();

        EventResponse event = eventService.createEvent(CreateEventRequest.builder()
                .name("Concurrent Test Event")
                .location("Test")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(10)
                .build());
        eventId = event.getId();
    }

    @Test
    @Timeout(30)
    void twentyThreadsCompeteForSameSeat_onlyOneSucceeds() throws InterruptedException {
        int threadCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            String userId = "user-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    holdService.holdSeats(eventId, HoldSeatsRequest.builder()
                            .userId(userId)
                            .seatNumbers(List.of("1"))
                            .build());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // All threads start simultaneously
        doneLatch.await();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(19);

        // Verify only one seat is held
        long heldCount = seatRepository.countByEventIdAndStatus(eventId, SeatStatus.HELD);
        assertThat(heldCount).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void fiveThreadsSameUserSameEvent_onlyOneSucceeds() throws InterruptedException {
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            int seatNum = i + 1;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    holdService.holdSeats(eventId, HoldSeatsRequest.builder()
                            .userId("same-user")
                            .seatNumbers(List.of(String.valueOf(seatNum)))
                            .build());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(4);
    }

    @Test
    @Timeout(30)
    void tenThreadsHoldDistinctSeats_allSucceed() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            int seatNum = i + 1;
            String userId = "user-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    holdService.holdSeats(eventId, HoldSeatsRequest.builder()
                            .userId(userId)
                            .seatNumbers(List.of(String.valueOf(seatNum)))
                            .build());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // All should succeed since each thread holds a different seat with a different user
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(failureCount.get()).isEqualTo(0);

        // All 10 seats should be held
        long heldCount = seatRepository.countByEventIdAndStatus(eventId, SeatStatus.HELD);
        assertThat(heldCount).isEqualTo(10);
    }
}
