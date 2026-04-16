package com.ticketbooking.exception;

import java.util.List;

public class InvalidSeatException extends RuntimeException {

    private final List<String> invalidSeats;

    public InvalidSeatException(List<String> invalidSeats) {
        super(String.format("The following seat numbers do not exist: %s", invalidSeats));
        this.invalidSeats = invalidSeats;
    }

    public List<String> getInvalidSeats() {
        return invalidSeats;
    }
}
