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
import com.roneysahil.movie_booking.common.exception.ApiExceptions.BusinessRuleException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.NotFoundException;
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
 * In-memory placeholder so the API contract is exercisable before the schema exists.
 * State is mutable so update and deactivate are observable. Replaced by a JPA-backed
 * implementation once Flyway migrations land.
 */
@Service
public class StubCatalogService implements CatalogService {

    private record ScreenRecord(Long id, String name, Long theaterId, List<SeatRowSpec> rows) {
        int totalSeats() {
            return rows.stream().mapToInt(SeatRowSpec::seatCount).sum();
        }
    }

    private final Map<Long, CityDto> cities = new ConcurrentHashMap<>();
    private final Map<Long, TheaterDto> theaters = new ConcurrentHashMap<>();
    private final Map<Long, MovieDto> movies = new ConcurrentHashMap<>();
    private final Map<Long, ScreenRecord> screens = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    /** Screens with future shows. Layout edits on these are rejected. */
    private static final Set<Long> SCREENS_IN_USE = Set.of(1L);

    private static final List<SeatRowSpec> DEFAULT_ROWS = List.of(
            new SeatRowSpec("A", 8, "REGULAR"),
            new SeatRowSpec("B", 8, "REGULAR"),
            new SeatRowSpec("C", 8, "PREMIUM"),
            new SeatRowSpec("D", 8, "PREMIUM"),
            new SeatRowSpec("E", 8, "RECLINER"));

    public StubCatalogService() {
        cities.put(1L, new CityDto(1L, "Bengaluru", "Karnataka", "Asia/Kolkata", true));
        cities.put(2L, new CityDto(2L, "Mumbai", "Maharashtra", "Asia/Kolkata", true));
        cities.put(3L, new CityDto(3L, "Delhi", "Delhi", "Asia/Kolkata", true));

        theaters.put(1L, new TheaterDto(1L, "PVR Forum Mall", "Koramangala", 1L, "Bengaluru", 1L, true));
        theaters.put(2L, new TheaterDto(2L, "INOX Garuda", "Magrath Road", 1L, "Bengaluru", 1L, true));
        theaters.put(3L, new TheaterDto(3L, "PVR Phoenix", "Lower Parel", 2L, "Mumbai", 1L, true));
        theaters.put(4L, new TheaterDto(4L, "PVR Select City", "Saket", 3L, "Delhi", 1L, true));

        movies.put(1L, new MovieDto(1L, "Dune: Part Three", 166, "English", "UA", "The saga concludes.", true));
        movies.put(2L, new MovieDto(2L, "Laapataa Ladies 2", 124, "Hindi", "U", "Two brides, one train.", true));
        movies.put(3L, new MovieDto(3L, "Kantara: Chapter 2", 148, "Kannada", "UA", "The forest remembers.", true));

        screens.put(1L, new ScreenRecord(1L, "Screen 1", 1L, DEFAULT_ROWS));
        screens.put(2L, new ScreenRecord(2L, "Screen 2", 1L, DEFAULT_ROWS));
    }

    // --- customer browse ---------------------------------------------------

    @Override
    public List<CityDto> listCities() {
        return sorted(cities.values().stream().filter(CityDto::active).toList(), CityDto::id);
    }

    @Override
    public List<MovieDto> listMoviesInCity(Long cityId, String q) {
        requireCity(cityId);
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        return sorted(
                movies.values().stream()
                        .filter(MovieDto::active)
                        .filter(m -> needle == null
                                || m.title().toLowerCase(Locale.ROOT).contains(needle))
                        .toList(),
                MovieDto::id);
    }

    @Override
    public MovieDto getMovie(Long movieId) {
        MovieDto movie = movies.get(movieId);
        if (movie == null || !movie.active()) {
            throw new NotFoundException("Movie " + movieId + " not found");
        }
        return movie;
    }

    @Override
    public List<TheaterDto> listTheatersInCity(Long cityId) {
        requireCity(cityId);
        return sorted(
                theaters.values().stream()
                        .filter(TheaterDto::active)
                        .filter(t -> t.cityId().equals(cityId))
                        .toList(),
                TheaterDto::id);
    }

    // --- admin: cities -----------------------------------------------------

    @Override
    public List<CityDto> adminListCities() {
        return sorted(List.copyOf(cities.values()), CityDto::id);
    }

    @Override
    public CityDto createCity(CityRequest r) {
        long id = ids.incrementAndGet();
        CityDto city = new CityDto(id, r.name(), r.state(), r.timezone(), true);
        cities.put(id, city);
        return city;
    }

    @Override
    public CityDto updateCity(Long cityId, CityRequest r) {
        CityDto existing = requireCity(cityId);
        CityDto updated = new CityDto(cityId, r.name(), r.state(), r.timezone(), existing.active());
        cities.put(cityId, updated);
        return updated;
    }

    @Override
    public void deactivateCity(Long cityId) {
        CityDto c = requireCity(cityId);
        cities.put(cityId, new CityDto(c.id(), c.name(), c.state(), c.timezone(), false));
    }

    // --- admin: theaters ---------------------------------------------------

    @Override
    public List<TheaterDto> adminListTheaters(Long cityId) {
        return sorted(
                theaters.values().stream()
                        .filter(t -> cityId == null || t.cityId().equals(cityId))
                        .toList(),
                TheaterDto::id);
    }

    @Override
    public TheaterDto createTheater(TheaterRequest r) {
        CityDto city = requireCity(r.cityId());
        long id = ids.incrementAndGet();
        TheaterDto theater =
                new TheaterDto(id, r.name(), r.address(), city.id(), city.name(), 1L, true);
        theaters.put(id, theater);
        return theater;
    }

