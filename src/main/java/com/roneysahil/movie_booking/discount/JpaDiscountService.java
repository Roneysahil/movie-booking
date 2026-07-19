package com.roneysahil.movie_booking.discount;

import com.roneysahil.movie_booking.booking.domain.Booking;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.DiscountNotApplicableException;
import com.roneysahil.movie_booking.user.repository.UserRepository;
import com.roneysahil.movie_booking.discount.DiscountDtos.CreateDiscountRequest;
import com.roneysahil.movie_booking.discount.DiscountDtos.DiscountCodeDto;
import com.roneysahil.movie_booking.discount.DiscountDtos.DiscountPreviewDto;
import com.roneysahil.movie_booking.discount.DiscountDtos.ValidateDiscountRequest;
import com.roneysahil.movie_booking.discount.domain.DiscountCode;
import com.roneysahil.movie_booking.discount.domain.DiscountRedemption;
import com.roneysahil.movie_booking.discount.repository.DiscountCodeRepository;
import com.roneysahil.movie_booking.discount.repository.DiscountRedemptionRepository;
import com.roneysahil.movie_booking.show.domain.ShowSeat;
import com.roneysahil.movie_booking.show.repository.ShowSeatRepository;
import com.roneysahil.movie_booking.user.domain.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaDiscountService implements DiscountService {

    private final DiscountCodeRepository codes;
    private final DiscountRedemptionRepository redemptions;
    private final ShowSeatRepository showSeats;
    private final UserRepository users;

    public JpaDiscountService(
            DiscountCodeRepository codes,
            DiscountRedemptionRepository redemptions,
            ShowSeatRepository showSeats,
            UserRepository users) {
        this.codes = codes;
        this.redemptions = redemptions;
        this.showSeats = showSeats;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public DiscountPreviewDto validate(String username, ValidateDiscountRequest request) {
        BigDecimal subtotal = subtotalFor(request.showId(), request.showSeatIds());
        User user = users.findByEmail(username).orElse(null);
        try {
            DiscountCode code = resolveUsable(user, request.code(), subtotal);
            BigDecimal amount = code.discountFor(subtotal);
            return new DiscountPreviewDto(
                    code.getCode(), true, null, subtotal, amount, subtotal.subtract(amount));
        } catch (DiscountNotApplicableException ex) {
            // A preview reports why instead of failing the request.
            return new DiscountPreviewDto(
                    request.code().toUpperCase(Locale.ROOT),
                    false,
                    ex.getMessage(),
                    subtotal,
                    BigDecimal.ZERO.setScale(2),
                    subtotal);
        }
    }

    @Override
    @Transactional
    public DiscountApplication apply(User user, String rawCode, BigDecimal subtotal) {
        if (rawCode == null || rawCode.isBlank()) {
            return DiscountApplication.none();
        }
        DiscountCode code = resolveUsable(user, rawCode, subtotal);

        // Atomic consume. The limit test is in the WHERE clause, so on the last remaining
        // use exactly one concurrent caller updates a row and the rest get zero.
        if (codes.tryConsume(code.getId()) == 0) {
            throw new DiscountNotApplicableException("This code has reached its usage limit");
        }

        return new DiscountApplication(code, code.discountFor(subtotal));
    }

    @Override
    @Transactional
    public void recordRedemption(DiscountApplication application, User user, Booking booking) {
        if (!application.applied()) {
            return;
        }
        DiscountRedemption redemption = new DiscountRedemption();
        redemption.setDiscountCode(application.code());
        redemption.setUser(user);
        redemption.setBooking(booking);
        redemptions.save(redemption);
    }

    @Override
    @Transactional
    public DiscountCodeDto createCode(CreateDiscountRequest r) {
        DiscountCode code = new DiscountCode();
        code.setCode(r.code().toUpperCase(Locale.ROOT));
        code.setType(DiscountCode.Type.valueOf(r.type().toUpperCase(Locale.ROOT)));
        code.setValue(r.value());
        code.setMaxDiscount(r.maxDiscount());
        code.setMinOrderAmount(r.minOrderAmount() == null ? BigDecimal.ZERO : r.minOrderAmount());
        code.setValidFrom(r.validFrom());
        code.setValidTo(r.validTo());
        code.setUsageLimit(r.usageLimit());
        code.setPerUserLimit(r.perUserLimit());
        code.setActive(true);
        return toDto(codes.save(code));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscountCodeDto> listCodes() {
        return codes.findAll().stream().map(this::toDto).toList();
    }

    // --- helpers -----------------------------------------------------------

    /** All validation except consumption, shared by preview and authoritative apply. */
    private DiscountCode resolveUsable(User user, String rawCode, BigDecimal subtotal) {
        DiscountCode code = codes.findByCodeIgnoreCase(rawCode)
                .orElseThrow(() -> new DiscountNotApplicableException("Unknown discount code"));

        if (!code.isActive()) {
            throw new DiscountNotApplicableException("This code is no longer active");
        }
        Instant now = Instant.now();
        if (now.isBefore(code.getValidFrom())) {
            throw new DiscountNotApplicableException("This code is not yet valid");
        }
        if (!now.isBefore(code.getValidTo())) {
            throw new DiscountNotApplicableException("This code has expired");
        }
        if (code.getUsageLimit() != null && code.getTimesUsed() >= code.getUsageLimit()) {
            throw new DiscountNotApplicableException("This code has reached its usage limit");
        }
        if (subtotal.compareTo(code.getMinOrderAmount()) < 0) {
            throw new DiscountNotApplicableException(
                    "Order must be at least " + code.getMinOrderAmount().toPlainString()
                            + " to use this code");
        }
        if (user != null && code.getPerUserLimit() != null) {
            long used = redemptions.countByDiscountCodeIdAndUserId(code.getId(), user.getId());
            if (used >= code.getPerUserLimit()) {
                throw new DiscountNotApplicableException(
                        "You have already used this code the maximum number of times");
            }
        }
        return code;
    }

    /** Preview pricing. Deliberately lock-free: a quote must not block real bookings. */
    private BigDecimal subtotalFor(Long showId, List<Long> showSeatIds) {
        return showSeats.findSeatMap(showId).stream()
                .filter(s -> showSeatIds.contains(s.getId()))
                .map(ShowSeat::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private DiscountCodeDto toDto(DiscountCode c) {
        return new DiscountCodeDto(
                c.getId(),
                c.getCode(),
                c.getType().name(),
                c.getValue(),
                c.getMaxDiscount(),
                c.getMinOrderAmount(),
                c.getValidFrom(),
                c.getValidTo(),
                c.getUsageLimit(),
                c.getTimesUsed(),
                c.isActive());
    }
}
