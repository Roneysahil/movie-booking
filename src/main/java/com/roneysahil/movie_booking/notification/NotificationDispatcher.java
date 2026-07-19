package com.roneysahil.movie_booking.notification;

import com.roneysahil.movie_booking.notification.domain.NotificationOutbox;
import com.roneysahil.movie_booking.notification.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the notification outbox.
 *
 * <p>This is the half of "delivered without blocking the booking flow" that happens after
 * the booking commits. The booking transaction only writes intent; nothing here can slow
 * down or roll back a sale.
 *
 * <p>Each row is delivered in its own transaction by {@link NotificationDelivery}, so one
 * undeliverable notification cannot stop the rest of the batch.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    /** Bounded so one pass cannot monopolise the scheduler thread. */
    private static final int BATCH_SIZE = 50;

    private final NotificationOutboxRepository outbox;
    private final NotificationDelivery delivery;

    public NotificationDispatcher(
            NotificationOutboxRepository outbox, NotificationDelivery delivery) {
        this.outbox = outbox;
        this.delivery = delivery;
    }

    @Scheduled(fixedDelayString = "PT10S")
    public void dispatchPending() {
        List<NotificationOutbox> due;
        try {
            due = outbox.findDue(Instant.now(), Limit.of(BATCH_SIZE));
        } catch (RuntimeException ex) {
            log.error("Could not read the notification outbox", ex);
            return;
        }

        for (NotificationOutbox entry : due) {
            try {
                delivery.deliver(entry.getId());
            } catch (RuntimeException ex) {
                log.error("Dispatch failed for outbox row {}", entry.getId(), ex);
            }
        }
    }
}
