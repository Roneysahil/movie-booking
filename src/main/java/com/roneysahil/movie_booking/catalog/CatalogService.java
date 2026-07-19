package com.roneysahil.movie_booking.catalog;

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
import java.util.List;

public interface CatalogService {

    // --- customer browse (active entities only) ----------------------------

    List<CityDto> listCities();

    List<MovieDto> listMoviesInCity(Long cityId, String q);

    MovieDto getMovie(Long movieId);

    List<TheaterDto> listTheatersInCity(Long cityId);

    // --- admin: cities -----------------------------------------------------

    List<CityDto> adminListCities();

    CityDto createCity(CityRequest request);

    CityDto updateCity(Long cityId, CityRequest request);

    /** Soft delete. Hard deletion would orphan theaters, shows and paid bookings. */
    void deactivateCity(Long cityId);

    // --- admin: theaters ---------------------------------------------------

    List<TheaterDto> adminListTheaters(Long cityId);

    TheaterDto createTheater(TheaterRequest request);

    TheaterDto updateTheater(Long theaterId, TheaterRequest request);

    void deactivateTheater(Long theaterId);

    /** Theatres may override the system default refund policy. */
    TheaterDto assignRefundPolicy(Long theaterId, Long refundPolicyId);

    // --- admin: movies -----------------------------------------------------

    List<MovieDto> adminListMovies();

    MovieDto createMovie(MovieRequest request);

    MovieDto updateMovie(Long movieId, MovieRequest request);

    void deactivateMovie(Long movieId);

    // --- admin: screens and seat layouts -----------------------------------

    List<ScreenDto> adminListScreens(Long theaterId);

    ScreenDto createScreen(CreateScreenRequest request);

    SeatLayoutDto getSeatLayout(Long screenId);

    /**
     * Replaces a screen's seating. Rejected with 409 if the screen has future shows —
     * remapping seats under sold tickets would invalidate them.
     */
    SeatLayoutDto updateSeatLayout(Long screenId, UpdateSeatLayoutRequest request);
}
