package com.ticketlab.reservation;

public enum ReservationStatus {

    /** Seat is held, payment not settled yet. Expires at expiresAt. */
    PENDING,

    /** Payment approved. Terminal state. */
    CONFIRMED,

    /** Ran past expiresAt without payment; the seat went back on sale. */
    EXPIRED,

    /** Given up deliberately, by the user or by a failed payment. */
    CANCELLED
}
