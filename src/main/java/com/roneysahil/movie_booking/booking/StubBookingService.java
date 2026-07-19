package com.roneysahil.movie_booking.booking;

import com.roneysahil.movie_booking.booking.BookingDtos.BookingDto;
import com.roneysahil.movie_booking.booking.BookingDtos.BookingSeatDto;
import com.roneysahil.movie_booking.booking.BookingDtos.CreateBookingRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.CreateHoldRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.HoldDto;
import com.roneysahil.movie_booking.booking.BookingDtos.PaymentRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.RefundDto;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.BusinessRuleException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.NotFoundException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.SeatUnavailableException;
import com.roneysahil.movie_booking.discount.DiscountService;
import com.roneysahil.movie_booking.refund.RefundPolicyService;
import com.roneysahil.movie_booking.show.ShowDtos.SeatDto;
import com.roneysahil.movie_booking.show.ShowDtos.ShowDetailDto;
import com.roneysahil.movie_booking.show.ShowService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * In-memory placeholder for the booking funnel. Models the state transitions and the
 * error cases; the concurrency guarantees are NOT real here — a ConcurrentHashMap is not
 * a substitute for row locks. The real implementation locks show_seat rows in id order
 * inside a transaction. See docs/DESIGN.md.
 */
@Service
public class StubBookingService implements BookingService {

    private static final Duration HOLD_TTL = Duration.ofMinutes(5);

