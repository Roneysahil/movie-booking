package com.roneysahil.movie_booking.booking;

import com.roneysahil.movie_booking.booking.BookingDtos.BookingDto;
import com.roneysahil.movie_booking.booking.BookingDtos.BookingSeatDto;
import com.roneysahil.movie_booking.booking.BookingDtos.CreateBookingRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.CreateHoldRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.HoldDto;
import com.roneysahil.movie_booking.booking.BookingDtos.PaymentRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.RefundDto;
import com.roneysahil.movie_booking.booking.domain.Booking;
import com.roneysahil.movie_booking.booking.domain.BookingSeat;
import com.roneysahil.movie_booking.booking.domain.Payment;
import com.roneysahil.movie_booking.booking.domain.SeatHold;
import com.roneysahil.movie_booking.booking.repository.BookingRepository;
import com.roneysahil.movie_booking.booking.repository.BookingSeatRepository;
import com.roneysahil.movie_booking.booking.repository.PaymentRepository;
import com.roneysahil.movie_booking.booking.repository.SeatHoldRepository;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.BusinessRuleException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.NotFoundException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.SeatUnavailableException;
import com.roneysahil.movie_booking.refund.repository.RefundPolicyRepository;
import com.roneysahil.movie_booking.refund.repository.RefundRepository;
import com.roneysahil.movie_booking.show.repository.ShowRepository;
import com.roneysahil.movie_booking.user.repository.UserRepository;
import com.roneysahil.movie_booking.discount.DiscountApplication;
import com.roneysahil.movie_booking.discount.DiscountService;
import com.roneysahil.movie_booking.notification.NotificationPublisher;
import com.roneysahil.movie_booking.notification.domain.NotificationOutbox;
import com.roneysahil.movie_booking.refund.RefundPolicySnapshot;
import com.roneysahil.movie_booking.refund.domain.Refund;
import com.roneysahil.movie_booking.refund.domain.RefundPolicy;
import com.roneysahil.movie_booking.show.domain.Show;
import com.roneysahil.movie_booking.show.domain.ShowSeat;
import com.roneysahil.movie_booking.show.repository.ShowSeatRepository;
import com.roneysahil.movie_booking.user.domain.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The booking funnel, backed by the database.
 *
 * <p>Seat allocation is decided in exactly one place — {@link #createHold} — by locking
 * show_seat rows in id order and re-checking their status while the lock is held. No
 * external call happens inside that transaction; a database lock is never held across a
 * payment gateway round trip.
 *
 * <p>See docs/DESIGN.md for the reasoning behind pessimistic over optimistic locking.
 */
@Service
public class JpaBookingService implements BookingService {

    private static final Duration HOLD_TTL = Duration.ofMinutes(5);

    private final UserRepository users;
    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final SeatHoldRepository holds;
    private final BookingRepository bookings;
    private final BookingSeatRepository bookingSeats;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final RefundPolicyRepository refundPolicies;
    private final DiscountService discounts;
    private final NotificationPublisher notifications;

    public JpaBookingService(
            UserRepository users,
            ShowRepository shows,
            ShowSeatRepository showSeats,
            SeatHoldRepository holds,
            BookingRepository bookings,
            BookingSeatRepository bookingSeats,
            PaymentRepository payments,
            RefundRepository refunds,
            RefundPolicyRepository refundPolicies,
            DiscountService discounts,
            NotificationPublisher notifications) {
        this.users = users;
        this.shows = shows;
        this.showSeats = showSeats;
        this.holds = holds;
        this.bookings = bookings;
        this.bookingSeats = bookingSeats;
        this.payments = payments;
        this.refunds = refunds;
        this.refundPolicies = refundPolicies;
        this.discounts = discounts;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ holds --

    /**
     * Claims seats for a short window. This is the contention point of the system.
     *
     * <p>Order of operations matters: lock first, validate second. Validating before the
     * lock is held would let two callers both observe AVAILABLE and both proceed.
     */
    @Override
    @Transactional
    public HoldDto createHold(String username, CreateHoldRequest request) {
        User user = requireUser(username);
        Show show = shows.findDetailById(request.showId())
                .orElseThrow(() -> new NotFoundException("Show " + request.showId() + " not found"));

        if (show.getStatus() != Show.Status.SCHEDULED) {
            throw new BusinessRuleException("This show is not open for booking");
        }
        if (show.getStartsAt().isBefore(Instant.now())) {
            throw new BusinessRuleException("This show has already started");
        }

        List<Long> requestedIds = request.showSeatIds().stream().distinct().sorted().toList();

        // Blocks here until any competing transaction commits or rolls back.
        List<ShowSeat> locked = showSeats.lockSeatsForUpdate(show.getId(), requestedIds);

        if (locked.size() != requestedIds.size()) {
            throw new NotFoundException("One or more seats do not belong to show " + show.getId());
        }

        // Re-checked under the lock. This is the check that actually decides the winner.
        List<Long> unavailable = locked.stream()
                .filter(seat -> !seat.isBookable())
                .map(ShowSeat::getId)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new SeatUnavailableException(unavailable);
        }

        SeatHold hold = new SeatHold();
        hold.setShow(show);
        hold.setUser(user);
        hold.setExpiresAt(Instant.now().plus(HOLD_TTL));
        hold.setStatus(SeatHold.Status.ACTIVE);
        holds.save(hold);

        for (ShowSeat seat : locked) {
            seat.setStatus(ShowSeat.Status.HELD);
            seat.setHold(hold);
        }

        return toHoldDto(hold, requestedIds);
        // Commit releases the locks.
    }

    @Override
    @Transactional(readOnly = true)
    public HoldDto getHold(String username, String holdId) {
        SeatHold hold = requireHold(username, holdId);
        return toHoldDto(hold, seatIdsOf(hold));
    }

    @Override
    @Transactional
    public void releaseHold(String username, String holdId) {
        SeatHold hold = requireHold(username, holdId);
        if (hold.getStatus() != SeatHold.Status.ACTIVE) {
            return;
        }
        releaseSeatsOf(hold, SeatHold.Status.RELEASED);
    }

    // --------------------------------------------------------------- bookings --

    @Override
    @Transactional
    public BookingDto createBooking(String username, CreateBookingRequest request) {
        User user = requireUser(username);
        SeatHold hold = requireHold(username, request.holdId());

        if (hold.isExpired()) {
            throw new BusinessRuleException("Hold has expired; please reselect your seats");
        }

        // Re-locked: the seats are already ours, but locking keeps the sweeper from
        // reclaiming them mid-transaction.
        List<ShowSeat> seats = showSeats.lockSeatsForUpdate(
                hold.getShow().getId(), seatIdsOf(hold));
        boolean stillHeld = seats.stream()
                .allMatch(s -> s.getStatus() == ShowSeat.Status.HELD
                        && s.getHold() != null
                        && s.getHold().getId().equals(hold.getId()));
        if (!stillHeld) {
            throw new BusinessRuleException("Hold is no longer valid; please reselect your seats");
        }

        BigDecimal subtotal = seats.stream()
                .map(ShowSeat::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // Authoritative discount check; consumes one use of the code atomically.
        // Loaded with its screen/theater/city graph: the policy lookup and the response
        // both need it, and a lazy proxy here would fail outside the session.
        Show show = shows.findDetailById(hold.getShow().getId())
                .orElseThrow(() -> new NotFoundException("Show not found"));
        RefundPolicy policy = resolvePolicy(show);

        DiscountApplication discount = discounts.apply(user, request.discountCode(), subtotal);

        Booking booking = new Booking();
        booking.setBookingRef(generateRef());
        booking.setUser(user);
        booking.setShow(show);
        booking.setDiscountCode(discount.code());
        booking.setStatus(Booking.Status.PENDING_PAYMENT);
        booking.setSubtotal(subtotal);
        booking.setDiscountAmount(discount.amount());
        booking.setTotalAmount(subtotal.subtract(discount.amount()).setScale(2, RoundingMode.HALF_UP));
        booking.setRefundPolicySnapshot(RefundPolicySnapshot.serialize(policy));

        for (ShowSeat seat : seats) {
            BookingSeat bs = new BookingSeat();
            bs.setShowSeat(seat);
            bs.setPrice(seat.getPrice());
            bs.setActive(true);
            booking.addSeat(bs);
        }
        bookings.save(booking);

        discounts.recordRedemption(discount, user, booking);

        hold.setStatus(SeatHold.Status.CONVERTED);
        return toBookingDto(booking, seats);
    }

    @Override
    @Transactional
    public BookingDto pay(String username, String bookingRef, PaymentRequest request) {
        // Idempotent replay: the same key returns the original outcome, never re-charges.
        Optional<Payment> existing = payments.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            Booking prior = existing.get().getBooking();
            requireOwnership(username, prior);
            return toBookingDto(prior, activeSeatsOf(prior));
        }

        Booking booking = requireBooking(username, bookingRef);
        if (booking.getStatus() != Booking.Status.PENDING_PAYMENT) {
            throw new BusinessRuleException("Booking is not awaiting payment");
        }

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setAmount(booking.getTotalAmount());
        payment.setMethod(request.method());
        payment.setIdempotencyKey(request.idempotencyKey());
        payment.setStatus(Payment.Status.SUCCEEDED);
        payment.setProviderRef("SIM-" + booking.getBookingRef());
        payments.save(payment);

        List<ShowSeat> seats = activeSeatsOf(booking);
        for (ShowSeat seat : seats) {
            seat.setStatus(ShowSeat.Status.BOOKED);
            seat.setHold(null);
        }
        booking.setStatus(Booking.Status.CONFIRMED);

        // Enqueued in this transaction. A provider outage must never roll back a paid
        // booking, so delivery happens later and out of band.
        notifications.enqueue(booking, NotificationOutbox.Type.BOOKING_CONFIRMATION);

        return toBookingDto(booking, seats);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingDto> listBookings(String username, String status) {
        return bookings.findHistory(username).stream()
                .filter(b -> status == null || b.getStatus().name().equalsIgnoreCase(status))
                .map(b -> toBookingDto(b, activeSeatsOf(b)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BookingDto getBooking(String username, String bookingRef) {
        Booking booking = requireBooking(username, bookingRef);
        return toBookingDto(booking, activeSeatsOf(booking));
    }

    // ----------------------------------------------------------- cancellation --

    @Override
    @Transactional
    public RefundDto cancel(String username, String bookingRef) {
        Booking booking = requireBooking(username, bookingRef);
        if (!booking.isCancellable()) {
            throw new BusinessRuleException("Only confirmed bookings can be cancelled");
        }

        long hoursBefore = Duration.between(Instant.now(), booking.getShow().getStartsAt()).toHours();

        // Resolved from the snapshot taken at confirmation, not the live policy: an admin
        // editing terms today cannot change what this customer already agreed to.
        RefundPolicySnapshot snapshot = RefundPolicySnapshot.parse(booking.getRefundPolicySnapshot());
        int percent = snapshot.refundPercentFor(hoursBefore);

        BigDecimal refundAmount = booking.getTotalAmount()
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // Release the seats: deactivating the join rows frees the partial unique index.
        for (BookingSeat bs : bookingSeats.findByBookingId(booking.getId())) {
            bs.setActive(false);
            ShowSeat seat = bs.getShowSeat();
            seat.setStatus(ShowSeat.Status.AVAILABLE);
            seat.setHold(null);
        }

        Refund refund = new Refund();
        refund.setBooking(booking);
        refund.setAmount(refundAmount);
        refund.setRefundPercent(percent);
        refund.setPolicyName(snapshot.policyName());
        refund.setStatus(Refund.Status.PROCESSED);
        refunds.save(refund);

        booking.setStatus(Booking.Status.CANCELLED);
        notifications.enqueue(booking, NotificationOutbox.Type.BOOKING_CANCELLED);

        return toRefundDto(booking, refund);
    }

    @Override
    @Transactional(readOnly = true)
    public RefundDto getRefund(String username, String bookingRef) {
        Booking booking = requireBooking(username, bookingRef);
        Refund refund = refunds.findByBookingId(booking.getId())
                .orElseThrow(() ->
                        new NotFoundException("No refund exists for booking " + bookingRef));
        return toRefundDto(booking, refund);
    }

    // -------------------------------------------------------------- sweeping --

    /**
     * Reclaims lapsed holds. Called by the scheduler; runs in its own transaction so one
     * bad row cannot poison an unrelated caller.
     *
     * <p>Hygiene only. Correctness comes from {@link ShowSeat#isBookable()} evaluating
     * expiry on read, so a stalled scheduler cannot make seats permanently unbookable.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int sweepExpiredHolds() {
        List<SeatHold> lapsed = holds.findLapsed(Instant.now());
        for (SeatHold hold : lapsed) {
            releaseSeatsOf(hold, SeatHold.Status.EXPIRED);
        }
        return lapsed.size();
    }

    /** Reclaims checkouts abandoned before payment. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int sweepAbandonedBookings(Duration gracePeriod) {
        List<Booking> abandoned = bookings.findAbandoned(Instant.now().minus(gracePeriod));
        for (Booking booking : abandoned) {
            for (BookingSeat bs : bookingSeats.findByBookingId(booking.getId())) {
                bs.setActive(false);
                ShowSeat seat = bs.getShowSeat();
                seat.setStatus(ShowSeat.Status.AVAILABLE);
                seat.setHold(null);
            }
            booking.setStatus(Booking.Status.EXPIRED);
        }
        return abandoned.size();
    }

    // --------------------------------------------------------------- helpers --

    private void releaseSeatsOf(SeatHold hold, SeatHold.Status terminalStatus) {
        List<ShowSeat> seats = showSeats.lockSeatsForUpdate(
                hold.getShow().getId(), seatIdsOf(hold));
        for (ShowSeat seat : seats) {
            if (seat.getStatus() == ShowSeat.Status.HELD) {
                seat.setStatus(ShowSeat.Status.AVAILABLE);
                seat.setHold(null);
            }
        }
        hold.setStatus(terminalStatus);
    }

    private List<Long> seatIdsOf(SeatHold hold) {
        return showSeats.findSeatMap(hold.getShow().getId()).stream()
                .filter(s -> s.getHold() != null && s.getHold().getId().equals(hold.getId()))
                .map(ShowSeat::getId)
                .sorted()
                .toList();
    }

    private List<ShowSeat> activeSeatsOf(Booking booking) {
        return bookingSeats.findByBookingId(booking.getId()).stream()
                .filter(BookingSeat::isActive)
                .map(BookingSeat::getShowSeat)
                .sorted(Comparator.comparing(ShowSeat::getId))
                .toList();
    }

    private RefundPolicy resolvePolicy(Show show) {
        RefundPolicy assigned = show.getScreen().getTheater().getRefundPolicy();
        if (assigned != null) {
            return refundPolicies.findWithTiers(assigned.getId()).orElse(assigned);
        }
        return refundPolicies.findDefaultWithTiers()
                .orElseThrow(() -> new NotFoundException("No default refund policy configured"));
    }

    private User requireUser(String email) {
        return users.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    /** Ownership check returns 404 rather than 403 so ids are not enumerable. */
    private SeatHold requireHold(String username, String holdId) {
        Long id;
        try {
            id = Long.valueOf(holdId);
        } catch (NumberFormatException ex) {
            throw new NotFoundException("Hold " + holdId + " not found");
        }
        SeatHold hold = holds.findById(id)
                .filter(h -> h.getUser().getEmail().equals(username))
                .orElseThrow(() -> new NotFoundException("Hold " + holdId + " not found"));
        return hold;
    }

    private Booking requireBooking(String username, String bookingRef) {
        return bookings.findByBookingRef(bookingRef)
                .filter(b -> b.getUser().getEmail().equals(username))
                .orElseThrow(() -> new NotFoundException("Booking " + bookingRef + " not found"));
    }

    private void requireOwnership(String username, Booking booking) {
        if (!booking.getUser().getEmail().equals(username)) {
            throw new NotFoundException("Booking " + booking.getBookingRef() + " not found");
        }
    }

    private String generateRef() {
        return "BK" + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
    }

    // ------------------------------------------------------------- mapping --

    private HoldDto toHoldDto(SeatHold hold, List<Long> seatIds) {
        return new HoldDto(
                String.valueOf(hold.getId()),
                hold.getShow().getId(),
                seatIds,
                hold.getExpiresAt(),
                hold.secondsRemaining(),
                hold.isExpired() ? "EXPIRED" : hold.getStatus().name());
    }

    private BookingDto toBookingDto(Booking booking, List<ShowSeat> seats) {
        List<BookingSeatDto> seatDtos = new ArrayList<>();
        for (ShowSeat seat : seats) {
            seatDtos.add(new BookingSeatDto(
                    seat.getId(),
                    seat.getSeat().label(),
                    seat.getSeat().getTier(),
                    seat.getPrice()));
        }
        Show show = booking.getShow();
        return new BookingDto(
                booking.getBookingRef(),
                show.getId(),
                show.getMovie().getTitle(),
                show.getScreen().getTheater().getName(),
                show.getStartsAt(),
                booking.getStatus().name(),
                seatDtos,
                booking.getSubtotal(),
                booking.getDiscountAmount(),
                booking.getTotalAmount(),
                booking.getDiscountCode() == null ? null : booking.getDiscountCode().getCode(),
                booking.getCreatedAt());
    }

    private RefundDto toRefundDto(Booking booking, Refund refund) {
        return new RefundDto(
                booking.getBookingRef(),
                booking.getTotalAmount(),
                refund.getAmount(),
                refund.getRefundPercent(),
                refund.getPolicyName(),
                refund.getStatus().name());
    }
}
