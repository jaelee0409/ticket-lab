package com.ticketlab.payment;

import java.time.Instant;

import com.ticketlab.reservation.Reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;

@Entity
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true)
    private Reservation reservation;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    /** The provider's own transaction id. Null until the provider answers. */
    @Column(name = "external_tx_id", length = 100)
    private String externalTxId;

    /**
     * Sent with every attempt for this reservation. Retrying an UNKNOWN payment
     * reuses the same key so the provider recognises the repeat and charges once.
     */
    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(Reservation reservation, int amount, String idempotencyKey) {
        this.reservation = reservation;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void approve(String externalTxId) {
        this.status = PaymentStatus.APPROVED;
        this.externalTxId = externalTxId;
        this.updatedAt = Instant.now();
    }

    public void fail() {
        this.status = PaymentStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    /** The provider never answered. Leaves the row for reconciliation to settle. */
    public void markUnknown() {
        this.status = PaymentStatus.UNKNOWN;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Reservation getReservation() {
        return reservation;
    }

    public int getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getExternalTxId() {
        return externalTxId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
