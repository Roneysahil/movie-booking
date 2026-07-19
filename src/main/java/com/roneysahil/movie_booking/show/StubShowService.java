package com.roneysahil.movie_booking.show;

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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * In-memory placeholder. Seat maps are generated deterministically so the pricing rules
 * (tier multiplier x weekend surcharge) are visible in responses before the schema exists.
 * Pricing config is mutable so admin edits are observable.
 */
@Service
public class StubShowService implements ShowService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final int SEATS_PER_ROW = 8;

    private record ShowRecord(
            Long id,
            Long movieId,
            String movieTitle,
            Long screenId,
            Instant startsAt,
            int durationMinutes,
            BigDecimal basePrice,
            String status) {}

    /** Row layout of the seeded screen: label -> tier. */
    private static final Map<String, String> ROWS = new LinkedHashMap<>();

    static {
        ROWS.put("A", "REGULAR");
        ROWS.put("B", "REGULAR");
        ROWS.put("C", "PREMIUM");
        ROWS.put("D", "PREMIUM");
        ROWS.put("E", "RECLINER");
    }

    /** Pre-sold seats, so a demo map is not uniformly available. */
    private static final Set<Long> SOLD = Set.of(3L, 4L, 21L);

    /** Shows with confirmed bookings. Reschedule and reprice are rejected on these. */
    private static final Set<Long> SHOWS_WITH_BOOKINGS = Set.of(2L);

    private final Map<Long, ShowRecord> shows = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> tierMultipliers = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(500);

    private volatile BigDecimal weekendMultiplier = new BigDecimal("1.20");
    private static final BigDecimal DEFAULT_BASE_PRICE = new BigDecimal("250.00");

    public StubShowService() {
        tierMultipliers.put("REGULAR", new BigDecimal("1.0"));
        tierMultipliers.put("PREMIUM", new BigDecimal("1.5"));
        tierMultipliers.put("RECLINER", new BigDecimal("2.0"));

        LocalDate today = LocalDate.now(ZONE);
        int[] hours = {10, 14, 18, 21};
        for (int i = 0; i < hours.length; i++) {
            long id = i + 1L;
            shows.put(
                    id,
                    new ShowRecord(
                            id,
                            1L,
                            "Dune: Part Three",
                            (id % 2) + 1,
                            today.atTime(hours[i], 0).atZone(ZONE).toInstant(),
                            166,
                            DEFAULT_BASE_PRICE,
                            "SCHEDULED"));
        }
    }

    // --- customer browse ---------------------------------------------------

    @Override
    public List<ShowSummaryDto> findShows(Long cityId, Long movieId, Long theaterId, LocalDate date) {
        return shows.values().stream()
                .filter(s -> "SCHEDULED".equals(s.status()))
                .filter(s -> movieId == null || s.movieId().equals(movieId))
                .filter(s -> date == null || s.startsAt().atZone(ZONE).toLocalDate().equals(date))
                .sorted(Comparator.comparing(ShowRecord::startsAt))
                .map(this::toSummary)
                .toList();
    }

    @Override
    public ShowDetailDto getShow(Long showId) {
        return toDetail(requireShow(showId));
    }

    @Override
    public SeatMapDto getSeatMap(Long showId) {
        ShowRecord show = requireShow(showId);
        List<SeatDto> seats = new ArrayList<>();
        long seatId = 1;
        for (Map.Entry<String, String> row : ROWS.entrySet()) {
            for (int n = 1; n <= SEATS_PER_ROW; n++) {
                String tier = row.getValue();
                seats.add(new SeatDto(
                        seatId,
                        row.getKey(),
                        n,
                        tier,
                        priceFor(tier, show),
                        SOLD.contains(seatId) ? "BOOKED" : "AVAILABLE"));
                seatId++;
            }
        }
        int available = (int) seats.stream().filter(s -> "AVAILABLE".equals(s.status())).count();
        return new SeatMapDto(showId, show.startsAt(), available, seats);
    }

    // --- admin: shows ------------------------------------------------------

    @Override
    public List<ShowSummaryDto> adminListShows(Long theaterId, LocalDate date) {
        return shows.values().stream()
                .filter(s -> date == null || s.startsAt().atZone(ZONE).toLocalDate().equals(date))
                .sorted(Comparator.comparing(ShowRecord::startsAt))
                .map(this::toSummary)
                .toList();
    }

    @Override
    public ShowDetailDto createShow(ShowRequest r) {
        long id = ids.incrementAndGet();
        ShowRecord show = new ShowRecord(
                id,
                r.movieId(),
                "Dune: Part Three",
                r.screenId(),
                r.startsAt(),
                166,
                r.basePrice(),
                "SCHEDULED");
        shows.put(id, show);
        return toDetail(show);
    }

    @Override
    public ShowDetailDto updateShow(Long showId, ShowRequest r) {
        ShowRecord existing = requireShow(showId);
        if (SHOWS_WITH_BOOKINGS.contains(showId)) {
            throw new BusinessRuleException(
                    "Show " + showId
                            + " has confirmed bookings; rescheduling would invalidate sold tickets");
        }
        ShowRecord updated = new ShowRecord(
                showId,
                r.movieId(),
                existing.movieTitle(),
                r.screenId(),
                r.startsAt(),
                existing.durationMinutes(),
                r.basePrice(),
                existing.status());
        shows.put(showId, updated);
        return toDetail(updated);
    }

    @Override
    public void cancelShow(Long showId) {
        ShowRecord show = requireShow(showId);
        shows.put(
                showId,
                new ShowRecord(
                        show.id(), show.movieId(), show.movieTitle(), show.screenId(),
                        show.startsAt(), show.durationMinutes(), show.basePrice(), "CANCELLED"));
        // Real implementation refunds every confirmed booking on this show.
    }

    // --- admin: pricing tiers ----------------------------------------------

    @Override
    public PricingConfigDto getPricingConfig() {
        return new PricingConfigDto(
                tierMultipliers.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> new PricingTierDto(e.getKey(), e.getValue()))
                        .toList(),
                weekendMultiplier,
                DEFAULT_BASE_PRICE);
    }

    @Override
    public PricingTierDto upsertTier(CreateTierRequest r) {
        String tier = r.tier().toUpperCase(Locale.ROOT);
        tierMultipliers.put(tier, r.multiplier());
        return new PricingTierDto(tier, r.multiplier());
    }

    @Override
    public PricingTierDto updateTierMultiplier(String tier, BigDecimal multiplier) {
        String key = tier.toUpperCase(Locale.ROOT);
        if (!tierMultipliers.containsKey(key)) {
            throw new NotFoundException("Pricing tier " + tier + " not found");
        }
        tierMultipliers.put(key, multiplier);
        return new PricingTierDto(key, multiplier);
    }

    @Override
    public void deleteTier(String tier) {
        String key = tier.toUpperCase(Locale.ROOT);
        if (!tierMultipliers.containsKey(key)) {
            throw new NotFoundException("Pricing tier " + tier + " not found");
        }
        if (ROWS.containsValue(key)) {
            throw new BusinessRuleException("Tier " + key + " is in use by existing seat layouts");
        }
        tierMultipliers.remove(key);
    }

    @Override
    public PricingConfigDto updateWeekendMultiplier(BigDecimal multiplier) {
        this.weekendMultiplier = multiplier;
        return getPricingConfig();
    }

    // --- helpers -----------------------------------------------------------

    /** base x tier multiplier x weekend surcharge, rounded to 2dp. */
    private BigDecimal priceFor(String tier, ShowRecord show) {
        BigDecimal price =
                show.basePrice().multiply(tierMultipliers.getOrDefault(tier, BigDecimal.ONE));
        if (isWeekend(show.startsAt())) {
            price = price.multiply(weekendMultiplier);
        }
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isWeekend(Instant instant) {
        DayOfWeek dow = instant.atZone(ZONE).getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private ShowRecord requireShow(Long showId) {
        ShowRecord show = shows.get(showId);
        if (show == null) {
            throw new NotFoundException("Show " + showId + " not found");
        }
        return show;
    }

    private ShowSummaryDto toSummary(ShowRecord s) {
        return new ShowSummaryDto(
                s.id(),
                s.movieId(),
                s.movieTitle(),
                1L,
                "PVR Forum Mall",
                "Screen " + s.screenId(),
                s.startsAt(),
                priceFor("REGULAR", s),
                ROWS.size() * SEATS_PER_ROW - SOLD.size(),
                s.status());
    }

    private ShowDetailDto toDetail(ShowRecord s) {
        return new ShowDetailDto(
                s.id(),
                s.movieId(),
                s.movieTitle(),
                s.screenId(),
                "PVR Forum Mall",
                "Screen " + s.screenId(),
                "Bengaluru",
                s.startsAt(),
                s.startsAt().plusSeconds(s.durationMinutes() * 60L),
                s.basePrice(),
                isWeekend(s.startsAt()),
                s.status());
    }
}
