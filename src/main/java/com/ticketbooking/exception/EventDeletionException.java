package com.ticketbooking.exception;

public class EventDeletionException extends RuntimeException {

    public EventDeletionException(Long eventId) {
        super(String.format("Cannot delete event %d: it has active holds or confirmed bookings", eventId));
    }
}
