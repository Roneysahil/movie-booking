package com.roneysahil.movie_booking.show;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ShowDtos {

    private ShowDtos() {}

    public record ShowSummaryDto(
            Long id,
            Long movieId,
            String movieTitle,
            Long theaterId,
            String theaterName,
            String screenName,
            Instant startsAt,
            BigDecimal fromPrice,
            int availableSeats,
            String status) {}

    public record ShowDetailDto(
            Long id,
            Long movieId,
            String movieTitle,
            Long screenId,
            String theaterName,
            String screenName,
            String cityName,
            Instant startsAt,
            Instant endsAt,
            BigDecimal basePrice,
            boolean weekendPricing,
            String status) {}

    /**
     * One seat in the map. {@code price} is the fully resolved per-seat price
     * (base x tier multiplier x weekend surcharge), computed server-side.
     */
    public record SeatDto(
            Long showSeatId,
            String rowLabel,
            int seatNumber,
            String tier,
            BigDecimal price,
            String status) {}

    public record SeatMapDto(
            Long showId, Instant startsAt, int availableCount, List<SeatDto> seats) {}

    // --- admin: shows ------------------------------------------------------

    public record ShowRequest(
            @NotNull Long movieId,
            @NotNull Long screenId,
            @NotNull Instant startsAt,
            @NotNull @Positive BigDecimal basePrice) {}

    // --- admin: pricing tiers ----------------------------------------------

    public record PricingTierDto(String tier, BigDecimal multiplier) {}

    public record PricingConfigDto(
            List<PricingTierDto> tiers, BigDecimal weekendMultiplier, BigDecimal defaultBasePrice) {}

    public record UpdateTierRequest(@NotNull @Positive BigDecimal multiplier) {}

    public record UpdateWeekendRequest(@NotNull @Positive BigDecimal multiplier) {}

    public record CreateTierRequest(
            @NotBlank String tier, @NotNull @Positive BigDecimal multiplier) {}
}
