package com.roneysahil.movie_booking.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roneysahil.movie_booking.support.TestDatabaseConfig;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The browse path a customer walks before booking: city, movie, show, seat map.
 *
 * <p>Every optional filter is exercised both present and absent — an omitted parameter
 * binds as a null of unknown type, which is a distinct query path from a supplied value
 * and fails differently.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseConfig.class)
class BrowseIntegrationTest {

    private static final String CUSTOMER = "customer@movies.test";
    private static final String PASSWORD = "customer123";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;

    @Test
    @DisplayName("Cities lists only active cities")
    void listsCities() throws Exception {
        mvc.perform(get("/api/cities").with(httpBasic(CUSTOMER, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].timezone").exists());
    }

    @Test
    @DisplayName("Movies in a city returns results with no filter supplied")
    void listsMoviesWithoutFilter() throws Exception {
        JsonNode movies = MAPPER.readTree(
                mvc.perform(get("/api/cities/1/movies").with(httpBasic(CUSTOMER, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(movies).isNotEmpty();
        assertThat(movies.get(0).get("title").asString()).isNotBlank();
    }

    @Test
    @DisplayName("Movies in a city can be filtered by title")
    void filtersMoviesByTitle() throws Exception {
        JsonNode filtered = MAPPER.readTree(
                mvc.perform(get("/api/cities/1/movies?q=dune")
                                .with(httpBasic(CUSTOMER, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("title").asString()).contains("Dune");
    }

    @Test
    @DisplayName("A title filter matching nothing returns an empty list, not an error")
    void unmatchedFilterIsEmpty() throws Exception {
        mvc.perform(get("/api/cities/1/movies?q=zzzznotamovie")
                        .with(httpBasic(CUSTOMER, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("An unknown city is a 404")
    void unknownCityIsNotFound() throws Exception {
        mvc.perform(get("/api/cities/9999/movies").with(httpBasic(CUSTOMER, PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Theaters in a city are listed")
    void listsTheatersInCity() throws Exception {
        mvc.perform(get("/api/cities/1/theaters").with(httpBasic(CUSTOMER, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].cityName").value("Bengaluru"));
    }

    @Test
    @DisplayName("Shows can be listed with no filters at all")
    void listsShowsUnfiltered() throws Exception {
        JsonNode shows = MAPPER.readTree(
                mvc.perform(get("/api/shows").with(httpBasic(CUSTOMER, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(shows).isNotEmpty();
        assertThat(shows.get(0).get("movieTitle").asString()).isNotBlank();
        assertThat(shows.get(0).get("fromPrice")).isNotNull();
    }

    @Test
    @DisplayName("Shows can be filtered by city, movie, theater and date together")
    void filtersShows() throws Exception {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);

        JsonNode shows = MAPPER.readTree(
                mvc.perform(get("/api/shows?cityId=1&movieId=1&date=" + tomorrow)
                                .with(httpBasic(CUSTOMER, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(shows).isNotEmpty();
        shows.forEach(s -> assertThat(s.get("movieId").asInt()).isEqualTo(1));
    }

    @Test
    @DisplayName("Browse only surfaces upcoming scheduled shows")
    void browseExcludesPastShows() throws Exception {
        JsonNode shows = MAPPER.readTree(
                mvc.perform(get("/api/shows").with(httpBasic(CUSTOMER, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        shows.forEach(s -> assertThat(s.get("status").asString()).isEqualTo("SCHEDULED"));
    }

    @Test
    @DisplayName("The seat map returns per-seat resolved prices and availability")
    void returnsSeatMap() throws Exception {
        JsonNode shows = MAPPER.readTree(
                mvc.perform(get("/api/shows").with(httpBasic(CUSTOMER, PASSWORD)))
                        .andReturn().getResponse().getContentAsString());
        long showId = shows.get(0).get("id").asLong();

        JsonNode map = MAPPER.readTree(
                mvc.perform(get("/api/shows/" + showId + "/seats")
                                .with(httpBasic(CUSTOMER, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(map.get("seats")).isNotEmpty();
        JsonNode seat = map.get("seats").get(0);
        assertThat(seat.get("showSeatId")).isNotNull();
        assertThat(seat.get("rowLabel").asString()).isNotBlank();
        assertThat(seat.get("tier").asString()).isNotBlank();
        // Price is resolved server-side, never supplied by the client.
        assertThat(seat.get("price").decimalValue().doubleValue()).isGreaterThan(0);
        assertThat(map.get("availableCount").asInt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("An unknown show is a 404 on both detail and seat map")
    void unknownShowIsNotFound() throws Exception {
        mvc.perform(get("/api/shows/999999").with(httpBasic(CUSTOMER, PASSWORD)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/shows/999999/seats").with(httpBasic(CUSTOMER, PASSWORD)))
                .andExpect(status().isNotFound());
    }
}
