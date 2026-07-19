package com.roneysahil.movie_booking.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.roneysahil.movie_booking.common.exception.ApiExceptions.BusinessRuleException;
import com.roneysahil.movie_booking.support.TestDatabaseConfig;
import com.roneysahil.movie_booking.user.UserDtos.RegisterRequest;
import com.roneysahil.movie_booking.user.repository.UserRepository;
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
 * Registration is check-then-act: {@code existsByEmail} followed by an insert. At READ
 * COMMITTED two concurrent callers can both pass the check, so uniqueness rests on the
 * unique constraint rather than the application check.
 *
 * <p>These tests pin both halves of that: exactly one account is created, and the loser
 * gets the same 422-mapped exception as a sequential duplicate rather than a raw
 * constraint violation surfacing as a 500.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class ConcurrentRegistrationTest {

    @Autowired private UserService users;
    @Autowired private UserRepository repository;

    @Test
    @DisplayName("Concurrent registrations for one email create exactly one account")
    void concurrentRegistrationCreatesOneAccount() throws Exception {
        String email = "race@movies.test";
        int threads = 12;

        var startGate = new CountDownLatch(1);
        var created = new AtomicInteger();
        var rejected = new AtomicInteger();
        var unexpected = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Void>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit((Callable<Void>) () -> {
                        startGate.await();
                        try {
                            users.register(new RegisterRequest(email, "supersecret", "Racer"));
                            created.incrementAndGet();
                        } catch (BusinessRuleException expected) {
                            // The mapped outcome: 422, same as a sequential duplicate.
                            rejected.incrementAndGet();
                        } catch (RuntimeException other) {
                            // A raw DataIntegrityViolationException landing here would mean
                            // the caller sees a 500 purely because of timing.
                            unexpected.incrementAndGet();
                        }
                        return null;
                    }))
                    .toList();

            startGate.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(created.get()).as("exactly one registration may succeed").isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(unexpected.get())
                .as("losers must get the mapped 422, never a raw constraint violation")
                .isZero();

        assertThat(repository.findByEmail(email)).isPresent();
    }

    @Test
    @DisplayName("Email is normalised, so casing cannot create a second account")
    void emailCasingDoesNotCreateDuplicates() {
        users.register(new RegisterRequest("Mixed.Case@Movies.Test", "supersecret", "First"));

        assertThat(repository.findByEmail("mixed.case@movies.test")).isPresent();

        // Same address, different casing: must be rejected, not stored as a second row.
        try {
            users.register(new RegisterRequest("MIXED.CASE@MOVIES.TEST", "supersecret", "Second"));
            throw new AssertionError("expected the duplicate to be rejected");
        } catch (BusinessRuleException expected) {
            assertThat(expected).hasMessageContaining("already exists");
        }
    }
}