    @Override
    public TheaterDto updateTheater(Long theaterId, TheaterRequest r) {
        TheaterDto existing = requireTheater(theaterId);
        CityDto city = requireCity(r.cityId());
        TheaterDto updated = new TheaterDto(
                theaterId,
                r.name(),
                r.address(),
                city.id(),
                city.name(),
                existing.refundPolicyId(),
                existing.active());
        theaters.put(theaterId, updated);
        return updated;
    }

    @Override
    public void deactivateTheater(Long theaterId) {
        TheaterDto t = requireTheater(theaterId);
        theaters.put(
                theaterId,
                new TheaterDto(
                        t.id(), t.name(), t.address(), t.cityId(), t.cityName(),
                        t.refundPolicyId(), false));
    }

    @Override
    public TheaterDto assignRefundPolicy(Long theaterId, Long refundPolicyId) {
        TheaterDto t = requireTheater(theaterId);
        TheaterDto updated = new TheaterDto(
                t.id(), t.name(), t.address(), t.cityId(), t.cityName(), refundPolicyId, t.active());
        theaters.put(theaterId, updated);
        return updated;
    }

    // --- admin: movies -----------------------------------------------------

    @Override
    public List<MovieDto> adminListMovies() {
        return sorted(List.copyOf(movies.values()), MovieDto::id);
    }

    @Override
    public MovieDto createMovie(MovieRequest r) {
        long id = ids.incrementAndGet();
        MovieDto movie = new MovieDto(
                id, r.title(), r.durationMinutes(), r.language(), r.certification(),
                r.synopsis(), true);
        movies.put(id, movie);
        return movie;
    }

    @Override
    public MovieDto updateMovie(Long movieId, MovieRequest r) {
        MovieDto existing = requireMovie(movieId);
        MovieDto updated = new MovieDto(
                movieId, r.title(), r.durationMinutes(), r.language(), r.certification(),
                r.synopsis(), existing.active());
        movies.put(movieId, updated);
        return updated;
    }

    @Override
    public void deactivateMovie(Long movieId) {
        MovieDto m = requireMovie(movieId);
        movies.put(
                movieId,
                new MovieDto(
                        m.id(), m.title(), m.durationMinutes(), m.language(), m.certification(),
                        m.synopsis(), false));
    }

    // --- admin: screens and seat layouts -----------------------------------

    @Override
    public List<ScreenDto> adminListScreens(Long theaterId) {
        return sorted(
                screens.values().stream()
                        .filter(s -> theaterId == null || s.theaterId().equals(theaterId))
                        .map(s -> new ScreenDto(s.id(), s.name(), s.theaterId(), s.totalSeats()))
                        .toList(),
                ScreenDto::id);
    }

    @Override
    public ScreenDto createScreen(CreateScreenRequest r) {
        requireTheater(r.theaterId());
        rejectDuplicateRows(r.rows());
        long id = ids.incrementAndGet();
        ScreenRecord screen = new ScreenRecord(id, r.name(), r.theaterId(), List.copyOf(r.rows()));
        screens.put(id, screen);
        return new ScreenDto(id, screen.name(), screen.theaterId(), screen.totalSeats());
    }

    @Override
    public SeatLayoutDto getSeatLayout(Long screenId) {
        ScreenRecord s = requireScreen(screenId);
        return new SeatLayoutDto(s.id(), s.name(), s.rows(), s.totalSeats());
    }

    @Override
    public SeatLayoutDto updateSeatLayout(Long screenId, UpdateSeatLayoutRequest r) {
        ScreenRecord existing = requireScreen(screenId);
        if (SCREENS_IN_USE.contains(screenId)) {
            throw new BusinessRuleException(
                    "Screen " + screenId
                            + " has scheduled shows; remapping seats would invalidate sold tickets");
        }
        rejectDuplicateRows(r.rows());
        ScreenRecord updated = new ScreenRecord(
                existing.id(), existing.name(), existing.theaterId(), List.copyOf(r.rows()));
        screens.put(screenId, updated);
        return new SeatLayoutDto(
                updated.id(), updated.name(), updated.rows(), updated.totalSeats());
    }

    // --- helpers -----------------------------------------------------------

    private void rejectDuplicateRows(List<SeatRowSpec> rows) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (SeatRowSpec row : rows) {
            if (seen.putIfAbsent(row.rowLabel().toUpperCase(Locale.ROOT), true) != null) {
                throw new BusinessRuleException("Duplicate row label: " + row.rowLabel());
            }
        }
    }

    private CityDto requireCity(Long cityId) {
        CityDto city = cities.get(cityId);
        if (city == null) {
            throw new NotFoundException("City " + cityId + " not found");
        }
        return city;
    }

    private TheaterDto requireTheater(Long theaterId) {
        TheaterDto theater = theaters.get(theaterId);
        if (theater == null) {
            throw new NotFoundException("Theater " + theaterId + " not found");
        }
        return theater;
    }

    private MovieDto requireMovie(Long movieId) {
        MovieDto movie = movies.get(movieId);
        if (movie == null) {
            throw new NotFoundException("Movie " + movieId + " not found");
        }
        return movie;
    }

    private ScreenRecord requireScreen(Long screenId) {
        ScreenRecord screen = screens.get(screenId);
        if (screen == null) {
            throw new NotFoundException("Screen " + screenId + " not found");
        }
        return screen;
    }

    private <T> List<T> sorted(List<T> items, java.util.function.Function<T, Long> key) {
        return items.stream().sorted(Comparator.comparing(key)).toList();
    }
}
