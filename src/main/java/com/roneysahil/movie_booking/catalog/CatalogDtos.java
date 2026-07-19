package com.roneysahil.movie_booking.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public final class CatalogDtos {

    private CatalogDtos() {}

    // --- responses ---------------------------------------------------------

    public record CityDto(Long id, String name, String state, String timezone, boolean active) {}

    public record MovieDto(
            Long id,
            String title,
            Integer durationMinutes,
            String language,
            String certification,
            String synopsis,
            boolean active) {}

    public record TheaterDto(
            Long id,
            String name,
            String address,
            Long cityId,
            String cityName,
            Long refundPolicyId,
            boolean active) {}

    public record ScreenDto(Long id, String name, Long theaterId, int totalSeats) {}

    /** Materialized view of a screen's seating: the rows that define it, plus the total. */
    public record SeatLayoutDto(
            Long screenId, String screenName, List<SeatRowSpec> rows, int totalSeats) {}

    // --- requests ----------------------------------------------------------

    public record CityRequest(
            @NotBlank String name, @NotBlank String state, @NotBlank String timezone) {}

    public record TheaterRequest(
            @NotBlank String name, @NotBlank String address, @NotNull Long cityId) {}

    public record MovieRequest(
            @NotBlank String title,
            @NotNull @Positive Integer durationMinutes,
            @NotBlank String language,
            @NotBlank String certification,
            String synopsis) {}

    /** A row of seats sharing a tier. Screens are defined as a list of these. */
    public record SeatRowSpec(
            @NotBlank String rowLabel, @NotNull @Positive Integer seatCount, @NotBlank String tier) {}

    public record CreateScreenRequest(
            @NotBlank String name, @NotNull Long theaterId, @NotEmpty List<SeatRowSpec> rows) {}

    public record UpdateSeatLayoutRequest(@NotEmpty List<SeatRowSpec> rows) {}

    public record AssignRefundPolicyRequest(@NotNull Long refundPolicyId) {}
}
