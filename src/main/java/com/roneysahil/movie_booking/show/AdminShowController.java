package com.roneysahil.movie_booking.show;

import com.roneysahil.movie_booking.show.ShowDtos.CreateTierRequest;
import com.roneysahil.movie_booking.show.ShowDtos.PricingConfigDto;
import com.roneysahil.movie_booking.show.ShowDtos.PricingTierDto;
import com.roneysahil.movie_booking.show.ShowDtos.ShowDetailDto;
import com.roneysahil.movie_booking.show.ShowDtos.ShowRequest;
import com.roneysahil.movie_booking.show.ShowDtos.ShowSummaryDto;
import com.roneysahil.movie_booking.show.ShowDtos.UpdateTierRequest;
import com.roneysahil.movie_booking.show.ShowDtos.UpdateWeekendRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin management of shows and pricing tiers. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminShowController {

    private final ShowService shows;

    public AdminShowController(ShowService shows) {
        this.shows = shows;
    }

    // --- shows -------------------------------------------------------------

    @GetMapping("/shows")
    public List<ShowSummaryDto> listShows(
            @RequestParam(required = false) Long theaterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return shows.adminListShows(theaterId, date);
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public ShowDetailDto createShow(@Valid @RequestBody ShowRequest request) {
        return shows.createShow(request);
    }

    @PutMapping("/shows/{showId}")
    public ShowDetailDto updateShow(
            @PathVariable Long showId, @Valid @RequestBody ShowRequest request) {
        return shows.updateShow(showId, request);
    }

    @DeleteMapping("/shows/{showId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelShow(@PathVariable Long showId) {
        shows.cancelShow(showId);
    }

    // --- pricing tiers -----------------------------------------------------

    @GetMapping("/pricing")
    public PricingConfigDto getPricing() {
        return shows.getPricingConfig();
    }

    @PostMapping("/pricing/tiers")
    @ResponseStatus(HttpStatus.CREATED)
    public PricingTierDto createTier(@Valid @RequestBody CreateTierRequest request) {
        return shows.upsertTier(request);
    }

    @PutMapping("/pricing/tiers/{tier}")
    public PricingTierDto updateTier(
            @PathVariable String tier, @Valid @RequestBody UpdateTierRequest request) {
        return shows.updateTierMultiplier(tier, request.multiplier());
    }

    @DeleteMapping("/pricing/tiers/{tier}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTier(@PathVariable String tier) {
        shows.deleteTier(tier);
    }

    /** The weekend surcharge applied on top of the seat tier multiplier. */
    @PutMapping("/pricing/weekend")
    public PricingConfigDto updateWeekend(@Valid @RequestBody UpdateWeekendRequest request) {
        return shows.updateWeekendMultiplier(request.multiplier());
    }
}
