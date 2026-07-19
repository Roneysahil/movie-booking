package com.roneysahil.movie_booking.notification;

import com.roneysahil.movie_booking.notification.domain.NotificationOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Transport boundary for notifications.
 *
 * <p>Delivery is a logging stub — integrating a real provider is credential wrangling,
 * not design. What matters is that this is the only place that knows how a message
 * leaves the system: swapping in SES, Twilio or a queue replaces this class alone, and
 * the outbox, retry and backoff behaviour around it is unchanged.
 *
 * <p>Throwing from {@link #send} is the contract for a failed delivery; the dispatcher
 * turns that into a retry with backoff.
 */
@Component
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    public void send(NotificationOutbox entry) {
        log.info(
                "NOTIFY [{}] to {} :: {}",
                entry.getType(),
                entry.getRecipient(),
                entry.getPayload());
    }
}
