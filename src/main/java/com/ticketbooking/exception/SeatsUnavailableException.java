package com.ticketbooking.exception;

import java.util.List;

public class SeatsUnavailableException extends RuntimeException {

    private final List<String> unavailableSeats;

    public SeatsUnavailableException(List<String> unavailableSeats) {
        super(String.format("The following seats are not available: %s", unavailableSeats));
        this.unavailableSeats = unavailableSeats;
    }

    public List<String> getUnavailableSeats() {
        return unavailableSeats;
    }
}
