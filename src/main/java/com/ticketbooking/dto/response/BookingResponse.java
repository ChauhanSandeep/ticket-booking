package com.ticketbooking.dto.response;

import com.ticketbooking.entity.enums.BookingStatus;
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
public class BookingResponse {

    private UUID bookingReference;
    private Long eventId;
    private String userId;
    private List<String> bookedSeats;
    private BookingStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime canceledAt;
}
