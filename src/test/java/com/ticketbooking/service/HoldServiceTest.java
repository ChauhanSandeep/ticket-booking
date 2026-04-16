package com.ticketbooking.service;

import com.ticketbooking.dto.request.HoldSeatsRequest;
import com.ticketbooking.dto.response.HoldResponse;
import com.ticketbooking.entity.Event;
import com.ticketbooking.entity.Seat;
import com.ticketbooking.entity.SeatHold;
import com.ticketbooking.entity.enums.HoldStatus;
import com.ticketbooking.entity.enums.SeatStatus;
import com.ticketbooking.exception.DuplicateHoldException;
import com.ticketbooking.exception.InvalidSeatException;
import com.ticketbooking.exception.ResourceNotFoundException;
import com.ticketbooking.exception.SeatsUnavailableException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private Clock clock;

    @InjectMocks
    private HoldService holdService;

    private void setupClock() {
        Instant fixedInstant = Instant.parse("2026-06-01T12:00:00Z");
        when(clock.instant()).thenReturn(fixedInstant);
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
    }

    private Event createTestEvent() {
        return Event.builder()
                .id(1L).name("Concert").location("NYC")
                .eventDate(LocalDateTime.now().plusDays(30)).totalSeats(10)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    private Seat createSeat(Event event, String number, SeatStatus status) {
        return Seat.builder()
                .id(Long.parseLong(number)).event(event)
                .seatNumber(number).status(status).build();
    }

    @Test
    void holdSeats_shouldSucceed() {
        setupClock();
        Event event = createTestEvent();
        List<Seat> seats = List.of(
                createSeat(event, "1", SeatStatus.AVAILABLE),
                createSeat(event, "2", SeatStatus.AVAILABLE)
        );

        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.existsByEventIdAndUserIdAndStatus(1L, "user-1", HoldStatus.ACTIVE)).thenReturn(false);
        when(seatRepository.findByEventIdAndSeatNumberIn(1L, List.of("1", "2"))).thenReturn(seats);
        when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(inv -> {
            SeatHold h = inv.getArgument(0);
            h.setId(1L);
            return h;
        });
        when(seatRepository.saveAll(anyList())).thenReturn(seats);

        HoldResponse response = holdService.holdSeats(1L, HoldSeatsRequest.builder()
                .userId("user-1").seatNumbers(List.of("1", "2")).build());

        assertThat(response.getHoldId()).isNotNull();
        assertThat(response.getHeldSeats()).containsExactly("1", "2");
        assertThat(response.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    void holdSeats_shouldThrowWhenEventNotFound() {
        when(eventRepository.findByIdWithLock(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.holdSeats(99L, HoldSeatsRequest.builder()
                .userId("user-1").seatNumbers(List.of("1")).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void holdSeats_shouldThrowOnDuplicateHold() {
        Event event = createTestEvent();
        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.existsByEventIdAndUserIdAndStatus(1L, "user-1", HoldStatus.ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> holdService.holdSeats(1L, HoldSeatsRequest.builder()
                .userId("user-1").seatNumbers(List.of("1")).build()))
                .isInstanceOf(DuplicateHoldException.class);
    }

    @Test
    void holdSeats_shouldThrowOnInvalidSeatNumbers() {
        Event event = createTestEvent();
        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.existsByEventIdAndUserIdAndStatus(1L, "user-1", HoldStatus.ACTIVE)).thenReturn(false);
        when(seatRepository.findByEventIdAndSeatNumberIn(1L, List.of("1", "99")))
                .thenReturn(List.of(createSeat(event, "1", SeatStatus.AVAILABLE)));

        assertThatThrownBy(() -> holdService.holdSeats(1L, HoldSeatsRequest.builder()
                .userId("user-1").seatNumbers(List.of("1", "99")).build()))
                .isInstanceOf(InvalidSeatException.class)
                .hasMessageContaining("99");
    }

    @Test
    void holdSeats_shouldThrowWhenSeatsUnavailable() {
        Event event = createTestEvent();
        List<Seat> seats = List.of(
                createSeat(event, "1", SeatStatus.AVAILABLE),
                createSeat(event, "2", SeatStatus.HELD)
        );

        when(eventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.existsByEventIdAndUserIdAndStatus(1L, "user-1", HoldStatus.ACTIVE)).thenReturn(false);
        when(seatRepository.findByEventIdAndSeatNumberIn(1L, List.of("1", "2"))).thenReturn(seats);

        assertThatThrownBy(() -> holdService.holdSeats(1L, HoldSeatsRequest.builder()
                .userId("user-1").seatNumbers(List.of("1", "2")).build()))
                .isInstanceOf(SeatsUnavailableException.class)
                .hasMessageContaining("2");
    }

    @Test
    void holdSeats_shouldThrowOnDuplicateSeatNumbers() {
        assertThatThrownBy(() -> holdService.holdSeats(1L, HoldSeatsRequest.builder()
                .userId("user-1").seatNumbers(List.of("1", "1")).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getHold_shouldReturnHoldWithSeats() {
        Event event = createTestEvent();
        UUID holdUuid = UUID.randomUUID();
        SeatHold hold = SeatHold.builder()
                .id(1L).holdId(holdUuid).event(event).userId("user-1")
                .status(HoldStatus.ACTIVE).expiresAt(LocalDateTime.now().plusMinutes(5))
                .createdAt(LocalDateTime.now()).build();

        when(seatHoldRepository.findByHoldId(holdUuid)).thenReturn(Optional.of(hold));
        when(seatRepository.findByHoldId(1L)).thenReturn(List.of(
                createSeat(event, "1", SeatStatus.HELD),
                createSeat(event, "2", SeatStatus.HELD)
        ));

        HoldResponse response = holdService.getHold(holdUuid);

        assertThat(response.getHoldId()).isEqualTo(holdUuid);
        assertThat(response.getHeldSeats()).containsExactly("1", "2");
    }
}
