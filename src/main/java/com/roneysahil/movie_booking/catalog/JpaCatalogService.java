package com.roneysahil.movie_booking.catalog;

import com.roneysahil.movie_booking.catalog.CatalogDtos.CityDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.CityRequest;
import com.roneysahil.movie_booking.catalog.CatalogDtos.CreateScreenRequest;
import com.roneysahil.movie_booking.catalog.CatalogDtos.MovieDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.MovieRequest;
import com.roneysahil.movie_booking.catalog.CatalogDtos.ScreenDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.SeatLayoutDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.SeatRowSpec;
import com.roneysahil.movie_booking.catalog.CatalogDtos.TheaterDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.TheaterRequest;
import com.roneysahil.movie_booking.catalog.CatalogDtos.UpdateSeatLayoutRequest;
import com.roneysahil.movie_booking.catalog.domain.City;
import com.roneysahil.movie_booking.catalog.domain.Movie;
import com.roneysahil.movie_booking.catalog.domain.Screen;
import com.roneysahil.movie_booking.catalog.domain.Seat;
import com.roneysahil.movie_booking.catalog.domain.Theater;
import com.roneysahil.movie_booking.catalog.repository.CityRepository;
import com.roneysahil.movie_booking.catalog.repository.MovieRepository;
import com.roneysahil.movie_booking.catalog.repository.ScreenRepository;
import com.roneysahil.movie_booking.catalog.repository.SeatRepository;
import com.roneysahil.movie_booking.catalog.repository.TheaterRepository;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.BusinessRuleException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.NotFoundException;
import com.roneysahil.movie_booking.refund.domain.RefundPolicy;
import com.roneysahil.movie_booking.refund.repository.RefundPolicyRepository;
import com.roneysahil.movie_booking.show.repository.PricingTierRepository;
import com.roneysahil.movie_booking.show.repository.ShowRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaCatalogService implements CatalogService {

    private final CityRepository cities;
    private final TheaterRepository theaters;
    private final MovieRepository movies;
    private final ScreenRepository screens;
    private final SeatRepository seats;
    private final ShowRepository shows;
    private final PricingTierRepository pricingTiers;
    private final RefundPolicyRepository refundPolicies;

    public JpaCatalogService(
            CityRepository cities,
            TheaterRepository theaters,
            MovieRepository movies,
            ScreenRepository screens,
            SeatRepository seats,
            ShowRepository shows,
            PricingTierRepository pricingTiers,
            RefundPolicyRepository refundPolicies) {
        this.cities = cities;
        this.theaters = theaters;
        this.movies = movies;
        this.screens = screens;
        this.seats = seats;
        this.shows = shows;
        this.pricingTiers = pricingTiers;
        this.refundPolicies = refundPolicies;
    }

    // --- customer browse ---------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<CityDto> listCities() {
        return cities.findByActiveTrueOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MovieDto> listMoviesInCity(Long cityId, String q) {
        requireCity(cityId);
        String filter = (q == null || q.isBlank()) ? null : q.trim();
        return movies.findShowingInCity(cityId, filter).stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MovieDto getMovie(Long movieId) {
        Movie movie = requireMovie(movieId);
        if (!movie.isActive()) {
            throw new NotFoundException("Movie " + movieId + " not found");
        }
        return toDto(movie);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TheaterDto> listTheatersInCity(Long cityId) {
        requireCity(cityId);
        return theaters.findActiveInCity(cityId).stream().map(this::toDto).toList();
    }

    // --- admin: cities -----------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<CityDto> adminListCities() {
        return cities.findAll().stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public CityDto createCity(CityRequest r) {
        City city = new City();
        city.setName(r.name());
        city.setState(r.state());
        city.setTimezone(r.timezone());
        city.setActive(true);
        return toDto(cities.save(city));
    }

    @Override
    @Transactional
    public CityDto updateCity(Long cityId, CityRequest r) {
        City city = requireCity(cityId);
        city.setName(r.name());
        city.setState(r.state());
        city.setTimezone(r.timezone());
        return toDto(city);
    }

    @Override
    @Transactional
    public void deactivateCity(Long cityId) {
        requireCity(cityId).setActive(false);
    }

    // --- admin: theaters ---------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<TheaterDto> adminListTheaters(Long cityId) {
        return theaters.findAllWithCity(cityId).stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public TheaterDto createTheater(TheaterRequest r) {
        Theater theater = new Theater();
        theater.setCity(requireCity(r.cityId()));
        theater.setName(r.name());
        theater.setAddress(r.address());
        theater.setActive(true);
        // Inherits the system default until an admin assigns one explicitly.
        refundPolicies.findDefaultWithTiers().ifPresent(theater::setRefundPolicy);
        return toDto(theaters.save(theater));
    }

    @Override
    @Transactional
    public TheaterDto updateTheater(Long theaterId, TheaterRequest r) {
        Theater theater = requireTheater(theaterId);
        theater.setCity(requireCity(r.cityId()));
        theater.setName(r.name());
        theater.setAddress(r.address());
        return toDto(theater);
    }

    @Override
    @Transactional
    public void deactivateTheater(Long theaterId) {
        requireTheater(theaterId).setActive(false);
    }

    @Override
    @Transactional
    public TheaterDto assignRefundPolicy(Long theaterId, Long refundPolicyId) {
        Theater theater = requireTheater(theaterId);
        RefundPolicy policy = refundPolicies.findById(refundPolicyId)
                .orElseThrow(() ->
                        new NotFoundException("Refund policy " + refundPolicyId + " not found"));
        if (!policy.isActive()) {
            throw new BusinessRuleException("Cannot assign an inactive refund policy");
        }
        theater.setRefundPolicy(policy);
        return toDto(theater);
    }

    // --- admin: movies -----------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<MovieDto> adminListMovies() {
        return movies.findAll().stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public MovieDto createMovie(MovieRequest r) {
        Movie movie = new Movie();
        applyMovie(movie, r);
        movie.setActive(true);
        return toDto(movies.save(movie));
    }

    @Override
    @Transactional
    public MovieDto updateMovie(Long movieId, MovieRequest r) {
        Movie movie = requireMovie(movieId);
        applyMovie(movie, r);
        return toDto(movie);
    }

    @Override
    @Transactional
    public void deactivateMovie(Long movieId) {
        requireMovie(movieId).setActive(false);
    }

    // --- admin: screens and seat layouts -----------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ScreenDto> adminListScreens(Long theaterId) {
        return screens.findAllWithTheater(theaterId).stream()
                .map(s -> new ScreenDto(
                        s.getId(),
                        s.getName(),
                        s.getTheater().getId(),
                        seats.findByScreenIdOrderByRowLabelAscSeatNumberAsc(s.getId()).size()))
                .toList();
    }

    @Override
    @Transactional
    public ScreenDto createScreen(CreateScreenRequest r) {
        Theater theater = requireTheater(r.theaterId());
        validateRows(r.rows());

        Screen screen = new Screen();
        screen.setTheater(theater);
        screen.setName(r.name());
        screen.setActive(true);
        screens.save(screen);

        materializeSeats(screen, r.rows());
        return new ScreenDto(screen.getId(), screen.getName(), theater.getId(), totalSeats(r.rows()));
    }

    @Override
    @Transactional(readOnly = true)
    public SeatLayoutDto getSeatLayout(Long screenId) {
        Screen screen = requireScreen(screenId);
        List<Seat> existing = seats.findByScreenIdOrderByRowLabelAscSeatNumberAsc(screenId);

        // Collapse individual seats back into the row specification that describes them.
        Map<String, SeatRowSpec> rows = new LinkedHashMap<>();
        for (Seat seat : existing) {
            rows.merge(
                    seat.getRowLabel(),
                    new SeatRowSpec(seat.getRowLabel(), 1, seat.getTier()),
                    (a, b) -> new SeatRowSpec(a.rowLabel(), a.seatCount() + 1, a.tier()));
        }
        return new SeatLayoutDto(
                screen.getId(), screen.getName(), List.copyOf(rows.values()), existing.size());
    }

    @Override
    @Transactional
    public SeatLayoutDto updateSeatLayout(Long screenId, UpdateSeatLayoutRequest r) {
        Screen screen = requireScreen(screenId);
        validateRows(r.rows());

        // Real check, not a heuristic: show_seats already materialized against the old
        // seat rows would be orphaned, and any sold ticket would point at a seat that no
        // longer exists.
        long scheduled = shows.countScheduledOnScreen(screenId);
        if (scheduled > 0) {
            throw new BusinessRuleException(
                    "Screen " + screenId + " has " + scheduled
                            + " scheduled show(s); remapping seats would invalidate sold tickets");
        }

        seats.deleteByScreenId(screenId);
        materializeSeats(screen, r.rows());
        return new SeatLayoutDto(
                screen.getId(), screen.getName(), List.copyOf(r.rows()), totalSeats(r.rows()));
    }

    // --- helpers -----------------------------------------------------------

    private void materializeSeats(Screen screen, List<SeatRowSpec> rows) {
        List<Seat> created = new ArrayList<>();
        for (SeatRowSpec row : rows) {
            for (int n = 1; n <= row.seatCount(); n++) {
                Seat seat = new Seat();
                seat.setScreen(screen);
                seat.setRowLabel(row.rowLabel().toUpperCase(Locale.ROOT));
                seat.setSeatNumber(n);
                seat.setTier(row.tier().toUpperCase(Locale.ROOT));
                seat.setActive(true);
                created.add(seat);
            }
        }
        seats.saveAll(created);
    }

    private void validateRows(List<SeatRowSpec> rows) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (SeatRowSpec row : rows) {
            String label = row.rowLabel().toUpperCase(Locale.ROOT);
            if (seen.putIfAbsent(label, true) != null) {
                throw new BusinessRuleException("Duplicate row label: " + label);
            }
            // An unknown tier would price at 1.0 silently, which is worse than failing.
            pricingTiers.findByTier(row.tier().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new BusinessRuleException(
                            "Unknown pricing tier: " + row.tier()));
        }
    }

    private int totalSeats(List<SeatRowSpec> rows) {
        return rows.stream().mapToInt(SeatRowSpec::seatCount).sum();
    }

    private void applyMovie(Movie movie, MovieRequest r) {
        movie.setTitle(r.title());
        movie.setDurationMinutes(r.durationMinutes());
        movie.setLanguage(r.language());
        movie.setCertification(r.certification());
        movie.setSynopsis(r.synopsis());
    }

    private City requireCity(Long cityId) {
        return cities.findById(cityId)
                .orElseThrow(() -> new NotFoundException("City " + cityId + " not found"));
    }

    private Theater requireTheater(Long theaterId) {
        return theaters.findWithCity(theaterId)
                .orElseThrow(() -> new NotFoundException("Theater " + theaterId + " not found"));
    }

    private Movie requireMovie(Long movieId) {
        return movies.findById(movieId)
                .orElseThrow(() -> new NotFoundException("Movie " + movieId + " not found"));
    }

    private Screen requireScreen(Long screenId) {
        return screens.findWithTheater(screenId)
                .orElseThrow(() -> new NotFoundException("Screen " + screenId + " not found"));
    }

    // --- mapping -----------------------------------------------------------

    private CityDto toDto(City c) {
        return new CityDto(c.getId(), c.getName(), c.getState(), c.getTimezone(), c.isActive());
    }

    private MovieDto toDto(Movie m) {
        return new MovieDto(
                m.getId(), m.getTitle(), m.getDurationMinutes(), m.getLanguage(),
                m.getCertification(), m.getSynopsis(), m.isActive());
    }

    private TheaterDto toDto(Theater t) {
        return new TheaterDto(
                t.getId(),
                t.getName(),
                t.getAddress(),
                t.getCity().getId(),
                t.getCity().getName(),
                t.getRefundPolicy() == null ? null : t.getRefundPolicy().getId(),
                t.isActive());
    }
}
