package com.ticketbooking.controller;

import com.ticketbooking.dto.request.ConfirmBookingRequest;
import com.ticketbooking.dto.response.BookingResponse;
import com.ticketbooking.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/api/v1/bookings")
    public ResponseEntity<BookingResponse> confirmBooking(@Valid @RequestBody ConfirmBookingRequest request) {
        BookingResponse response = bookingService.confirmBooking(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/v1/bookings/{bookingReference}")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable UUID bookingReference) {
        return ResponseEntity.ok(bookingService.getBooking(bookingReference));
    }

    @GetMapping("/api/v1/users/{userId}/bookings")
    public ResponseEntity<List<BookingResponse>> getUserBookings(@PathVariable String userId) {
        return ResponseEntity.ok(bookingService.getUserBookings(userId));
    }

    @PatchMapping("/api/v1/bookings/{bookingReference}/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(@PathVariable UUID bookingReference) {
        return ResponseEntity.ok(bookingService.cancelBooking(bookingReference));
    }
}
