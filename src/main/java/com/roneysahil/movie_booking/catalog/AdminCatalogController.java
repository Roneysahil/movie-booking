package com.roneysahil.movie_booking.catalog;

import com.roneysahil.movie_booking.catalog.CatalogDtos.AssignRefundPolicyRequest;
import com.roneysahil.movie_booking.catalog.CatalogDtos.CityDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.CityRequest;
import com.roneysahil.movie_booking.catalog.CatalogDtos.CreateScreenRequest;
import com.roneysahil.movie_booking.catalog.CatalogDtos.MovieDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.MovieRequest;
import com.roneysahil.movie_booking.catalog.CatalogDtos.ScreenDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.SeatLayoutDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.TheaterDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.TheaterRequest;
import com.roneysahil.movie_booking.catalog.CatalogDtos.UpdateSeatLayoutRequest;
import jakarta.validation.Valid;
import java.util.List;
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

/**
 * Admin management of cities, theaters, movies, screens and seat layouts.
 * Guarded by URL rules in SecurityConfig; the annotation is defence in depth so the
 * check survives a routing change.
 *
 * <p>DELETE is a soft deactivate throughout: hard deletion would orphan theaters,
 * shows and paid bookings.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogController {

    private final CatalogService catalog;

    public AdminCatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    // --- cities ------------------------------------------------------------

    @GetMapping("/cities")
    public List<CityDto> listCities() {
        return catalog.adminListCities();
    }

    @PostMapping("/cities")
    @ResponseStatus(HttpStatus.CREATED)
    public CityDto createCity(@Valid @RequestBody CityRequest request) {
        return catalog.createCity(request);
    }

    @PutMapping("/cities/{cityId}")
    public CityDto updateCity(@PathVariable Long cityId, @Valid @RequestBody CityRequest request) {
        return catalog.updateCity(cityId, request);
    }

    @DeleteMapping("/cities/{cityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateCity(@PathVariable Long cityId) {
        catalog.deactivateCity(cityId);
    }

    // --- theaters ----------------------------------------------------------

    @GetMapping("/theaters")
    public List<TheaterDto> listTheaters(@RequestParam(required = false) Long cityId) {
        return catalog.adminListTheaters(cityId);
    }

    @PostMapping("/theaters")
    @ResponseStatus(HttpStatus.CREATED)
    public TheaterDto createTheater(@Valid @RequestBody TheaterRequest request) {
        return catalog.createTheater(request);
    }

    @PutMapping("/theaters/{theaterId}")
    public TheaterDto updateTheater(
            @PathVariable Long theaterId, @Valid @RequestBody TheaterRequest request) {
        return catalog.updateTheater(theaterId, request);
    }

    @DeleteMapping("/theaters/{theaterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateTheater(@PathVariable Long theaterId) {
        catalog.deactivateTheater(theaterId);
    }

    /** Theatres may override the system default refund policy. */
    @PutMapping("/theaters/{theaterId}/refund-policy")
    public TheaterDto assignRefundPolicy(
            @PathVariable Long theaterId, @Valid @RequestBody AssignRefundPolicyRequest request) {
        return catalog.assignRefundPolicy(theaterId, request.refundPolicyId());
    }

    // --- movies ------------------------------------------------------------

    @GetMapping("/movies")
    public List<MovieDto> listMovies() {
        return catalog.adminListMovies();
    }

    @PostMapping("/movies")
    @ResponseStatus(HttpStatus.CREATED)
    public MovieDto createMovie(@Valid @RequestBody MovieRequest request) {
        return catalog.createMovie(request);
    }

    @PutMapping("/movies/{movieId}")
    public MovieDto updateMovie(
            @PathVariable Long movieId, @Valid @RequestBody MovieRequest request) {
        return catalog.updateMovie(movieId, request);
    }

    @DeleteMapping("/movies/{movieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateMovie(@PathVariable Long movieId) {
        catalog.deactivateMovie(movieId);
    }

    // --- screens and seat layouts ------------------------------------------

    @GetMapping("/screens")
    public List<ScreenDto> listScreens(@RequestParam(required = false) Long theaterId) {
        return catalog.adminListScreens(theaterId);
    }

    @PostMapping("/screens")
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenDto createScreen(@Valid @RequestBody CreateScreenRequest request) {
        return catalog.createScreen(request);
    }

    @GetMapping("/screens/{screenId}/layout")
    public SeatLayoutDto getLayout(@PathVariable Long screenId) {
        return catalog.getSeatLayout(screenId);
    }

    @PutMapping("/screens/{screenId}/layout")
    public SeatLayoutDto updateLayout(
            @PathVariable Long screenId, @Valid @RequestBody UpdateSeatLayoutRequest request) {
        return catalog.updateSeatLayout(screenId, request);
    }
}
