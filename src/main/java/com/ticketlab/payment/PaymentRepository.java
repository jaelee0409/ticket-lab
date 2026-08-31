package com.ticketlab.payment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByReservationId(Long reservationId);

    /** Feeds the reconciliation pass that settles payments left in the dark. */
    List<Payment> findByStatus(PaymentStatus status);
}
