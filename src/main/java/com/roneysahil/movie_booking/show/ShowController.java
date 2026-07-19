package com.roneysahil.movie_booking.show;

import com.roneysahil.movie_booking.show.ShowDtos.SeatMapDto;
import com.roneysahil.movie_booking.show.ShowDtos.ShowDetailDto;
import com.roneysahil.movie_booking.show.ShowDtos.ShowSummaryDto;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Customer-facing show browsing and seat maps. */
@RestController
@RequestMapping("/api/shows")
public class ShowController {

    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    @GetMapping
    public List<ShowSummaryDto> findShows(
            @RequestParam(required = false) Long cityId,
            @RequestParam(required = false) Long movieId,
            @RequestParam(required = false) Long theaterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return shows.findShows(cityId, movieId, theaterId, date);
    }

    @GetMapping("/{showId}")
    public ShowDetailDto show(@PathVariable Long showId) {
        return shows.getShow(showId);
    }

    @GetMapping("/{showId}/seats")
    public SeatMapDto seatMap(@PathVariable Long showId) {
        return shows.getSeatMap(showId);
    }
}
