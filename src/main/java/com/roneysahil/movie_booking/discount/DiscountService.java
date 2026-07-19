package com.roneysahil.movie_booking.discount;

import com.roneysahil.movie_booking.discount.DiscountDtos.CreateDiscountRequest;
import com.roneysahil.movie_booking.discount.DiscountDtos.DiscountCodeDto;
import com.roneysahil.movie_booking.discount.DiscountDtos.DiscountPreviewDto;
import com.roneysahil.movie_booking.discount.DiscountDtos.ValidateDiscountRequest;
import java.math.BigDecimal;
import java.util.List;

public interface DiscountService {

    /** Non-authoritative preview for the UI. */
    DiscountPreviewDto validate(String username, ValidateDiscountRequest request);

    /**
     * Authoritative discount computation, called inside the booking transaction.
     * Throws DiscountNotApplicableException if the code is invalid, expired, below the
     * minimum order value, or has exhausted its usage limit.
     */
    BigDecimal computeDiscount(String username, String code, BigDecimal subtotal);

    // --- admin -------------------------------------------------------------

    DiscountCodeDto createCode(CreateDiscountRequest request);

    List<DiscountCodeDto> listCodes();
}
