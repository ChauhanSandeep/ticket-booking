package com.ticketbooking.service;

import com.ticketbooking.entity.Event;
import com.ticketbooking.entity.Seat;
import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.HoldStatus;
import com.ticketbooking.entity.enums.SeatStatus;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatHoldRepository;
import com.ticketbooking.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HoldCleanupServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SeatHoldRepository seatHoldRepository;

    @Mock
    private SeatRepository seatRepository;
    @Mock
    private Clock clock;

    @InjectMocks
    private HoldCleanupService holdCleanupService;

    private void setupClock() {
        Instant fixedInstant = Instant.parse("2026-06-01T12:00:00Z");
        when(clock.instant()).thenReturn(fixedInstant);
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
    }

    private Event createEvent() {
        return Event.builder().id(1L).name("Concert").location("NYC")
                .eventDate(LocalDateTime.now().plusDays(30)).totalSeats(10)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    void releaseExpiredHoldsForEvent_shouldExpireActiveHoldsAndReleaseSeats() {
        setupClock();
        Event event = createEvent();
        SeatHold expiredHold = SeatHold.builder()
                .id(1L).holdId(UUID.randomUUID()).event(event).userId("user-1")
                .status(HoldStatus.ACTIVE).expiresAt(LocalDateTime.now().minusMinutes(1))
                .createdAt(LocalDateTime.now().minusMinutes(6)).build();

        Seat seat1 = Seat.builder().id(1L).event(event).seatNumber("1").status(SeatStatus.HELD).hold(expiredHold).build();
        Seat seat2 = Seat.builder().id(2L).event(event).seatNumber("2").status(SeatStatus.HELD).hold(expiredHold).build();

        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.findByEventIdAndStatusAndExpiresAtBefore(eq(1L), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(List.of(expiredHold));
        when(seatRepository.findByHoldId(1L)).thenReturn(List.of(seat1, seat2));
        when(seatRepository.saveAll(anyList())).thenReturn(List.of(seat1, seat2));

        int released = holdCleanupService.releaseExpiredHoldsForEvent(1L);

        assertThat(released).isEqualTo(1);
        assertThat(expiredHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(seat1.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat2.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat1.getHold()).isNull();
    }

    @Test
    void releaseExpiredHoldsForEvent_shouldSkipConfirmedHolds() {
        setupClock();
        Event event = createEvent();
        SeatHold confirmedHold = SeatHold.builder()
                .id(1L).holdId(UUID.randomUUID()).event(event).userId("user-1")
                .status(HoldStatus.CONFIRMED).expiresAt(LocalDateTime.now().minusMinutes(1))
                .createdAt(LocalDateTime.now().minusMinutes(6)).build();

        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.findByEventIdAndStatusAndExpiresAtBefore(eq(1L), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(List.of(confirmedHold));

        int released = holdCleanupService.releaseExpiredHoldsForEvent(1L);

        assertThat(released).isEqualTo(0);
        verify(seatRepository, never()).findByHoldId(anyLong());
    }

    @Test
    void releaseExpiredHoldsForEvent_shouldHandleNoExpiredHolds() {
        setupClock();
        Event event = createEvent();

        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.findByEventIdAndStatusAndExpiresAtBefore(eq(1L), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(List.of());

        int released = holdCleanupService.releaseExpiredHoldsForEvent(1L);

        assertThat(released).isEqualTo(0);
    }
}
