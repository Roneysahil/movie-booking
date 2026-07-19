package com.roneysahil.movie_booking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** End-to-end coverage of the customer funnel over HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseConfig.class)
class BookingFlowIntegrationTest {

    private static final String CUSTOMER = "customer@movies.test";
    private static final String PASSWORD = "customer123";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private ShowRepository shows;
    @Autowired private ShowSeatRepository showSeats;

    @Test
    @DisplayName("hold -> book -> pay -> cancel produces a full refund on a distant show")
    void completeFunnel() throws Exception {
        Long showId = futureShowId();
        List<Long> seats = availableSeatIds(showId, 2);

        String holdId = MAPPER.readTree(
                        mvc.perform(post("/api/holds")
                                        .with(httpBasic(CUSTOMER, PASSWORD))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(holdBody(showId, seats)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("holdId")
                .asString();

        JsonNode booking = MAPPER.readTree(
                mvc.perform(post("/api/bookings")
                                .with(httpBasic(CUSTOMER, PASSWORD))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"holdId\":\"" + holdId + "\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());

        String ref = booking.get("bookingRef").asString();
        assertThat(booking.get("seats")).hasSize(2);

        mvc.perform(post("/api/bookings/" + ref + "/payment")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CARD\",\"idempotencyKey\":\"flow-" + ref + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // Seeded shows used here are more than 48 hours out, so STANDARD refunds in full.
        mvc.perform(post("/api/bookings/" + ref + "/cancel").with(httpBasic(CUSTOMER, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundPercent").value(100))
                .andExpect(jsonPath("$.policyName").value("STANDARD"));

        // Cancelling must return the seats to circulation.
        for (Long seatId : seats) {
            assertThat(showSeats.findById(seatId).orElseThrow().getStatus())
                    .isEqualTo(ShowSeat.Status.AVAILABLE);
        }
    }

    @Test
    @DisplayName("Holding an already-held seat returns 409 naming the seat")
    void secondHoldConflicts() throws Exception {
        Long showId = futureShowId();
        List<Long> seats = availableSeatIds(showId, 1);

        mvc.perform(post("/api/holds")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(showId, seats)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/holds")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(showId, seats)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableSeatIds[0]").value(seats.getFirst()));
    }

    @Test
    @DisplayName("A capped percentage discount never exceeds its ceiling")
    void discountRespectsCap() throws Exception {
        Long showId = futureShowId();
        List<Long> seats = availableSeatIds(showId, 2);

        String holdId = MAPPER.readTree(
                        mvc.perform(post("/api/holds")
                                        .with(httpBasic(CUSTOMER, PASSWORD))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(holdBody(showId, seats)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("holdId")
                .asString();

        // FIRST50 is 50% capped at 150, so any subtotal above 300 caps out.
        mvc.perform(post("/api/bookings")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"" + holdId + "\",\"discountCode\":\"FIRST50\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountAmount").value(150.00));
    }

    @Test
    @DisplayName("Expired, exhausted and disabled codes are each rejected with a reason")
    void unusableDiscountCodesAreRejected() throws Exception {
        Long showId = futureShowId();
        List<Long> seats = availableSeatIds(showId, 2);
        String body = "{\"code\":\"%s\",\"showId\":" + showId + ",\"showSeatIds\":["
                + seats.getFirst() + "]}";

        mvc.perform(post("/api/discounts/validate")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("EXPIRED20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("This code has expired"));

        mvc.perform(post("/api/discounts/validate")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("SOLDOUT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("This code has reached its usage limit"));

        mvc.perform(post("/api/discounts/validate")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("DISABLED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("This code is no longer active"));
    }

    @Test
    @DisplayName("Replaying a payment with the same idempotency key does not charge twice")
    void paymentIsIdempotent() throws Exception {
        Long showId = futureShowId();
        List<Long> seats = availableSeatIds(showId, 1);

        String holdId = MAPPER.readTree(
                        mvc.perform(post("/api/holds")
                                        .with(httpBasic(CUSTOMER, PASSWORD))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(holdBody(showId, seats)))
                                .andReturn().getResponse().getContentAsString())
                .get("holdId").asString();

        String ref = MAPPER.readTree(
                        mvc.perform(post("/api/bookings")
                                        .with(httpBasic(CUSTOMER, PASSWORD))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"holdId\":\"" + holdId + "\"}"))
                                .andReturn().getResponse().getContentAsString())
                .get("bookingRef").asString();

        String payment = "{\"method\":\"CARD\",\"idempotencyKey\":\"idem-once-" + ref + "\"}";

        mvc.perform(post("/api/bookings/" + ref + "/payment")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(payment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // Same key again: the original outcome is returned rather than a second charge.
        mvc.perform(post("/api/bookings/" + ref + "/payment")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(payment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(get("/api/bookings/" + ref).with(httpBasic(CUSTOMER, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("An empty seat selection is rejected before any locking happens")
    void validationRejectsEmptySelection() throws Exception {
        mvc.perform(post("/api/holds")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showId\":1,\"showSeatIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.showSeatIds").exists());
    }

    @Test
    @DisplayName("Booking against an unknown hold is a 404")
    void unknownHoldIsNotFound() throws Exception {
        mvc.perform(post("/api/bookings")
                        .with(httpBasic(CUSTOMER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"99999999\"}"))
                .andExpect(status().isNotFound());
    }

    // --- helpers -----------------------------------------------------------

    private String holdBody(Long showId, List<Long> seatIds) {
        return "{\"showId\":" + showId + ",\"showSeatIds\":" + seatIds + "}";
    }

    private Long futureShowId() {
        return shows.findAll().stream()
                .filter(s -> s.getStatus() == Show.Status.SCHEDULED)
                .filter(s -> s.getStartsAt().isAfter(Instant.now().plusSeconds(72 * 3600)))
                .min(Comparator.comparing(Show::getStartsAt))
                .orElseThrow()
                .getId();
    }

    /** Fresh seats each time, so tests sharing a context do not collide. */
    private List<Long> availableSeatIds(Long showId, int count) {
        return showSeats.findSeatMap(showId).stream()
                .filter(s -> s.getStatus() == ShowSeat.Status.AVAILABLE)
                .map(ShowSeat::getId)
                .limit(count)
                .toList();
    }
}
