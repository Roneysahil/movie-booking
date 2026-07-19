package com.roneysahil.movie_booking.discount;

import com.roneysahil.movie_booking.discount.domain.DiscountCode;
import java.math.BigDecimal;

/**
 * Outcome of applying a discount code to a subtotal. {@code code} is null when no code
 * was supplied, in which case {@code amount} is zero.
 */
public record DiscountApplication(DiscountCode code, BigDecimal amount) {

    public static DiscountApplication none() {
        return new DiscountApplication(null, BigDecimal.ZERO.setScale(2));
    }

    public boolean applied() {
        return code != null;
    }
}
