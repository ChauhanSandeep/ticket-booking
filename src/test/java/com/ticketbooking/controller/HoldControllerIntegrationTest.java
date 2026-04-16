package com.ticketbooking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbooking.dto.request.CreateEventRequest;
import com.ticketbooking.dto.request.HoldSeatsRequest;
import com.ticketbooking.repository.BookingRepository;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatHoldRepository;
import com.ticketbooking.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HoldControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void setUp() throws Exception {
        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        seatHoldRepository.deleteAll();
        eventRepository.deleteAll();

        CreateEventRequest request = CreateEventRequest.builder()
                .name("Test Event")
                .location("Test Location")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(10)
                .build();

        String result = mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();

        eventId = objectMapper.readTree(result).get("id").asLong();
    }

    @Test
    void holdSeats_shouldReturn201() throws Exception {
        HoldSeatsRequest request = HoldSeatsRequest.builder()
                .userId("user-1")
                .seatNumbers(List.of("1", "2", "3"))
                .build();

        mockMvc.perform(post("/api/v1/events/" + eventId + "/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").exists())
                .andExpect(jsonPath("$.heldSeats", hasSize(3)))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void holdSeats_shouldReturn409ForUnavailableSeats() throws Exception {
        // First hold takes seats 1-3
        HoldSeatsRequest first = HoldSeatsRequest.builder()
                .userId("user-1")
                .seatNumbers(List.of("1", "2", "3"))
                .build();
        mockMvc.perform(post("/api/v1/events/" + eventId + "/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        // Second hold tries to take seat 2 (already held)
        HoldSeatsRequest second = HoldSeatsRequest.builder()
                .userId("user-2")
                .seatNumbers(List.of("2", "4"))
                .build();
        mockMvc.perform(post("/api/v1/events/" + eventId + "/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details", contains("2")));
    }

    @Test
    void holdSeats_shouldReturn409ForDuplicateHold() throws Exception {
        HoldSeatsRequest first = HoldSeatsRequest.builder()
                .userId("user-1")
                .seatNumbers(List.of("1"))
                .build();
        mockMvc.perform(post("/api/v1/events/" + eventId + "/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        HoldSeatsRequest duplicate = HoldSeatsRequest.builder()
                .userId("user-1")
                .seatNumbers(List.of("2"))
                .build();
        mockMvc.perform(post("/api/v1/events/" + eventId + "/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict());
    }

    @Test
    void holdSeats_shouldReturn400ForInvalidSeatNumbers() throws Exception {
        HoldSeatsRequest request = HoldSeatsRequest.builder()
                .userId("user-1")
                .seatNumbers(List.of("1", "999"))
                .build();

        mockMvc.perform(post("/api/v1/events/" + eventId + "/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", contains("999")));
    }

    @Test
    void getHold_shouldReturnHoldDetails() throws Exception {
        HoldSeatsRequest request = HoldSeatsRequest.builder()
                .userId("user-1")
                .seatNumbers(List.of("5", "6"))
                .build();

        String holdResult = mockMvc.perform(post("/api/v1/events/" + eventId + "/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String holdId = objectMapper.readTree(holdResult).get("holdId").asText();

        mockMvc.perform(get("/api/v1/holds/" + holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.heldSeats", hasSize(2)));
    }

    @Test
    void holdSeats_shouldUpdateAvailability() throws Exception {
        HoldSeatsRequest request = HoldSeatsRequest.builder()
                .userId("user-1")
                .seatNumbers(List.of("1", "2", "3"))
                .build();

        mockMvc.perform(post("/api/v1/events/" + eventId + "/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/events/" + eventId + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCount").value(7))
                .andExpect(jsonPath("$.heldCount").value(3))
                .andExpect(jsonPath("$.bookedCount").value(0));
    }
}
