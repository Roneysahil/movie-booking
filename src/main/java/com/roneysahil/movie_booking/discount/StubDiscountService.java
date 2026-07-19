package com.roneysahil.movie_booking.discount;

import com.roneysahil.movie_booking.common.exception.ApiExceptions.DiscountNotApplicableException;
import com.roneysahil.movie_booking.discount.DiscountDtos.CreateDiscountRequest;
import com.roneysahil.movie_booking.discount.DiscountDtos.DiscountCodeDto;
import com.roneysahil.movie_booking.discount.DiscountDtos.DiscountPreviewDto;
import com.roneysahil.movie_booking.discount.DiscountDtos.ValidateDiscountRequest;
import com.roneysahil.movie_booking.show.ShowDtos.SeatDto;
import com.roneysahil.movie_booking.show.ShowService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * In-memory placeholder with two canned codes. The real implementation counts
 * redemptions inside the booking transaction, since a usage-limited code races the same
 * way a seat does.
 */
@Service
public class StubDiscountService implements DiscountService {

    private record Code(
            String type, BigDecimal value, BigDecimal maxDiscount, BigDecimal minOrderAmount) {}

    private static final Map<String, Code> CODES = Map.of(
            "FIRST50",
            new Code("PERCENT", new BigDecimal("50"), new BigDecimal("150.00"), new BigDecimal("300.00")),
            "FLAT100",
            new Code("FLAT", new BigDecimal("100.00"), null, new BigDecimal("500.00")));

    private final ShowService shows;
    private final AtomicLong ids = new AtomicLong(900);

    public StubDiscountService(ShowService shows) {
        this.shows = shows;
    }

    @Override
    public DiscountPreviewDto validate(String username, ValidateDiscountRequest request) {
        BigDecimal subtotal = subtotalFor(request.showId(), request.showSeatIds());
        try {
            BigDecimal discount = computeDiscount(username, request.code(), subtotal);
            return new DiscountPreviewDto(
                    request.code().toUpperCase(Locale.ROOT),
                    true,
                    null,
                    subtotal,
                    discount,
                    subtotal.subtract(discount));
        } catch (DiscountNotApplicableException ex) {
            // A preview reports why rather than failing the request.
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
    public BigDecimal computeDiscount(String username, String code, BigDecimal subtotal) {
        if (code == null || code.isBlank()) {
            return BigDecimal.ZERO.setScale(2);
        }
        Code c = CODES.get(code.toUpperCase(Locale.ROOT));
        if (c == null) {
            throw new DiscountNotApplicableException("Unknown discount code");
        }
        if (subtotal.compareTo(c.minOrderAmount()) < 0) {
            throw new DiscountNotApplicableException(
                    "Order must be at least " + c.minOrderAmount() + " to use this code");
        }
        BigDecimal discount = "PERCENT".equals(c.type())
                ? subtotal.multiply(c.value()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                : c.value();
        if (c.maxDiscount() != null && discount.compareTo(c.maxDiscount()) > 0) {
            discount = c.maxDiscount();
        }
        // Never discount below zero.
        if (discount.compareTo(subtotal) > 0) {
            discount = subtotal;
        }
        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public DiscountCodeDto createCode(CreateDiscountRequest r) {
        return new DiscountCodeDto(
                ids.incrementAndGet(),
                r.code().toUpperCase(Locale.ROOT),
                r.type(),
                r.value(),
                r.maxDiscount(),
                r.minOrderAmount(),
                r.validFrom(),
                r.validTo(),
                r.usageLimit(),
                0,
                true);
    }

    @Override
    public List<DiscountCodeDto> listCodes() {
        Instant now = Instant.now();
        return CODES.entrySet().stream()
                .map(e -> new DiscountCodeDto(
                        ids.incrementAndGet(),
                        e.getKey(),
                        e.getValue().type(),
                        e.getValue().value(),
                        e.getValue().maxDiscount(),
                        e.getValue().minOrderAmount(),
                        now.minusSeconds(86400),
                        now.plusSeconds(86400 * 30),
                        100,
                        0,
                        true))
                .toList();
    }

    private BigDecimal subtotalFor(Long showId, List<Long> showSeatIds) {
        Map<Long, SeatDto> byId = shows.getSeatMap(showId).seats().stream()
                .collect(java.util.stream.Collectors.toMap(SeatDto::showSeatId, s -> s));
        return showSeatIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(SeatDto::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
