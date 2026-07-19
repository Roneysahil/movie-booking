package com.roneysahil.movie_booking.refund;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class RefundPolicyDtos {

    private RefundPolicyDtos() {}

    /**
     * One band of a refund policy: cancel at least {@code minHoursBeforeShow} before
     * showtime and this percentage is refunded. Bands are evaluated highest-hours first.
     */
    public record RefundTierDto(
            @NotNull @Min(0) Integer minHoursBeforeShow,
            @NotNull @Min(0) @Max(100) Integer refundPercent) {}

    public record RefundPolicyDto(
            Long id,
            String name,
            List<RefundTierDto> tiers,
            boolean isDefault,
            boolean active) {}

    public record RefundPolicyRequest(
            @NotBlank String name, @NotEmpty List<@Valid RefundTierDto> tiers) {}

    /** What a given booking would be refunded right now, without cancelling it. */
    public record RefundQuoteDto(
            String policyName,
            long hoursBeforeShow,
            int refundPercent,
            java.math.BigDecimal amountPaid,
            java.math.BigDecimal refundAmount) {}
}
