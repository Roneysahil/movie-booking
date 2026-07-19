package com.roneysahil.movie_booking.notification;

import com.roneysahil.movie_booking.booking.domain.Booking;
import com.roneysahil.movie_booking.booking.repository.BookingRepository;
import com.roneysahil.movie_booking.notification.domain.NotificationOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues a reminder for every confirmed booking whose show is approaching.
 *
 * <p>Only enqueues — actual delivery is {@link NotificationDispatcher}'s job, so a slow
 * or failing provider cannot make this job fall behind.
 *
 * <p>Idempotent by construction: the query excludes bookings already reminded, and the
 * unique constraint on (booking_id, type) is a second line of defence if two instances
 * ever run this concurrently.
 */
@Component
public class ShowReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ShowReminderScheduler.class);

    /** Remind customers a day ahead — long enough to act on, close enough to be useful. */
    private static final Duration REMINDER_WINDOW = Duration.ofHours(24);

    private final BookingRepository bookings;
    private final NotificationPublisher notifications;

    public ShowReminderScheduler(
            BookingRepository bookings, NotificationPublisher notifications) {
        this.bookings = bookings;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void queueUpcomingShowReminders() {
        try {
            Instant now = Instant.now();
            List<Booking> due = bookings.findNeedingReminder(now, now.plus(REMINDER_WINDOW));

            for (Booking booking : due) {
                notifications.enqueue(booking, NotificationOutbox.Type.SHOW_REMINDER);
            }
            if (!due.isEmpty()) {
                log.info("Queued {} show reminder(s)", due.size());
            }
        } catch (RuntimeException ex) {
            // The next tick retries; a failure here must not kill the scheduler thread.
            log.error("Reminder scheduling failed", ex);
        }
    }
}
