package com.roneysahil.movie_booking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roneysahil.movie_booking.booking.BookingDtos.CreateBookingRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.CreateHoldRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.HoldDto;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.SeatUnavailableException;
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

/**
 * Regression: a seat inside a created-but-unpaid booking must not read as available.
 *
 * <p>Booking creation converts the hold (status CONVERTED) but leaves the seat HELD until
 * payment. An earlier {@code isBookable()} treated any non-ACTIVE hold as lapsed, so a
 * CONVERTED hold made the seat look free — a second customer could hold it and then hit
 * the partial unique index as a raw 500. The seat is taken until the booking is paid or
 * swept; a competitor must get a clean rejection instead.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class PendingBookingSeatVisibilityTest {

    private static final String CUSTOMER = "customer@movies.test";
    private static final String OTHER = "priya@movies.test";

    @Autowired private BookingService bookings;
    @Autowired private ShowRepository shows;
    @Autowired private ShowSeatRepository showSeats;

    @Test
    @DisplayName("A seat in an unpaid booking is not offered to another customer")
    void pendingBookingSeatIsNotAvailable() {
        Long showId = futureShowId();
        Long seatId = firstAvailableSeat(showId);

        // Customer books but does NOT pay: hold becomes CONVERTED, seat stays HELD.
        HoldDto hold = bookings.createHold(CUSTOMER, new CreateHoldRequest(showId, List.of(seatId)));
        bookings.createBooking(CUSTOMER, new CreateBookingRequest(hold.holdId(), null));

        // The seat must read as taken, not available. Loaded via the seat-map query,
        // which fetches the hold — the same path the API uses.
        ShowSeat seat = showSeats.findSeatMap(showId).stream()
                .filter(s -> s.getId().equals(seatId))
                .findFirst().orElseThrow();
        assertThat(seat.isBookable()).as("a seat in an unpaid booking is not bookable").isFalse();

        // And a second customer racing it gets a clean 409, never a 500 from the constraint.
        assertThatThrownBy(() ->
                bookings.createHold(OTHER, new CreateHoldRequest(showId, List.of(seatId))))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    @DisplayName("A seat map does not show a pending-booking seat as available")
    void seatMapHidesPendingBookingSeat() {
        Long showId = futureShowId();
        Long seatId = firstAvailableSeat(showId);

        HoldDto hold = bookings.createHold(CUSTOMER, new CreateHoldRequest(showId, List.of(seatId)));
        bookings.createBooking(CUSTOMER, new CreateBookingRequest(hold.holdId(), null));

        boolean shownAvailable = bookings != null
                && showSeats.findSeatMap(showId).stream()
                        .filter(s -> s.getId().equals(seatId))
                        .findFirst().orElseThrow()
                        .isBookable();

        assertThat(shownAvailable).isFalse();
    }

    private Long futureShowId() {
        return shows.findAll().stream()
                .filter(s -> s.getStatus() == Show.Status.SCHEDULED)
                .filter(s -> s.getStartsAt().isAfter(Instant.now().plusSeconds(72 * 3600)))
                .min(Comparator.comparing(Show::getStartsAt))
                .orElseThrow()
                .getId();
    }

    private Long firstAvailableSeat(Long showId) {
        return showSeats.findSeatMap(showId).stream()
                .filter(s -> s.getStatus() == ShowSeat.Status.AVAILABLE)
                .map(ShowSeat::getId)
                .findFirst()
                .orElseThrow();
    }
}
