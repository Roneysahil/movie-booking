package com.roneysahil.movie_booking.notification;

import com.roneysahil.movie_booking.booking.domain.Booking;
import com.roneysahil.movie_booking.notification.repository.NotificationOutboxRepository;
import com.roneysahil.movie_booking.notification.domain.NotificationOutbox;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes notification intent into the outbox as part of the caller's transaction.
 *
 * <p>Deliberately does not send anything. Calling a mail provider inline would mean a
 * provider timeout rolls back a paid booking — trading a missing email for a lost sale.
 * Delivery is the dispatcher's job, out of band and retryable.
 */
@Component
public class NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final NotificationOutboxRepository outbox;

    public NotificationPublisher(NotificationOutboxRepository outbox) {
        this.outbox = outbox;
    }

    /**
     * Enqueues one notification. The unique constraint on (booking_id, type) makes this
     * naturally idempotent — a retried caller cannot cause a duplicate send.
     */
    public void enqueue(Booking booking, NotificationOutbox.Type type) {
        NotificationOutbox entry = new NotificationOutbox();
        entry.setBookingId(booking.getId());
        entry.setType(type);
        entry.setRecipient(booking.getUser().getEmail());
        entry.setPayload(payloadFor(booking));
        entry.setStatus(NotificationOutbox.Status.PENDING);
        entry.setNextAttemptAt(Instant.now());
        try {
            outbox.save(entry);
        } catch (RuntimeException ex) {
            // Never let a notification failure take down the booking that caused it.
            log.warn("Could not enqueue {} for booking {}", type, booking.getBookingRef(), ex);
        }
    }

    private String payloadFor(Booking booking) {
        return MAPPER.writeValueAsString(new Payload(
                booking.getBookingRef(),
                booking.getShow().getMovie().getTitle(),
                booking.getShow().getScreen().getTheater().getName(),
                booking.getShow().getStartsAt().toString(),
                booking.getTotalAmount().toPlainString(),
                booking.getSeats().stream()
                        .map(s -> s.getShowSeat().getSeat().label())
                        .toList()));
    }

    private record Payload(
            String bookingRef,
            String movieTitle,
            String theaterName,
            String startsAt,
            String totalAmount,
            java.util.List<String> seats) {}
}
