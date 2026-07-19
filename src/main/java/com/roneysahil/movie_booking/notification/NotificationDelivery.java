package com.roneysahil.movie_booking.notification;

import com.roneysahil.movie_booking.notification.domain.NotificationOutbox;
import com.roneysahil.movie_booking.notification.repository.NotificationOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers one outbox row in its own transaction.
 *
 * <p>Deliberately a separate bean from {@link NotificationDispatcher}: calling a
 * {@code @Transactional} method from another method of the same class bypasses the Spring
 * proxy entirely, so the new transaction would silently never start and one poison
 * message could roll back the whole batch.
 */
@Component
public class NotificationDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);

    /** After this many failures a row is parked for manual inspection. */
    private static final int MAX_ATTEMPTS = 5;

    private final NotificationOutboxRepository outbox;
    private final NotificationSender sender;

    public NotificationDelivery(NotificationOutboxRepository outbox, NotificationSender sender) {
        this.outbox = outbox;
        this.sender = sender;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(Long entryId) {
        NotificationOutbox entry = outbox.findById(entryId).orElse(null);
        if (entry == null || entry.getStatus() != NotificationOutbox.Status.PENDING) {
            return;
        }

        try {
            sender.send(entry);
            entry.setStatus(NotificationOutbox.Status.SENT);
            entry.setAttempts(entry.getAttempts() + 1);
            entry.setLastError(null);
        } catch (RuntimeException ex) {
            int attempts = entry.getAttempts() + 1;
            entry.setAttempts(attempts);
            entry.setLastError(truncate(ex.getMessage()));

            if (attempts >= MAX_ATTEMPTS) {
                entry.setStatus(NotificationOutbox.Status.FAILED);
                log.error("Giving up on notification {} after {} attempts", entryId, attempts);
            } else {
                // Exponential backoff: 1, 2, 4, 8 minutes. A provider having a bad minute
                // should not be hammered every ten seconds.
                entry.setNextAttemptAt(
                        Instant.now().plus(Duration.ofMinutes(1L << (attempts - 1))));
                log.warn("Notification {} failed (attempt {}), will retry", entryId, attempts);
            }
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
