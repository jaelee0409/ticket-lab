package com.ticketlab.performance;

public enum SeatStatus {

    /** Nobody holds this seat. */
    AVAILABLE,

    /** Reserved but not paid for yet; released when the reservation expires. */
    HELD,

    /** Paid for. Terminal state. */
    SOLD
}
