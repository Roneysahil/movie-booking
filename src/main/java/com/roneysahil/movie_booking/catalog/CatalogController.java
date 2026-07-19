package com.roneysahil.movie_booking.catalog;

import com.roneysahil.movie_booking.catalog.CatalogDtos.CityDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.MovieDto;
import com.roneysahil.movie_booking.catalog.CatalogDtos.TheaterDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Customer-facing browse endpoints. */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/cities")
    public List<CityDto> cities() {
        return catalog.listCities();
    }

    @GetMapping("/cities/{cityId}/movies")
    public List<MovieDto> moviesInCity(
            @PathVariable Long cityId, @RequestParam(required = false) String q) {
        return catalog.listMoviesInCity(cityId, q);
    }

    @GetMapping("/cities/{cityId}/theaters")
    public List<TheaterDto> theatersInCity(@PathVariable Long cityId) {
        return catalog.listTheatersInCity(cityId);
    }

    @GetMapping("/movies/{movieId}")
    public MovieDto movie(@PathVariable Long movieId) {
        return catalog.getMovie(movieId);
    }
}
