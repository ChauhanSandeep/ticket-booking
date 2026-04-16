package com.ticketbooking.dto.response;

import com.ticketbooking.entity.enums.HoldStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HoldResponse {

    private UUID holdId;
    private Long eventId;
    private String userId;
    private List<String> heldSeats;
    private HoldStatus status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
