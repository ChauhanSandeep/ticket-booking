package com.ticketbooking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbooking.dto.request.ConfirmBookingRequest;
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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingControllerIntegrationTest {

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
        seatRepository.deleteAll();
        bookingRepository.deleteAll();
        seatHoldRepository.deleteAll();
        eventRepository.deleteAll();

        String result = mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateEventRequest.builder()
                                .name("Test Event")
                                .location("Test Location")
                                .eventDate(LocalDateTime.now().plusDays(30))
                                .totalSeats(10)
                                .build())))
                .andReturn().getResponse().getContentAsString();

        eventId = objectMapper.readTree(result).get("id").asLong();
    }

    private String createHold(String userId, List<String> seats) throws Exception {
        String result = mockMvc.perform(post("/api/v1/events/" + eventId + "/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(HoldSeatsRequest.builder()
                                .userId(userId).seatNumbers(seats).build())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(result).get("holdId").asText();
    }

    @Test
    void fullWorkflow_holdConfirmCancelRebook() throws Exception {
        // Step 1: Hold seats
        String holdId = createHold("user-1", List.of("1", "2", "3"));

        // Step 2: Confirm booking
        String bookingResult = mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfirmBookingRequest.builder().holdId(UUID.fromString(holdId)).build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookedSeats", hasSize(3)))
                .andReturn().getResponse().getContentAsString();

        String bookingRef = objectMapper.readTree(bookingResult).get("bookingReference").asText();

        // Step 3: Verify availability
        mockMvc.perform(get("/api/v1/events/" + eventId + "/availability"))
                .andExpect(jsonPath("$.availableCount").value(7))
                .andExpect(jsonPath("$.bookedCount").value(3))
                .andExpect(jsonPath("$.heldCount").value(0));

        // Step 4: View booking
        mockMvc.perform(get("/api/v1/bookings/" + bookingRef))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookedSeats", hasSize(3)));

        // Step 5: Cancel booking (soft delete)
        mockMvc.perform(patch("/api/v1/bookings/" + bookingRef + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.canceledAt").exists());

        // Step 6: Verify seats freed up
        mockMvc.perform(get("/api/v1/events/" + eventId + "/availability"))
                .andExpect(jsonPath("$.availableCount").value(10))
                .andExpect(jsonPath("$.bookedCount").value(0));

        // Step 7: Re-book same seats
        String newHoldId = createHold("user-2", List.of("1", "2", "3"));
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfirmBookingRequest.builder().holdId(UUID.fromString(newHoldId)).build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void confirmBooking_shouldReturn410ForExpiredHold() throws Exception {
        // Create hold with immediate expiry by manipulating the hold directly
        String holdId = createHold("user-1", List.of("1"));

        // Expire the hold by updating expires_at in the DB
        var hold = seatHoldRepository.findByHoldId(UUID.fromString(holdId)).orElseThrow();
        hold.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        seatHoldRepository.save(hold);

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfirmBookingRequest.builder().holdId(UUID.fromString(holdId)).build())))
                .andExpect(status().isGone());
    }

    @Test
    void cancelBooking_shouldReturn409WhenAlreadyCanceled() throws Exception {
        String holdId = createHold("user-1", List.of("1"));

        String bookingResult = mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfirmBookingRequest.builder().holdId(UUID.fromString(holdId)).build())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String bookingRef = objectMapper.readTree(bookingResult).get("bookingReference").asText();

        // First cancel
        mockMvc.perform(patch("/api/v1/bookings/" + bookingRef + "/cancel"))
                .andExpect(status().isOk());

        // Second cancel should fail
        mockMvc.perform(patch("/api/v1/bookings/" + bookingRef + "/cancel"))
                .andExpect(status().isConflict());
    }

    @Test
    void getUserBookings_shouldReturnAllBookingsForUser() throws Exception {
        String holdId = createHold("user-1", List.of("1", "2"));
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfirmBookingRequest.builder().holdId(UUID.fromString(holdId)).build())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/users/user-1/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userId").value("user-1"));
    }
}
