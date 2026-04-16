package com.ticketbooking.exception;

import java.util.UUID;

public class HoldExpiredException extends RuntimeException {

    public HoldExpiredException(UUID holdId) {
        super(String.format("Hold '%s' has expired and is no longer valid", holdId));
    }
}
