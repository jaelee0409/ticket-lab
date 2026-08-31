package com.ticketlab.payment;

public enum PaymentStatus {

    /** Sent to the provider, no answer yet. */
    PENDING,

    /** The provider confirmed the charge. Terminal state. */
    APPROVED,

    /** The provider explicitly declined. Terminal state. */
    FAILED,

    /**
     * The call to the provider did not come back with an answer: a timeout, a
     * dropped connection, a 5xx. The money may or may not have moved and this
     * side cannot tell which.
     *
     * Guessing here is what corrupts data. Reading it as FAILED releases a seat
     * the customer already paid for; reading it as APPROVED hands out a seat
     * nobody paid for. UNKNOWN records the ignorance instead, and a later
     * reconciliation pass asks the provider what actually happened.
     *
     * A reservation whose payment is UNKNOWN must never be swept by the expiry
     * job - that is the invariant experiment 4 exists to check.
     */
    UNKNOWN
}
