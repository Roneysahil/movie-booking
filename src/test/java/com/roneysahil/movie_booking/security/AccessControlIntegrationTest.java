package com.roneysahil.movie_booking.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roneysahil.movie_booking.show.domain.Show;
import com.roneysahil.movie_booking.show.domain.ShowSeat;
import com.roneysahil.movie_booking.show.repository.ShowRepository;
import com.roneysahil.movie_booking.show.repository.ShowSeatRepository;
import com.roneysahil.movie_booking.support.TestDatabaseConfig;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Authentication, role separation, and per-resource ownership. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseConfig.class)
class AccessControlIntegrationTest {

    private static final String ADMIN = "admin@movies.test";
    private static final String ADMIN_PW = "admin123";
    private static final String CUSTOMER = "customer@movies.test";
    private static final String OTHER = "priya@movies.test";
    private static final String CUSTOMER_PW = "customer123";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private ShowRepository shows;
    @Autowired private ShowSeatRepository showSeats;

    @Test
    @DisplayName("Unauthenticated requests are rejected")
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/cities")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Wrong password is rejected")
    void badCredentialsAreUnauthorized() throws Exception {
        mvc.perform(get("/api/cities").with(httpBasic(CUSTOMER, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Registration is the only public endpoint")
    void registrationIsPublic() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"email":"newcomer@movies.test",
                                  "password":"supersecret",
                                  "fullName":"New Comer"}
                                 """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Customers cannot reach admin endpoints")
    void customerIsForbiddenFromAdmin() throws Exception {
        mvc.perform(get("/api/admin/refund-policies").with(httpBasic(CUSTOMER, CUSTOMER_PW)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/cities")
                        .with(httpBasic(CUSTOMER, CUSTOMER_PW))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"name":"Pune","state":"Maharashtra","timezone":"Asia/Kolkata"}
                                 """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admins can reach admin endpoints")
    void adminIsPermitted() throws Exception {
        mvc.perform(get("/api/admin/refund-policies").with(httpBasic(ADMIN, ADMIN_PW)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Admins cannot use customer-only booking endpoints")
    void adminIsForbiddenFromCustomerRoutes() throws Exception {
        mvc.perform(get("/api/bookings").with(httpBasic(ADMIN, ADMIN_PW)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A customer cannot read or cancel another customer's booking")
    void bookingsArePrivateToTheirOwner() throws Exception {
        Long showId = futureShowId();
        List<Long> seats = availableSeatIds(showId, 1);

        String holdId = MAPPER.readTree(
                        mvc.perform(post("/api/holds")
                                        .with(httpBasic(CUSTOMER, CUSTOMER_PW))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"showId\":" + showId
                                                + ",\"showSeatIds\":" + seats + "}"))
                                .andReturn().getResponse().getContentAsString())
                .get("holdId").asString();

        String ref = MAPPER.readTree(
                        mvc.perform(post("/api/bookings")
                                        .with(httpBasic(CUSTOMER, CUSTOMER_PW))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"holdId\":\"" + holdId + "\"}"))
                                .andReturn().getResponse().getContentAsString())
                .get("bookingRef").asString();

        // The owner sees it.
        mvc.perform(get("/api/bookings/" + ref).with(httpBasic(CUSTOMER, CUSTOMER_PW)))
                .andExpect(status().isOk());

        // Another customer gets 404, not 403: a 403 would confirm the reference exists
        // and make booking references enumerable.
        mvc.perform(get("/api/bookings/" + ref).with(httpBasic(OTHER, CUSTOMER_PW)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/bookings/" + ref + "/cancel").with(httpBasic(OTHER, CUSTOMER_PW)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A customer cannot read another customer's hold")
    void holdsArePrivateToTheirOwner() throws Exception {
        Long showId = futureShowId();
        List<Long> seats = availableSeatIds(showId, 1);

        String holdId = MAPPER.readTree(
                        mvc.perform(post("/api/holds")
                                        .with(httpBasic(CUSTOMER, CUSTOMER_PW))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"showId\":" + showId
                                                + ",\"showSeatIds\":" + seats + "}"))
                                .andReturn().getResponse().getContentAsString())
                .get("holdId").asString();

        mvc.perform(get("/api/holds/" + holdId).with(httpBasic(OTHER, CUSTOMER_PW)))
                .andExpect(status().isNotFound());
    }

    // --- helpers -----------------------------------------------------------

    private Long futureShowId() {
        return shows.findAll().stream()
                .filter(s -> s.getStatus() == Show.Status.SCHEDULED)
                .filter(s -> s.getStartsAt().isAfter(Instant.now().plusSeconds(72 * 3600)))
                .min(Comparator.comparing(Show::getStartsAt))
                .orElseThrow()
                .getId();
    }

    private List<Long> availableSeatIds(Long showId, int count) {
        return showSeats.findSeatMap(showId).stream()
                .filter(s -> s.getStatus() == ShowSeat.Status.AVAILABLE)
                .map(ShowSeat::getId)
                .limit(count)
                .toList();
    }
}
