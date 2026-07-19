package com.roneysahil.movie_booking.discount;

import com.roneysahil.movie_booking.booking.domain.Booking;
import com.roneysahil.movie_booking.discount.DiscountDtos.CreateDiscountRequest;
import com.roneysahil.movie_booking.discount.DiscountDtos.DiscountCodeDto;
import com.roneysahil.movie_booking.discount.DiscountDtos.DiscountPreviewDto;
import com.roneysahil.movie_booking.discount.DiscountDtos.ValidateDiscountRequest;
import com.roneysahil.movie_booking.user.domain.User;
import java.math.BigDecimal;
import java.util.List;

public interface DiscountService {

    /** Non-authoritative preview for the UI. Consumes nothing. */
    DiscountPreviewDto validate(String username, ValidateDiscountRequest request);

    /**
     * Authoritative application, called inside the booking transaction. Validates the
     * code and atomically consumes one use.
     *
     * <p>Returns {@link DiscountApplication#none()} when no code was supplied; throws
     * DiscountNotApplicableException when a supplied code is unusable.
     */
    DiscountApplication apply(User user, String code, BigDecimal subtotal);

    /** Records the redemption against the booking, for per-user limit enforcement. */
    void recordRedemption(DiscountApplication application, User user, Booking booking);

    // --- admin -------------------------------------------------------------

    DiscountCodeDto createCode(CreateDiscountRequest request);

    List<DiscountCodeDto> listCodes();
}
