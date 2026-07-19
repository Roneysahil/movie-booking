package com.roneysahil.movie_booking.booking;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims lapsed holds and abandoned checkouts.
 *
 * <p>Hygiene, not correctness. Availability is decided by {@code ShowSeat.isBookable()},
 * which evaluates hold expiry on read — so a stalled or crashed scheduler cannot make
 * seats permanently unbookable. If this were the source of truth, a paused sweeper would
 * take inventory offline.
 *
 * <p>Single-instance assumption: running two copies would duplicate the work harmlessly
 * but wastefully. Multi-instance deployment would want ShedLock or a Postgres advisory
 * lock; out of scope here.
 */
@Component
public class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);

    /** How long an unpaid booking may sit before its seats are reclaimed. */
    private static final Duration CHECKOUT_GRACE = Duration.ofMinutes(15);

    private final JpaBookingService bookings;

    public HoldSweeper(JpaBookingService bookings) {
        this.bookings = bookings;
    }

    @Scheduled(fixedDelayString = "PT30S")
    public void sweep() {
        try {
            int holds = bookings.sweepExpiredHolds();
            int abandoned = bookings.sweepAbandonedBookings(CHECKOUT_GRACE);
            if (holds > 0 || abandoned > 0) {
                log.info("Swept {} expired holds and {} abandoned bookings", holds, abandoned);
            }
        } catch (RuntimeException ex) {
            // A failed sweep must not kill the scheduler thread; the next tick retries.
            log.error("Sweep failed", ex);
        }
    }
}
