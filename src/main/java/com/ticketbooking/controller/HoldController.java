package com.ticketbooking.controller;

import com.ticketbooking.dto.request.HoldSeatsRequest;
import com.ticketbooking.dto.response.HoldResponse;
import com.ticketbooking.service.HoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Holds", description = "Temporary seat reservation management")
public class HoldController {

    private final HoldService holdService;

    @PostMapping("/events/{eventId}/holds")
    @Operation(summary = "Hold specific seats", description = "Creates a 5-minute temporary reservation on the specified seats. Returns a holdId for confirmation.")
    public ResponseEntity<HoldResponse> holdSeats(@PathVariable Long eventId,
                                                   @Valid @RequestBody HoldSeatsRequest request) {
        HoldResponse response = holdService.holdSeats(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/holds/{holdId}")
    @Operation(summary = "Get hold status", description = "Returns hold details including held seat numbers and expiry time")
    public ResponseEntity<HoldResponse> getHold(@PathVariable UUID holdId) {
        return ResponseEntity.ok(holdService.getHold(holdId));
    }
}
