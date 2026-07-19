package com.roneysahil.movie_booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.roneysahil.movie_booking.booking.BookingDtos.CreateBookingRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.CreateHoldRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.HoldDto;
import com.roneysahil.movie_booking.booking.BookingDtos.PaymentRequest;
import com.roneysahil.movie_booking.booking.domain.BookingSeat;
import com.roneysahil.movie_booking.booking.repository.BookingSeatRepository;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.SeatUnavailableException;
import com.roneysahil.movie_booking.show.domain.Show;
import com.roneysahil.movie_booking.show.domain.ShowSeat;
import com.roneysahil.movie_booking.show.repository.ShowRepository;
import com.roneysahil.movie_booking.show.repository.ShowSeatRepository;
import com.roneysahil.movie_booking.support.TestDatabaseConfig;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The load-bearing test of this system.
 *
 * <p>The requirement is that multiple users attempting the same seat are serialized
 * without double allocation. These tests exercise that directly with real threads and
 * real transactions — no mocking, no single-threaded simulation, because neither would
 * prove anything about locking.
 *
 * <p>Deliberately not annotated {@code @Transactional}: a test-managed transaction would
 * confine every thread to one connection and the race being tested could not occur.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class SeatConcurrencyIntegrationTest {

    private static final String CUSTOMER = "customer@movies.test";
    private static final String OTHER_CUSTOMER = "priya@movies.test";

    @Autowired private BookingService bookings;
    @Autowired private ShowRepository shows;
    @Autowired private ShowSeatRepository showSeats;
    @Autowired private BookingSeatRepository bookingSeats;

    @Test
    @DisplayName("20 threads racing for one seat: exactly one wins")
    void exactlyOneThreadWinsASingleSeat() throws Exception {
        Long showId = futureShowId();
        Long seatId = availableSeatIds(showId, 1).getFirst();

        int threads = 20;
        var startGate = new CountDownLatch(1);
        var winners = new AtomicInteger();
        var rejections = new AtomicInteger();
        var unexpected = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Void>> futures = submitAll(pool, threads, () -> {
                // Every thread blocks here, so they contend as simultaneously as the
                // platform allows rather than trickling in.
                startGate.await();
                try {
                    bookings.createHold(CUSTOMER, new CreateHoldRequest(showId, List.of(seatId)));
                    winners.incrementAndGet();
                } catch (SeatUnavailableException expected) {
                    rejections.incrementAndGet();
                } catch (RuntimeException other) {
                    unexpected.incrementAndGet();
                }
                return null;
            });

            startGate.countDown();
            awaitAll(futures);
        }

        assertThat(winners.get()).as("exactly one hold may succeed").isEqualTo(1);
        assertThat(rejections.get()).as("everyone else is cleanly rejected").isEqualTo(threads - 1);
        assertThat(unexpected.get()).as("no thread may fail for any other reason").isZero();

        ShowSeat seat = showSeats.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(ShowSeat.Status.HELD);
        assertThat(seat.getHold()).isNotNull();
    }

    @Test
    @DisplayName("Overlapping multi-seat selections cannot deadlock or double-allocate")
    void overlappingSelectionsSerializeWithoutDeadlock() throws Exception {
        Long showId = futureShowId();
        List<Long> seats = availableSeatIds(showId, 2);
        Long first = seats.get(0);
        Long second = seats.get(1);

        // Opposite orderings are the classic deadlock setup. The repository sorts by id
        // before locking, so both transactions acquire in the same order regardless.
        List<Long> ascending = List.of(first, second);
        List<Long> descending = List.of(second, first);

        int threads = 16;
        var startGate = new CountDownLatch(1);
        var winners = new AtomicInteger();
        var rejections = new AtomicInteger();
        var unexpected = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Void>> futures = submitAllIndexed(pool, threads, index -> {
                startGate.await();
                List<Long> selection = index % 2 == 0 ? ascending : descending;
                try {
                    bookings.createHold(CUSTOMER, new CreateHoldRequest(showId, selection));
                    winners.incrementAndGet();
                } catch (SeatUnavailableException expected) {
                    rejections.incrementAndGet();
                } catch (RuntimeException other) {
                    unexpected.incrementAndGet();
                }
                return null;
            });

            startGate.countDown();
            awaitAll(futures);
        }

        assertThat(winners.get()).isEqualTo(1);
        assertThat(rejections.get()).isEqualTo(threads - 1);
        assertThat(unexpected.get()).as("a deadlock would surface here").isZero();
    }

    @Test
    @DisplayName("Concurrent full bookings never double-allocate a seat")
    void concurrentBookingsProduceOneActiveAllocation() throws Exception {
        Long showId = futureShowId();
        Long seatId = availableSeatIds(showId, 1).getFirst();

        int threads = 10;
        var startGate = new CountDownLatch(1);
        var confirmed = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Void>> futures = submitAllIndexed(pool, threads, index -> {
                startGate.await();
                try {
                    // Whole funnel per thread: hold, book, pay.
                    HoldDto hold = bookings.createHold(
                            CUSTOMER, new CreateHoldRequest(showId, List.of(seatId)));
                    var booking = bookings.createBooking(
                            CUSTOMER, new CreateBookingRequest(hold.holdId(), null));
                    bookings.pay(
                            CUSTOMER,
                            booking.bookingRef(),
                            new PaymentRequest("CARD", "idem-" + index));
                    confirmed.incrementAndGet();
                } catch (RuntimeException expected) {
                    // Losing the race is the normal outcome for all but one thread.
                }
                return null;
            });

            startGate.countDown();
            awaitAll(futures);
        }

        assertThat(confirmed.get()).as("only one booking may confirm").isEqualTo(1);

        // The database-level guarantee: the partial unique index permits at most one
        // active booking_seat per show_seat, independent of application logic.
        List<BookingSeat> active = bookingSeats.findAll().stream()
                .filter(BookingSeat::isActive)
                .filter(bs -> bs.getShowSeat().getId().equals(seatId))
                .toList();
        assertThat(active).hasSize(1);
    }

    @Test
    @DisplayName("Two customers racing the same seat: one is rejected, not silently queued")
    void differentCustomersCannotBothHoldTheSameSeat() throws Exception {
        Long showId = futureShowId();
        Long seatId = availableSeatIds(showId, 1).getFirst();

        var startGate = new CountDownLatch(1);
        var outcomes = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Void>> futures = List.of(
                    pool.submit(holdAttempt(startGate, CUSTOMER, showId, seatId, outcomes)),
                    pool.submit(holdAttempt(startGate, OTHER_CUSTOMER, showId, seatId, outcomes)));
            startGate.countDown();
            awaitAll(futures);
        }

        assertThat(outcomes.get()).as("exactly one of the two customers succeeds").isEqualTo(1);
    }

    // --- helpers -----------------------------------------------------------

    private Callable<Void> holdAttempt(
            CountDownLatch gate, String user, Long showId, Long seatId, AtomicInteger successes) {
        return () -> {
            gate.await();
            try {
                bookings.createHold(user, new CreateHoldRequest(showId, List.of(seatId)));
                successes.incrementAndGet();
            } catch (SeatUnavailableException expected) {
                // Expected for the loser.
            }
            return null;
        };
    }

    /** A seeded show far enough out that hold and booking rules all pass. */
    private Long futureShowId() {
        return shows.findAll().stream()
                .filter(s -> s.getStatus() == Show.Status.SCHEDULED)
                .filter(s -> s.getStartsAt().isAfter(Instant.now().plusSeconds(72 * 3600)))
                .min(Comparator.comparing(Show::getStartsAt))
                .orElseThrow(() -> new IllegalStateException("seed contains no future show"))
                .getId();
    }

    /** Fresh seats, so tests do not interfere with each other's allocations. */
    private List<Long> availableSeatIds(Long showId, int count) {
        List<Long> ids = showSeats.findSeatMap(showId).stream()
                .filter(s -> s.getStatus() == ShowSeat.Status.AVAILABLE)
                .map(ShowSeat::getId)
                .limit(count)
                .toList();
        if (ids.size() < count) {
            throw new IllegalStateException("not enough available seats on show " + showId);
        }
        return ids;
    }

    private List<Future<Void>> submitAll(ExecutorService pool, int count, Callable<Void> task) {
        return submitAllIndexed(pool, count, index -> task.call());
    }

    private List<Future<Void>> submitAllIndexed(
            ExecutorService pool, int count, IndexedTask task) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> pool.submit((Callable<Void>) () -> task.run(i)))
                .toList();
    }

    private void awaitAll(List<Future<Void>> futures) throws Exception {
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface IndexedTask {
        Void run(int index) throws Exception;
    }
}
