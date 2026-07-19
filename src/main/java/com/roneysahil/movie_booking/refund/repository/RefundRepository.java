package com.roneysahil.movie_booking.refund.repository;

import com.roneysahil.movie_booking.refund.domain.Refund;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByBookingId(Long bookingId);
}
