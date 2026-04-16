package com.ticketbooking.exception;

public class DuplicateHoldException extends RuntimeException {

    public DuplicateHoldException(Long eventId, String userId) {
        super(String.format("User '%s' already has an active hold for event %d", userId, eventId));
    }
}
