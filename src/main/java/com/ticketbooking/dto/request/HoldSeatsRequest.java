package com.ticketbooking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HoldSeatsRequest {

    @NotBlank(message = "User ID is required")
    private String userId;

    @NotEmpty(message = "At least one seat number is required")
    @Size(max = 10, message = "Cannot hold more than 10 seats at a time")
    private List<@NotBlank(message = "Seat number must not be blank") String> seatNumbers;
}
