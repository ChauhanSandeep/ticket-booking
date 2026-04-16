package com.ticketbooking.exception;

public class DuplicateBookingException extends RuntimeException {

    public DuplicateBookingException(Long eventId, String userId) {
        super(String.format("User '%s' already has a confirmed booking for event %d", userId, eventId));
    }
}
