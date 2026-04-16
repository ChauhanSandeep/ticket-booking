package com.ticketbooking.controller;

import com.ticketbooking.dto.request.ConfirmBookingRequest;
import com.ticketbooking.dto.response.BookingResponse;
import com.ticketbooking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Booking confirmation and management")
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/bookings")
    @Operation(summary = "Confirm booking from hold", description = "Converts a valid hold into a permanent booking. Hold must be ACTIVE and not expired.")
    public ResponseEntity<BookingResponse> confirmBooking(@Valid @RequestBody ConfirmBookingRequest request) {
        BookingResponse response = bookingService.confirmBooking(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/bookings/{bookingReference}")
    @Operation(summary = "Get booking details")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable UUID bookingReference) {
        return ResponseEntity.ok(bookingService.getBooking(bookingReference));
    }

    @GetMapping("/users/{userId}/bookings")
    @Operation(summary = "Get all bookings for a user")
    public ResponseEntity<List<BookingResponse>> getUserBookings(@PathVariable String userId) {
        return ResponseEntity.ok(bookingService.getUserBookings(userId));
    }

    @PatchMapping("/bookings/{bookingReference}/cancel")
    @Operation(summary = "Cancel booking", description = "Soft-deletes the booking (marks as CANCELED) and releases seats back to AVAILABLE")
    public ResponseEntity<BookingResponse> cancelBooking(@PathVariable UUID bookingReference) {
        return ResponseEntity.ok(bookingService.cancelBooking(bookingReference));
    }
}
