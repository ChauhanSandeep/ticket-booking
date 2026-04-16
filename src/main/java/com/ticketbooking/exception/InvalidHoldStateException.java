package com.ticketbooking.exception;

import com.ticketbooking.entity.enums.HoldStatus;

import java.util.UUID;

public class InvalidHoldStateException extends RuntimeException {

    public InvalidHoldStateException(UUID holdId, HoldStatus currentStatus) {
        super(String.format("Hold '%s' is in state '%s' and cannot be confirmed", holdId, currentStatus));
    }
}
