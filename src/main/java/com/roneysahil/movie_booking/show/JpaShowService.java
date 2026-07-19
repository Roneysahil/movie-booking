package com.roneysahil.movie_booking.show;

import com.roneysahil.movie_booking.booking.repository.BookingRepository;
import com.roneysahil.movie_booking.catalog.domain.Movie;
import com.roneysahil.movie_booking.catalog.domain.Screen;
import com.roneysahil.movie_booking.catalog.domain.Seat;
import com.roneysahil.movie_booking.catalog.repository.MovieRepository;
import com.roneysahil.movie_booking.catalog.repository.ScreenRepository;
import com.roneysahil.movie_booking.catalog.repository.SeatRepository;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.BusinessRuleException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.NotFoundException;
import com.roneysahil.movie_booking.show.ShowDtos.CreateTierRequest;
import com.roneysahil.movie_booking.show.ShowDtos.PricingConfigDto;
import com.roneysahil.movie_booking.show.ShowDtos.PricingTierDto;
import com.roneysahil.movie_booking.show.ShowDtos.SeatDto;
import com.roneysahil.movie_booking.show.ShowDtos.SeatMapDto;
import com.roneysahil.movie_booking.show.ShowDtos.ShowDetailDto;
import com.roneysahil.movie_booking.show.ShowDtos.ShowRequest;
import com.roneysahil.movie_booking.show.ShowDtos.ShowSummaryDto;
import com.roneysahil.movie_booking.show.domain.PricingConfig;
import com.roneysahil.movie_booking.show.domain.PricingTier;
import com.roneysahil.movie_booking.show.domain.Show;
import com.roneysahil.movie_booking.show.domain.ShowSeat;
import com.roneysahil.movie_booking.show.repository.PricingConfigRepository;
import com.roneysahil.movie_booking.show.repository.PricingTierRepository;
import com.roneysahil.movie_booking.show.repository.ShowRepository;
import com.roneysahil.movie_booking.show.repository.ShowSeatRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaShowService implements ShowService {

    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final MovieRepository movies;
    private final ScreenRepository screens;
    private final SeatRepository seats;
    private final PricingTierRepository pricingTiers;
    private final PricingConfigRepository pricingConfig;
    private final BookingRepository bookings;

    public JpaShowService(
            ShowRepository shows,
            ShowSeatRepository showSeats,
            MovieRepository movies,
            ScreenRepository screens,
            SeatRepository seats,
            PricingTierRepository pricingTiers,
            PricingConfigRepository pricingConfig,
            BookingRepository bookings) {
        this.shows = shows;
        this.showSeats = showSeats;
        this.movies = movies;
        this.screens = screens;
        this.seats = seats;
        this.pricingTiers = pricingTiers;
        this.pricingConfig = pricingConfig;
        this.bookings = bookings;
    }

    // --- customer browse ---------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ShowSummaryDto> findShows(
            Long cityId, Long movieId, Long theaterId, LocalDate date) {
        Instant dayStart = null;
        Instant dayEnd = null;
        if (date != null) {
            ZoneId zone = ZoneId.of("Asia/Kolkata");
            dayStart = date.atStartOfDay(zone).toInstant();
            dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        }
        return shows.findForBrowse(cityId, movieId, theaterId, dayStart, dayEnd).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ShowDetailDto getShow(Long showId) {
        return toDetail(requireShow(showId));
    }

    @Override
    @Transactional(readOnly = true)
    public SeatMapDto getSeatMap(Long showId) {
        Show show = requireShow(showId);
        List<ShowSeat> rows = showSeats.findSeatMap(showId);

        List<SeatDto> seatDtos = rows.stream()
                .map(ss -> new SeatDto(
                        ss.getId(),
                        ss.getSeat().getRowLabel(),
                        ss.getSeat().getSeatNumber(),
                        ss.getSeat().getTier(),
                        ss.getPrice(),
                        // Lazy expiry: a seat whose hold has lapsed reads as available
                        // even before the sweeper reconciles the row.
                        ss.isBookable() ? "AVAILABLE" : ss.getStatus().name()))
                .toList();

        int available = (int) seatDtos.stream().filter(s -> "AVAILABLE".equals(s.status())).count();
        return new SeatMapDto(showId, show.getStartsAt(), available, seatDtos);
    }

    // --- admin: shows ------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ShowSummaryDto> adminListShows(Long theaterId, LocalDate date) {
        Instant dayStart = null;
        Instant dayEnd = null;
        if (date != null) {
            ZoneId zone = ZoneId.of("Asia/Kolkata");
            dayStart = date.atStartOfDay(zone).toInstant();
            dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        }
        return shows.findForAdmin(theaterId, dayStart, dayEnd).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional
    public ShowDetailDto createShow(ShowRequest r) {
        Movie movie = movies.findById(r.movieId())
                .orElseThrow(() -> new NotFoundException("Movie " + r.movieId() + " not found"));
        Screen screen = screens.findWithTheater(r.screenId())
                .orElseThrow(() -> new NotFoundException("Screen " + r.screenId() + " not found"));

        if (r.startsAt().isBefore(Instant.now())) {
            throw new BusinessRuleException("Cannot schedule a show in the past");
        }

        Show show = new Show();
        show.setMovie(movie);
        show.setScreen(screen);
        show.setStartsAt(r.startsAt());
        show.setEndsAt(r.startsAt().plusSeconds(movie.getDurationMinutes() * 60L));
        show.setBasePrice(r.basePrice());
        show.setStatus(Show.Status.SCHEDULED);

        try {
            shows.saveAndFlush(show);
        } catch (DataIntegrityViolationException ex) {
            // The EXCLUDE constraint rejects two live shows overlapping on one screen.
            throw new BusinessRuleException(
                    "Screen " + screen.getId() + " already has a show running at that time");
        }

        materializeShowSeats(show, screen);
        return toDetail(show);
    }

    @Override
    @Transactional
    public ShowDetailDto updateShow(Long showId, ShowRequest r) {
        Show show = requireShow(showId);

        long confirmed = bookings.countConfirmedForShow(showId);
        if (confirmed > 0) {
            throw new BusinessRuleException(
                    "Show " + showId + " has " + confirmed
                            + " confirmed booking(s); rescheduling would invalidate sold tickets");
        }

        Movie movie = movies.findById(r.movieId())
                .orElseThrow(() -> new NotFoundException("Movie " + r.movieId() + " not found"));

        boolean screenChanged = !show.getScreen().getId().equals(r.screenId());
        boolean priceChanged = show.getBasePrice().compareTo(r.basePrice()) != 0;
        boolean timeChanged = !show.getStartsAt().equals(r.startsAt());

        if (screenChanged) {
            throw new BusinessRuleException(
                    "A show cannot be moved between screens; cancel it and create a new one");
        }

        show.setMovie(movie);
        show.setStartsAt(r.startsAt());
        show.setEndsAt(r.startsAt().plusSeconds(movie.getDurationMinutes() * 60L));
        show.setBasePrice(r.basePrice());

        try {
            shows.saveAndFlush(show);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException(
                    "That time overlaps another show on the same screen");
        }

        // Repricing or moving the day changes the weekend surcharge, so the materialized
        // seat prices have to be recomputed. Safe here only because no booking exists.
        if (priceChanged || timeChanged) {
            repriceShowSeats(show);
        }
        return toDetail(show);
    }

    @Override
    @Transactional
    public void cancelShow(Long showId) {
        Show show = requireShow(showId);
        show.setStatus(Show.Status.CANCELLED);
        // A production system would refund every confirmed booking here; refunds are
        // customer-initiated in this build, so cancelling only closes the show.
    }

    // --- admin: pricing tiers ----------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PricingConfigDto getPricingConfig() {
        PricingConfig config = requireConfig();
        return new PricingConfigDto(
                pricingTiers.findByActiveTrueOrderByTierAsc().stream()
                        .map(t -> new PricingTierDto(t.getTier(), t.getMultiplier()))
                        .toList(),
                config.getWeekendMultiplier(),
                config.getDefaultBasePrice());
    }

    @Override
    @Transactional
    public PricingTierDto upsertTier(CreateTierRequest r) {
        String name = r.tier().toUpperCase(Locale.ROOT);
        PricingTier tier = pricingTiers.findByTier(name).orElseGet(PricingTier::new);
        tier.setTier(name);
        tier.setMultiplier(r.multiplier());
        tier.setActive(true);
        return new PricingTierDto(name, pricingTiers.save(tier).getMultiplier());
    }

    @Override
    @Transactional
    public PricingTierDto updateTierMultiplier(String tier, BigDecimal multiplier) {
        PricingTier existing = pricingTiers.findByTier(tier.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("Pricing tier " + tier + " not found"));
        existing.setMultiplier(multiplier);
        // Deliberately does not reprice existing shows: changing what a scheduled show
        // costs mid-sale would mean two customers paying different prices for the same
        // seat with no explanation. New shows pick up the new multiplier.
        return new PricingTierDto(existing.getTier(), existing.getMultiplier());
    }

    @Override
    @Transactional
    public void deleteTier(String tier) {
        String name = tier.toUpperCase(Locale.ROOT);
        PricingTier existing = pricingTiers.findByTier(name)
                .orElseThrow(() -> new NotFoundException("Pricing tier " + tier + " not found"));

        long inUse = seats.countByTier(name);
        if (inUse > 0) {
            throw new BusinessRuleException(
                    "Tier " + name + " is used by " + inUse + " seat(s) and cannot be removed");
        }
        pricingTiers.delete(existing);
    }

    @Override
    @Transactional
    public PricingConfigDto updateWeekendMultiplier(BigDecimal multiplier) {
        requireConfig().setWeekendMultiplier(multiplier);
        return getPricingConfig();
    }

    // --- pricing -----------------------------------------------------------

    /**
     * Creates one show_seat per physical seat, with the price resolved once at creation:
     * base x tier multiplier x weekend surcharge. Snapshotting here is what lets an admin
     * change tiers later without altering shows already on sale.
     */
    private void materializeShowSeats(Show show, Screen screen) {
        List<Seat> physical = seats.findByScreenIdOrderByRowLabelAscSeatNumberAsc(screen.getId());
        if (physical.isEmpty()) {
            throw new BusinessRuleException(
                    "Screen " + screen.getId() + " has no seat layout; define one first");
        }
        Map<String, BigDecimal> multipliers = tierMultipliers();
        BigDecimal weekend = weekendMultiplierFor(show);

        List<ShowSeat> created = new ArrayList<>(physical.size());
        for (Seat seat : physical) {
            ShowSeat showSeat = new ShowSeat();
            showSeat.setShow(show);
            showSeat.setSeat(seat);
            showSeat.setStatus(ShowSeat.Status.AVAILABLE);
            showSeat.setPrice(priceFor(show.getBasePrice(), seat.getTier(), multipliers, weekend));
            created.add(showSeat);
        }
        showSeats.saveAll(created);
    }

    private void repriceShowSeats(Show show) {
        Map<String, BigDecimal> multipliers = tierMultipliers();
        BigDecimal weekend = weekendMultiplierFor(show);
        for (ShowSeat showSeat : showSeats.findSeatMap(show.getId())) {
            showSeat.setPrice(priceFor(
                    show.getBasePrice(), showSeat.getSeat().getTier(), multipliers, weekend));
        }
    }

    private BigDecimal priceFor(
            BigDecimal basePrice,
            String tier,
            Map<String, BigDecimal> multipliers,
            BigDecimal weekend) {
        return basePrice
                .multiply(multipliers.getOrDefault(tier, BigDecimal.ONE))
                .multiply(weekend)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Weekend is evaluated in the city's own timezone, not the server's. */
    private BigDecimal weekendMultiplierFor(Show show) {
        ZoneId zone = ZoneId.of(show.getScreen().getTheater().getCity().getTimezone());
        DayOfWeek day = show.getStartsAt().atZone(zone).getDayOfWeek();
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        return weekend ? requireConfig().getWeekendMultiplier() : BigDecimal.ONE;
    }

    private Map<String, BigDecimal> tierMultipliers() {
        return pricingTiers.findAll().stream()
                .collect(Collectors.toMap(PricingTier::getTier, PricingTier::getMultiplier));
    }

    private PricingConfig requireConfig() {
        return pricingConfig.findById(PricingConfig.SINGLETON_ID)
                .orElseThrow(() -> new NotFoundException("Pricing configuration is missing"));
    }

    private Show requireShow(Long showId) {
        return shows.findDetailById(showId)
                .orElseThrow(() -> new NotFoundException("Show " + showId + " not found"));
    }

    // --- mapping -----------------------------------------------------------

    private ShowSummaryDto toSummary(Show s) {
        List<ShowSeat> seatRows = showSeats.findSeatMap(s.getId());
        BigDecimal fromPrice = seatRows.stream()
                .map(ShowSeat::getPrice)
                .min(BigDecimal::compareTo)
                .orElse(s.getBasePrice());
        int available = (int) seatRows.stream().filter(ShowSeat::isBookable).count();

        return new ShowSummaryDto(
                s.getId(),
                s.getMovie().getId(),
                s.getMovie().getTitle(),
                s.getScreen().getTheater().getId(),
                s.getScreen().getTheater().getName(),
                s.getScreen().getName(),
                s.getStartsAt(),
                fromPrice,
                available,
                s.getStatus().name());
    }

    private ShowDetailDto toDetail(Show s) {
        ZoneId zone = ZoneId.of(s.getScreen().getTheater().getCity().getTimezone());
        DayOfWeek day = s.getStartsAt().atZone(zone).getDayOfWeek();
        return new ShowDetailDto(
                s.getId(),
                s.getMovie().getId(),
                s.getMovie().getTitle(),
                s.getScreen().getId(),
                s.getScreen().getTheater().getName(),
                s.getScreen().getName(),
                s.getScreen().getTheater().getCity().getName(),
                s.getStartsAt(),
                s.getEndsAt(),
                s.getBasePrice(),
                day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY,
                s.getStatus().name());
    }
}
