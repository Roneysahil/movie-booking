package com.roneysahil.movie_booking.discount;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class DiscountDtos {

    private DiscountDtos() {}

    public record ValidateDiscountRequest(
            @NotBlank String code, @NotNull Long showId, @NotEmpty List<Long> showSeatIds) {}

    /**
     * Preview only. The authoritative check re-runs inside the booking transaction, since
     * a usage-limited code can be exhausted between preview and booking.
     */
    public record DiscountPreviewDto(
            String code,
            boolean valid,
            String reason,
            BigDecimal subtotal,
            BigDecimal discountAmount,
            BigDecimal total) {}

    // --- admin -------------------------------------------------------------

    public record CreateDiscountRequest(
            @NotBlank String code,
            @NotBlank String type,
            @NotNull BigDecimal value,
            BigDecimal maxDiscount,
            BigDecimal minOrderAmount,
            @NotNull Instant validFrom,
            @NotNull Instant validTo,
            Integer usageLimit,
            Integer perUserLimit) {}

    public record DiscountCodeDto(
            Long id,
            String code,
            String type,
            BigDecimal value,
            BigDecimal maxDiscount,
            BigDecimal minOrderAmount,
            Instant validFrom,
            Instant validTo,
            Integer usageLimit,
            Integer timesUsed,
            boolean active) {}
}
