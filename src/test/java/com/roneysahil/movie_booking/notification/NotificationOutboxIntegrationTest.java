package com.roneysahil.movie_booking.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.roneysahil.movie_booking.booking.BookingDtos.CreateBookingRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.CreateHoldRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.HoldDto;
import com.roneysahil.movie_booking.booking.BookingService;
import com.roneysahil.movie_booking.booking.BookingDtos.PaymentRequest;
import com.roneysahil.movie_booking.notification.domain.NotificationOutbox;
import com.roneysahil.movie_booking.notification.repository.NotificationOutboxRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The outbox is how "notifications delivered without blocking the booking flow" is met.
 * These tests cover both halves: intent is written inside the booking transaction, and a
 * failing transport degrades to a retry rather than costing anyone their booking.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class NotificationOutboxIntegrationTest {

    private static final String CUSTOMER = "customer@movies.test";

    @Autowired private BookingService bookings;
    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private NotificationOutboxRepository outbox;
    @Autowired private ShowRepository shows;
    @Autowired private ShowSeatRepository showSeats;

    @MockitoSpyBean private NotificationSender sender;

    @Test
    @DisplayName("Confirming a booking enqueues a notification and the dispatcher sends it")
    void confirmationIsEnqueuedThenDelivered() {
        String ref = confirmBooking("outbox-happy");

        NotificationOutbox entry = entryFor(ref, NotificationOutbox.Type.BOOKING_CONFIRMATION);
        assertThat(entry.getStatus()).isEqualTo(NotificationOutbox.Status.PENDING);
        assertThat(entry.getRecipient()).isEqualTo(CUSTOMER);

        dispatcher.dispatchPending();

        NotificationOutbox delivered = outbox.findById(entry.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(NotificationOutbox.Status.SENT);
        assertThat(delivered.getAttempts()).isEqualTo(1);
        assertThat(delivered.getLastError()).isNull();
    }

    @Test
    @DisplayName("A failing transport schedules a retry instead of losing the notification")
    void failedDeliveryIsRetriedWithBackoff() {
        doThrow(new IllegalStateException("provider unavailable"))
                .when(sender).send(any(NotificationOutbox.class));

        String ref = confirmBooking("outbox-failure");
        NotificationOutbox entry = entryFor(ref, NotificationOutbox.Type.BOOKING_CONFIRMATION);

        dispatcher.dispatchPending();

        NotificationOutbox afterFailure = outbox.findById(entry.getId()).orElseThrow();
        assertThat(afterFailure.getStatus()).isEqualTo(NotificationOutbox.Status.PENDING);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).contains("provider unavailable");
        // Backed off, so the next pass will skip it rather than hammer the provider.
        assertThat(afterFailure.getNextAttemptAt()).isAfter(Instant.now());

        // Recovery: once the transport works, the same row goes out.
        doNothing().when(sender).send(any(NotificationOutbox.class));
        afterFailure.setNextAttemptAt(Instant.now().minusSeconds(1));
        outbox.saveAndFlush(afterFailure);

        dispatcher.dispatchPending();

        assertThat(outbox.findById(entry.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationOutbox.Status.SENT);
    }

    @Test
    @DisplayName("A notification failure never costs the customer their booking")
    void bookingSurvivesNotificationFailure() {
        doThrow(new IllegalStateException("provider down"))
                .when(sender).send(any(NotificationOutbox.class));

        // The booking must confirm regardless of the transport being broken, because the
        // booking transaction never touches it.
        String ref = confirmBooking("outbox-isolation");

        assertThat(bookings.getBooking(CUSTOMER, ref).status()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("Cancelling enqueues its own notification")
    void cancellationIsEnqueued() {
        String ref = confirmBooking("outbox-cancel");
        bookings.cancel(CUSTOMER, ref);

        assertThat(entryFor(ref, NotificationOutbox.Type.BOOKING_CANCELLED)).isNotNull();
    }

    // --- helpers -----------------------------------------------------------

    private String confirmBooking(String idempotencyKey) {
        Long showId = futureShowId();
        Long seatId = availableSeatId(showId);

        HoldDto hold = bookings.createHold(CUSTOMER, new CreateHoldRequest(showId, List.of(seatId)));
        var booking = bookings.createBooking(CUSTOMER, new CreateBookingRequest(hold.holdId(), null));
        bookings.pay(CUSTOMER, booking.bookingRef(), new PaymentRequest("CARD", idempotencyKey));
        return booking.bookingRef();
    }

    /** Located by booking reference in the payload, which is unique per booking. */
    private NotificationOutbox entryFor(String bookingRef, NotificationOutbox.Type type) {
        return outbox.findAll().stream()
                .filter(n -> n.getType() == type)
                .filter(n -> n.getPayload().contains(bookingRef))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("no " + type + " outbox row for " + bookingRef));
    }

    private Long futureShowId() {
        return shows.findAll().stream()
                .filter(s -> s.getStatus() == Show.Status.SCHEDULED)
                .filter(s -> s.getStartsAt().isAfter(Instant.now().plusSeconds(72 * 3600)))
                .min(Comparator.comparing(Show::getStartsAt))
                .orElseThrow()
                .getId();
    }

    private Long availableSeatId(Long showId) {
        return showSeats.findSeatMap(showId).stream()
                .filter(s -> s.getStatus() == ShowSeat.Status.AVAILABLE)
                .map(ShowSeat::getId)
                .findFirst()
                .orElseThrow();
    }
}
