package com.ticketlab.common.error;

public class TicketLabException extends RuntimeException {
    private final ErrorCode errorCode;

    public TicketLabException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
    
}
