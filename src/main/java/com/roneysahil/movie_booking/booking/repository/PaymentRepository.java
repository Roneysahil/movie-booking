package com.roneysahil.movie_booking.booking.repository;

import com.roneysahil.movie_booking.booking.domain.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Idempotency lookup: a replayed key returns the original payment. */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
