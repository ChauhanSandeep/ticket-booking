package com.ticketbooking.controller;

import com.ticketbooking.dto.request.HoldSeatsRequest;
import com.ticketbooking.dto.response.HoldResponse;
import com.ticketbooking.service.HoldService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class HoldController {

    private final HoldService holdService;

    @PostMapping("/api/v1/events/{eventId}/holds")
    public ResponseEntity<HoldResponse> holdSeats(@PathVariable Long eventId,
                                                   @Valid @RequestBody HoldSeatsRequest request) {
        HoldResponse response = holdService.holdSeats(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/v1/holds/{holdId}")
    public ResponseEntity<HoldResponse> getHold(@PathVariable UUID holdId) {
        return ResponseEntity.ok(holdService.getHold(holdId));
    }
}