    private record Hold(
            String holdId, String owner, Long showId, List<Long> seatIds, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private record Booking(
            String ref,
            String owner,
            Long showId,
            List<Long> seatIds,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal total,
            String discountCode,
            String status,
            Instant createdAt) {
        Booking withStatus(String newStatus) {
            return new Booking(
                    ref, owner, showId, seatIds, subtotal, discount, total, discountCode,
                    newStatus, createdAt);
        }
    }

    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final Map<String, String> paidKeys = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    private final ShowService shows;
    private final DiscountService discounts;
    private final RefundPolicyService refundPolicies;

    public StubBookingService(
            ShowService shows, DiscountService discounts, RefundPolicyService refundPolicies) {
        this.shows = shows;
        this.discounts = discounts;
        this.refundPolicies = refundPolicies;
    }

    // --- holds -------------------------------------------------------------

    @Override
    public HoldDto createHold(String username, CreateHoldRequest request) {
        Map<Long, SeatDto> seatMap = seatsById(request.showId());

        List<Long> unavailable = new ArrayList<>();
        for (Long seatId : request.showSeatIds()) {
            SeatDto seat = seatMap.get(seatId);
            if (seat == null) {
                throw new NotFoundException("Seat " + seatId + " not found on show " + request.showId());
            }
            if (!"AVAILABLE".equals(seat.status()) || heldByAnother(request.showId(), seatId)) {
                unavailable.add(seatId);
            }
        }
        if (!unavailable.isEmpty()) {
            throw new SeatUnavailableException(unavailable);
        }

        Hold hold = new Hold(
                "HOLD-" + sequence.incrementAndGet(),
                username,
                request.showId(),
                List.copyOf(request.showSeatIds()),
                Instant.now().plus(HOLD_TTL));
        holds.put(hold.holdId(), hold);
        return toDto(hold, "ACTIVE");
    }

    @Override
    public HoldDto getHold(String username, String holdId) {
        Hold hold = requireHold(username, holdId);
        return toDto(hold, hold.expired() ? "EXPIRED" : "ACTIVE");
    }

    @Override
    public void releaseHold(String username, String holdId) {
        requireHold(username, holdId);
        holds.remove(holdId);
    }

    // --- bookings ----------------------------------------------------------

    @Override
    public BookingDto createBooking(String username, CreateBookingRequest request) {
        Hold hold = requireHold(username, request.holdId());
        if (hold.expired()) {
            holds.remove(hold.holdId());
            throw new BusinessRuleException("Hold has expired; please reselect your seats");
        }

        Map<Long, SeatDto> seatMap = seatsById(hold.showId());
        BigDecimal subtotal = hold.seatIds().stream()
                .map(seatMap::get)
                .filter(Objects::nonNull)
                .map(SeatDto::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal discount = discounts.computeDiscount(username, request.discountCode(), subtotal);

        Booking booking = new Booking(
                "BK" + sequence.incrementAndGet(),
                username,
                hold.showId(),
                hold.seatIds(),
                subtotal,
                discount,
                subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP),
                request.discountCode(),
                "PENDING_PAYMENT",
                Instant.now());
        bookings.put(booking.ref(), booking);
        holds.remove(hold.holdId());
        return toDto(booking);
    }

    @Override
    public BookingDto pay(String username, String bookingRef, PaymentRequest request) {
        Booking booking = requireBooking(username, bookingRef);

        // Idempotency: replaying the same key returns the existing result, never re-charges.
        String seenRef = paidKeys.putIfAbsent(request.idempotencyKey(), bookingRef);
        if (seenRef != null) {
            return toDto(bookings.get(seenRef));
        }
        if (!"PENDING_PAYMENT".equals(booking.status())) {
            throw new BusinessRuleException("Booking is not awaiting payment");
        }

        Booking confirmed = booking.withStatus("CONFIRMED");
        bookings.put(bookingRef, confirmed);
        // Real implementation enqueues a notification outbox row in this same transaction.
        return toDto(confirmed);
    }

    @Override
    public List<BookingDto> listBookings(String username, String status) {
        return bookings.values().stream()
                .filter(b -> b.owner().equals(username))
                .filter(b -> status == null || b.status().equalsIgnoreCase(status))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .map(this::toDto)
                .toList();
    }

    @Override
    public BookingDto getBooking(String username, String bookingRef) {
        return toDto(requireBooking(username, bookingRef));
    }

    // --- cancellation ------------------------------------------------------

    @Override
    public RefundDto cancel(String username, String bookingRef) {
        Booking booking = requireBooking(username, bookingRef);
        if (!"CONFIRMED".equals(booking.status())) {
            throw new BusinessRuleException("Only confirmed bookings can be cancelled");
        }

        ShowDetailDto show = shows.getShow(booking.showId());
        int percent = refundPercent(Duration.between(Instant.now(), show.startsAt()).toHours());
        BigDecimal refund = booking.total()
                .multiply(BigDecimal.valueOf(percent))
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        bookings.put(bookingRef, booking.withStatus("CANCELLED"));
        // Real implementation releases the seats back to AVAILABLE in this transaction.
        return new RefundDto(
                bookingRef, booking.total(), refund, percent, "STANDARD", "PROCESSED");
    }

    @Override
    public RefundDto getRefund(String username, String bookingRef) {
        Booking booking = requireBooking(username, bookingRef);
        if (!"CANCELLED".equals(booking.status())) {
            throw new NotFoundException("No refund exists for booking " + bookingRef);
        }
        ShowDetailDto show = shows.getShow(booking.showId());
        int percent = refundPercent(Duration.between(booking.createdAt(), show.startsAt()).toHours());
        BigDecimal refund = booking.total()
                .multiply(BigDecimal.valueOf(percent))
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        return new RefundDto(
                bookingRef, booking.total(), refund, percent, "STANDARD", "PROCESSED");
    }

    /**
     * Resolves against the configured policy. The real implementation uses the policy
     * snapshotted on the booking at confirmation time, so later admin edits cannot change
     * terms the customer already agreed to.
     */
    private int refundPercent(long hoursBeforeShow) {
        return refundPolicies.refundPercentFor(null, hoursBeforeShow);
    }

    // --- helpers -----------------------------------------------------------

    private boolean heldByAnother(Long showId, Long seatId) {
        return holds.values().stream()
                .anyMatch(h -> !h.expired() && h.showId().equals(showId) && h.seatIds().contains(seatId));
    }

    private Map<Long, SeatDto> seatsById(Long showId) {
        return shows.getSeatMap(showId).seats().stream()
                .collect(Collectors.toMap(SeatDto::showSeatId, s -> s));
    }

    /** Ownership check returns 404 rather than 403 so ids are not enumerable. */
    private Hold requireHold(String username, String holdId) {
        Hold hold = holds.get(holdId);
        if (hold == null || !hold.owner().equals(username)) {
            throw new NotFoundException("Hold " + holdId + " not found");
        }
        return hold;
    }

    private Booking requireBooking(String username, String bookingRef) {
        Booking booking = bookings.get(bookingRef);
        if (booking == null || !booking.owner().equals(username)) {
            throw new NotFoundException("Booking " + bookingRef + " not found");
        }
        return booking;
    }

    private HoldDto toDto(Hold hold, String status) {
        long remaining = Math.max(0, Duration.between(Instant.now(), hold.expiresAt()).toSeconds());
        return new HoldDto(
                hold.holdId(), hold.showId(), hold.seatIds(), hold.expiresAt(), remaining, status);
    }

    private BookingDto toDto(Booking b) {
        Map<Long, SeatDto> seatMap = seatsById(b.showId());
        List<BookingSeatDto> seats = b.seatIds().stream()
                .map(seatMap::get)
                .filter(Objects::nonNull)
                .map(s -> new BookingSeatDto(
                        s.showSeatId(), s.rowLabel() + s.seatNumber(), s.tier(), s.price()))
                .toList();
        ShowDetailDto show = shows.getShow(b.showId());
        return new BookingDto(
                b.ref(),
                b.showId(),
                show.movieTitle(),
                show.theaterName(),
                show.startsAt(),
                b.status(),
                seats,
                b.subtotal(),
                b.discount(),
                b.total(),
                b.discountCode(),
                b.createdAt());
    }
}
