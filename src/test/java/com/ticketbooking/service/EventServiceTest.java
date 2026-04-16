package com.ticketbooking.service;

import com.ticketbooking.dto.request.CreateEventRequest;
import com.ticketbooking.dto.request.UpdateEventRequest;
import com.ticketbooking.dto.response.EventResponse;
import com.ticketbooking.entity.Event;
import com.ticketbooking.entity.enums.BookingStatus;
import com.ticketbooking.entity.enums.HoldStatus;
import com.ticketbooking.exception.EventDeletionException;
import com.ticketbooking.exception.ResourceNotFoundException;
import com.ticketbooking.repository.BookingRepository;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatHoldRepository;
import com.ticketbooking.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private SeatHoldRepository seatHoldRepository;

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private EventService eventService;

    @Test
    void createEvent_shouldCreateEventAndSeats() {
        CreateEventRequest request = CreateEventRequest.builder()
                .name("Concert")
                .location("NYC")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(100)
                .build();

        Event savedEvent = Event.builder()
                .id(1L)
                .name("Concert")
                .location("NYC")
                .eventDate(request.getEventDate())
                .totalSeats(100)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(eventRepository.save(any(Event.class))).thenReturn(savedEvent);
        when(seatRepository.saveAll(anyList())).thenReturn(Collections.emptyList());

        EventResponse response = eventService.createEvent(request);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("Concert");
        assertThat(response.getTotalSeats()).isEqualTo(100);
        verify(seatRepository).saveAll(anyList());
    }

    @Test
    void getEvent_shouldReturnEvent() {
        Event event = Event.builder()
                .id(1L)
                .name("Concert")
                .location("NYC")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(50)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));

        EventResponse response = eventService.getEvent(1L);

        assertThat(response.getName()).isEqualTo("Concert");
    }

    @Test
    void getEvent_shouldThrowWhenNotFound() {
        when(eventRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getEvent(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAllEvents_shouldReturnAllEvents() {
        Event event1 = Event.builder().id(1L).name("Event 1").location("NYC")
                .eventDate(LocalDateTime.now().plusDays(1)).totalSeats(10)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Event event2 = Event.builder().id(2L).name("Event 2").location("LA")
                .eventDate(LocalDateTime.now().plusDays(2)).totalSeats(20)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(eventRepository.findAll()).thenReturn(List.of(event1, event2));

        List<EventResponse> events = eventService.getAllEvents();

        assertThat(events).hasSize(2);
    }

    @Test
    void updateEvent_shouldUpdateFields() {
        Event event = Event.builder()
                .id(1L)
                .name("Old Name")
                .location("Old Location")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(50)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        UpdateEventRequest request = UpdateEventRequest.builder()
                .name("New Name")
                .location("New Location")
                .eventDate(LocalDateTime.now().plusDays(60))
                .build();

        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(eventRepository.save(any(Event.class))).thenReturn(event);

        EventResponse response = eventService.updateEvent(1L, request);

        assertThat(response.getName()).isEqualTo("New Name");
    }

    @Test
    void deleteEvent_shouldThrowWhenActiveHoldsExist() {
        Event event = Event.builder().id(1L).name("Concert").location("NYC")
                .eventDate(LocalDateTime.now().plusDays(30)).totalSeats(50)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.existsByEventIdAndStatus(1L, HoldStatus.ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> eventService.deleteEvent(1L))
                .isInstanceOf(EventDeletionException.class);
    }

    @Test
    void deleteEvent_shouldThrowWhenConfirmedBookingsExist() {
        Event event = Event.builder().id(1L).name("Concert").location("NYC")
                .eventDate(LocalDateTime.now().plusDays(30)).totalSeats(50)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.existsByEventIdAndStatus(1L, HoldStatus.ACTIVE)).thenReturn(false);
        when(bookingRepository.existsByEventIdAndStatus(1L, BookingStatus.CONFIRMED)).thenReturn(true);

        assertThatThrownBy(() -> eventService.deleteEvent(1L))
                .isInstanceOf(EventDeletionException.class);
    }

    @Test
    void deleteEvent_shouldSucceedWhenNoActiveHoldsOrBookings() {
        Event event = Event.builder().id(1L).name("Concert").location("NYC")
                .eventDate(LocalDateTime.now().plusDays(30)).totalSeats(50)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(seatHoldRepository.existsByEventIdAndStatus(1L, HoldStatus.ACTIVE)).thenReturn(false);
        when(bookingRepository.existsByEventIdAndStatus(1L, BookingStatus.CONFIRMED)).thenReturn(false);
        when(seatRepository.findByEventId(1L)).thenReturn(Collections.emptyList());

        eventService.deleteEvent(1L);

        verify(eventRepository).delete(event);
    }
}
