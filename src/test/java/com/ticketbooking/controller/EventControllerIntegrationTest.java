package com.ticketbooking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbooking.dto.request.CreateEventRequest;
import com.ticketbooking.dto.request.UpdateEventRequest;
import com.ticketbooking.repository.EventRepository;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SeatRepository seatRepository;

    @BeforeEach
    void setUp() {
        seatRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    void createEvent_shouldReturn201WithCreatedEvent() throws Exception {
        CreateEventRequest request = CreateEventRequest.builder()
                .name("Spring Concert")
                .description("A wonderful concert")
                .location("San Francisco")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(100)
                .build();

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Spring Concert"))
                .andExpect(jsonPath("$.totalSeats").value(100))
                .andExpect(jsonPath("$.id").exists());

        // Verify seats were created
        long seatCount = seatRepository.count();
        assert seatCount == 100;
    }

    @Test
    void createEvent_shouldReturn400ForInvalidInput() throws Exception {
        CreateEventRequest request = CreateEventRequest.builder()
                .name("")
                .location("")
                .totalSeats(-1)
                .build();

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void getAllEvents_shouldReturnEmptyListInitially() throws Exception {
        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getEvent_shouldReturn404ForNonExistent() throws Exception {
        mockMvc.perform(get("/api/v1/events/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fullCrudLifecycle() throws Exception {
        // Create
        CreateEventRequest createRequest = CreateEventRequest.builder()
                .name("Tech Conference")
                .location("Austin, TX")
                .eventDate(LocalDateTime.now().plusDays(60))
                .totalSeats(50)
                .build();

        String createResult = mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long eventId = objectMapper.readTree(createResult).get("id").asLong();

        // Read
        mockMvc.perform(get("/api/v1/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tech Conference"));

        // Update
        UpdateEventRequest updateRequest = UpdateEventRequest.builder()
                .name("Tech Conference 2026")
                .location("Austin, TX")
                .eventDate(LocalDateTime.now().plusDays(90))
                .build();

        mockMvc.perform(put("/api/v1/events/" + eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tech Conference 2026"));

        // Delete
        mockMvc.perform(delete("/api/v1/events/" + eventId))
                .andExpect(status().isNoContent());

        // Verify deleted
        mockMvc.perform(get("/api/v1/events/" + eventId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAvailability_shouldReturnPerSeatBreakdown() throws Exception {
        CreateEventRequest request = CreateEventRequest.builder()
                .name("Small Event")
                .location("NYC")
                .eventDate(LocalDateTime.now().plusDays(10))
                .totalSeats(5)
                .build();

        String result = mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long eventId = objectMapper.readTree(result).get("id").asLong();

        mockMvc.perform(get("/api/v1/events/" + eventId + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(5))
                .andExpect(jsonPath("$.availableCount").value(5))
                .andExpect(jsonPath("$.heldCount").value(0))
                .andExpect(jsonPath("$.bookedCount").value(0))
                .andExpect(jsonPath("$.seats", hasSize(5)))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));
    }
}
