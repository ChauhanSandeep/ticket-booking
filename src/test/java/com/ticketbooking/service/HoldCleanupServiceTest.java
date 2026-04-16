package com.ticketbooking.service;

import com.ticketbooking.entity.Event;
import com.ticketbooking.entity.Seat;
import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.HoldStatus;
import com.ticketbooking.entity.enums.SeatStatus;
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
    void releaseHoldIfExpired_shouldExpireActiveHoldAndReleaseSeats() {
        setupClock();
        Event event = createEvent();
        SeatHold expiredHold = SeatHold.builder()
                .id(1L).holdId(UUID.randomUUID()).event(event).userId("user-1")
                .status(HoldStatus.ACTIVE).expiresAt(LocalDateTime.now().minusMinutes(1))
                .createdAt(LocalDateTime.now().minusMinutes(6)).build();

        Seat seat1 = Seat.builder().id(1L).event(event).seatNumber("1").status(SeatStatus.HELD).hold(expiredHold).build();
        Seat seat2 = Seat.builder().id(2L).event(event).seatNumber("2").status(SeatStatus.HELD).hold(expiredHold).build();

        when(seatHoldRepository.findByIdWithLock(1L)).thenReturn(Optional.of(expiredHold));
        when(seatRepository.findByHoldId(1L)).thenReturn(List.of(seat1, seat2));
        when(seatRepository.saveAll(anyList())).thenReturn(List.of(seat1, seat2));

        int released = holdCleanupService.releaseHoldIfExpired(1L);

        assertThat(released).isEqualTo(1);
        assertThat(expiredHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(seat1.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat2.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat1.getHold()).isNull();
    }

    @Test
    void releaseHoldIfExpired_shouldSkipConfirmedHold() {
        Event event = createEvent();
        SeatHold confirmedHold = SeatHold.builder()
                .id(1L).holdId(UUID.randomUUID()).event(event).userId("user-1")
                .status(HoldStatus.CONFIRMED).expiresAt(LocalDateTime.now().minusMinutes(1))
                .createdAt(LocalDateTime.now().minusMinutes(6)).build();

        when(seatHoldRepository.findByIdWithLock(1L)).thenReturn(Optional.of(confirmedHold));

        int released = holdCleanupService.releaseHoldIfExpired(1L);

        assertThat(released).isEqualTo(0);
        verify(seatRepository, never()).findByHoldId(anyLong());
    }

    @Test
    void releaseHoldIfExpired_shouldReturnZeroWhenHoldNotFound() {
        when(seatHoldRepository.findByIdWithLock(99L)).thenReturn(Optional.empty());

        int released = holdCleanupService.releaseHoldIfExpired(99L);

        assertThat(released).isEqualTo(0);
        verify(seatRepository, never()).findByHoldId(anyLong());
    }

    @Test
    void releaseHoldIfExpired_shouldReturnZeroWhenHoldNotYetExpiredByTime() {
        setupClock();
        LocalDateTime mockedNow = LocalDateTime.ofInstant(
                Instant.parse("2026-06-01T12:00:00Z"), ZoneId.systemDefault());
        Event event = createEvent();
        SeatHold stillValidHold = SeatHold.builder()
                .id(1L).holdId(UUID.randomUUID()).event(event).userId("user-1")
                .status(HoldStatus.ACTIVE).expiresAt(mockedNow.plusMinutes(5))
                .createdAt(mockedNow).build();

        when(seatHoldRepository.findByIdWithLock(1L)).thenReturn(Optional.of(stillValidHold));

        int released = holdCleanupService.releaseHoldIfExpired(1L);

        assertThat(released).isEqualTo(0);
        verify(seatRepository, never()).findByHoldId(anyLong());
    }
}
