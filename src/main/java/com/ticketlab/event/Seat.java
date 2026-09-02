package com.ticketlab.event;

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
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unidirectional on purpose. Event has no getSeats() collection: a
     * sold-out arena would load thousands of rows for anyone who touched it.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "seat_no", nullable = false, length = 20)
    private String seatNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatGrade grade;

    /** Korean won, so whole units. No fractional currency to represent. */
    @Column(nullable = false)
    private int price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Column(nullable = false)
    private long version;

    protected Seat() {
    }

    public Seat(Event event, String seatNo, SeatGrade grade, int price) {
        this.event = event;
        this.seatNo = seatNo;
        this.grade = grade;
        this.price = price;
        this.status = SeatStatus.AVAILABLE;
    }

    public void hold() {
        this.status = SeatStatus.HELD;
    }

    public void sell() {
        this.status = SeatStatus.SOLD;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public SeatGrade getGrade() {
        return grade;
    }

    public int getPrice() {
        return price;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }
}
