package com.ticketbooking.exception;

import java.util.UUID;

public class BookingAlreadyCanceledException extends RuntimeException {

    public BookingAlreadyCanceledException(UUID bookingReference) {
        super(String.format("Booking '%s' has already been canceled", bookingReference));
    }
}
