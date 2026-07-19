package com.roneysahil.movie_booking.show;

import com.roneysahil.movie_booking.show.ShowDtos.CreateTierRequest;
import com.roneysahil.movie_booking.show.ShowDtos.PricingConfigDto;
import com.roneysahil.movie_booking.show.ShowDtos.PricingTierDto;
import com.roneysahil.movie_booking.show.ShowDtos.SeatMapDto;
import com.roneysahil.movie_booking.show.ShowDtos.ShowDetailDto;
import com.roneysahil.movie_booking.show.ShowDtos.ShowRequest;
import com.roneysahil.movie_booking.show.ShowDtos.ShowSummaryDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ShowService {

    // --- customer browse ---------------------------------------------------

    /** Filtered show listing. Any filter may be null. */
    List<ShowSummaryDto> findShows(Long cityId, Long movieId, Long theaterId, LocalDate date);

    ShowDetailDto getShow(Long showId);

    /**
     * Seat map for a show. Applies hold expiry at read time, so a seat whose hold has
     * lapsed reads as AVAILABLE even before the sweeper reconciles it.
     */
    SeatMapDto getSeatMap(Long showId);

    // --- admin: shows ------------------------------------------------------

    List<ShowSummaryDto> adminListShows(Long theaterId, LocalDate date);

    ShowDetailDto createShow(ShowRequest request);

    /** Reschedule or reprice. Rejected if the show already has confirmed bookings. */
    ShowDetailDto updateShow(Long showId, ShowRequest request);

    /** Cancels a show. Real implementation refunds every confirmed booking on it. */
    void cancelShow(Long showId);

    // --- admin: pricing tiers ----------------------------------------------

    PricingConfigDto getPricingConfig();

    PricingTierDto upsertTier(CreateTierRequest request);

    PricingTierDto updateTierMultiplier(String tier, BigDecimal multiplier);

    void deleteTier(String tier);

    PricingConfigDto updateWeekendMultiplier(BigDecimal multiplier);
}
