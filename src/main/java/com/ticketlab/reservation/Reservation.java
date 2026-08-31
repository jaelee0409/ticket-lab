package com.ticketlab.reservation;

import java.time.Instant;

import com.ticketlab.event.Seat;
import com.ticketlab.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * ManyToOne rather than OneToOne: a seat collects several reservations over
     * time as earlier ones expire. Only one may be active at a time, and that
     * rule is enforced by the reservation path, not by the mapping.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Reservation() {
    }

    public Reservation(User user, Seat seat, Instant expiresAt) {
        this.user = user;
        this.seat = seat;
        this.expiresAt = expiresAt;
        this.status = ReservationStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void confirm() {
        this.status = ReservationStatus.CONFIRMED;
        this.expiresAt = null;
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Seat getSeat() {
        return seat;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
