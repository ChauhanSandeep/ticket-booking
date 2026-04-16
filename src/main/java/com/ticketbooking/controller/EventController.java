package com.ticketbooking.controller;

import com.ticketbooking.dto.request.CreateEventRequest;
import com.ticketbooking.dto.request.UpdateEventRequest;
import com.ticketbooking.dto.response.EventAvailabilityResponse;
import com.ticketbooking.dto.response.EventResponse;
import com.ticketbooking.service.EventService;
import com.ticketbooking.service.SeatAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Events", description = "Event management and availability")
public class EventController {

    private final EventService eventService;
    private final SeatAvailabilityService seatAvailabilityService;

    @PostMapping("/events")
    @Operation(summary = "Create a new event", description = "Creates an event and auto-generates numbered seat rows")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
        EventResponse response = eventService.createEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/events")
    @Operation(summary = "List all events")
    public ResponseEntity<List<EventResponse>> getAllEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    @GetMapping("/events/{eventId}")
    @Operation(summary = "Get event by ID")
    public ResponseEntity<EventResponse> getEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(eventService.getEvent(eventId));
    }

    @PutMapping("/events/{eventId}")
    @Operation(summary = "Update event details", description = "Updates event name, description, location, and date. Total seats cannot be changed.")
    public ResponseEntity<EventResponse> updateEvent(@PathVariable Long eventId,
                                                      @Valid @RequestBody UpdateEventRequest request) {
        return ResponseEntity.ok(eventService.updateEvent(eventId, request));
    }

    @DeleteMapping("/events/{eventId}")
    @Operation(summary = "Delete event", description = "Fails if event has active holds or confirmed bookings")
    public ResponseEntity<Void> deleteEvent(@PathVariable Long eventId) {
        eventService.deleteEvent(eventId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events/{eventId}/availability")
    @Operation(summary = "Get seat availability", description = "Returns event details with per-seat status breakdown (AVAILABLE, HELD, BOOKED)")
    public ResponseEntity<EventAvailabilityResponse> getAvailability(@PathVariable Long eventId) {
        return ResponseEntity.ok(seatAvailabilityService.getAvailability(eventId));
    }
}
