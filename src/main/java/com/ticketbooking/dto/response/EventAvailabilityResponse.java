package com.ticketbooking.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventAvailabilityResponse {

    private Long eventId;
    private String eventName;
    private LocalDateTime eventDate;
    private String location;
    private int totalSeats;
    private long availableCount;
    private long heldCount;
    private long bookedCount;
    private List<SeatDetail> seats;
}
