package com.roneysahil.movie_booking.discount.repository;

import com.roneysahil.movie_booking.discount.domain.DiscountRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscountRedemptionRepository extends JpaRepository<DiscountRedemption, Long> {

    /** Enforces the per-user limit on a code. */
    long countByDiscountCodeIdAndUserId(Long discountCodeId, Long userId);
}
