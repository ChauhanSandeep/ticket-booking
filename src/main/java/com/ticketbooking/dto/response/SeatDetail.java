package com.ticketbooking.dto.response;

import com.ticketbooking.entity.enums.SeatStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatDetail {

    private String seatNumber;
    private SeatStatus status;
}
