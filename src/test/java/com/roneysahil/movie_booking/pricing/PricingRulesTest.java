package com.roneysahil.movie_booking.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.roneysahil.movie_booking.booking.domain.SeatHold;
import com.roneysahil.movie_booking.discount.domain.DiscountCode;
import com.roneysahil.movie_booking.refund.RefundPolicySnapshot;
import com.roneysahil.movie_booking.refund.RefundPolicySnapshot.Band;
import com.roneysahil.movie_booking.show.domain.ShowSeat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pure logic: money maths, refund band selection, and hold expiry. No Spring context,
 * so these run in milliseconds and pin the rules that are easiest to break by accident.
 */
class PricingRulesTest {

    @Nested
    @DisplayName("Discount calculation")
    class Discounts {

        @Test
        @DisplayName("A percentage discount is capped at its ceiling")
        void percentageIsCapped() {
            DiscountCode code = percentCode(new BigDecimal("50"), new BigDecimal("150.00"));

            // 50% of 600 is 300, but the cap is 150.
            assertThat(code.discountFor(new BigDecimal("600.00")))
                    .isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("Below the cap, the percentage applies in full")
        void percentageBelowCapApplies() {
            DiscountCode code = percentCode(new BigDecimal("50"), new BigDecimal("150.00"));

            assertThat(code.discountFor(new BigDecimal("200.00")))
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("A flat discount never exceeds the subtotal")
        void flatDiscountCannotExceedSubtotal() {
            DiscountCode code = new DiscountCode();
            code.setType(DiscountCode.Type.FLAT);
            code.setValue(new BigDecimal("500.00"));

            // Otherwise the booking total would go negative and the system would owe money.
            assertThat(code.discountFor(new BigDecimal("300.00")))
                    .isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("Rounding is half-up at two decimal places")
        void roundsToPaise() {
            DiscountCode code = percentCode(new BigDecimal("33.33"), null);

            // 33.33% of 100 = 33.33
            assertThat(code.discountFor(new BigDecimal("100.00")))
                    .isEqualByComparingTo("33.33");
        }

        @Test
        @DisplayName("An exhausted code is not redeemable")
        void exhaustedCodeIsNotRedeemable() {
            DiscountCode code = percentCode(new BigDecimal("10"), null);
            code.setUsageLimit(5);
            code.setTimesUsed(5);

            assertThat(code.isRedeemableAt(Instant.now())).isFalse();
        }

        @Test
        @DisplayName("A code outside its validity window is not redeemable")
        void expiredCodeIsNotRedeemable() {
            DiscountCode code = percentCode(new BigDecimal("10"), null);
            code.setValidFrom(Instant.now().minusSeconds(7200));
            code.setValidTo(Instant.now().minusSeconds(3600));

            assertThat(code.isRedeemableAt(Instant.now())).isFalse();
        }

        @Test
        @DisplayName("An inactive code is not redeemable even inside its window")
        void inactiveCodeIsNotRedeemable() {
            DiscountCode code = percentCode(new BigDecimal("10"), null);
            code.setActive(false);

            assertThat(code.isRedeemableAt(Instant.now())).isFalse();
        }

        private DiscountCode percentCode(BigDecimal value, BigDecimal cap) {
            DiscountCode code = new DiscountCode();
            code.setType(DiscountCode.Type.PERCENT);
            code.setValue(value);
            code.setMaxDiscount(cap);
            code.setActive(true);
            code.setValidFrom(Instant.now().minusSeconds(3600));
            code.setValidTo(Instant.now().plusSeconds(3600));
            return code;
        }
    }

    @Nested
    @DisplayName("Refund policy resolution")
    class Refunds {

        private final RefundPolicySnapshot standard = new RefundPolicySnapshot(
                "STANDARD",
                List.of(new Band(48, 100), new Band(24, 75), new Band(6, 50), new Band(0, 0)));

        @ParameterizedTest(name = "{0}h before showtime refunds {1}%")
        @CsvSource({
            "200, 100",
            "49,  100",
            "48,  100",
            "47,  75",
            "24,  75",
            "23,  50",
            "6,   50",
            "5,   0",
            "0,   0"
        })
        @DisplayName("Each band applies at its boundary")
        void bandsApplyAtBoundaries(long hoursBefore, int expectedPercent) {
            assertThat(standard.refundPercentFor(hoursBefore)).isEqualTo(expectedPercent);
        }

        @Test
        @DisplayName("Bands are honoured regardless of declared order")
        void orderOfBandsDoesNotMatter() {
            RefundPolicySnapshot shuffled = new RefundPolicySnapshot(
                    "SHUFFLED",
                    List.of(new Band(0, 0), new Band(48, 100), new Band(6, 50), new Band(24, 75)));

            assertThat(shuffled.refundPercentFor(30)).isEqualTo(75);
        }

        @Test
        @DisplayName("A cancellation after showtime refunds nothing")
        void negativeHoursRefundNothing() {
            assertThat(standard.refundPercentFor(-3)).isZero();
        }

        @Test
        @DisplayName("Snapshots survive a round trip through JSON")
        void snapshotRoundTrips() {
            String json = """
                          {"policyName":"FLEXIBLE",
                           "bands":[{"minHoursBeforeShow":2,"refundPercent":100},
                                    {"minHoursBeforeShow":0,"refundPercent":50}]}
                          """;
            RefundPolicySnapshot parsed = RefundPolicySnapshot.parse(json);

            assertThat(parsed.policyName()).isEqualTo("FLEXIBLE");
            assertThat(parsed.refundPercentFor(5)).isEqualTo(100);
            assertThat(parsed.refundPercentFor(1)).isEqualTo(50);
        }

        @Test
        @DisplayName("A missing snapshot refunds nothing rather than guessing")
        void missingSnapshotIsConservative() {
            assertThat(RefundPolicySnapshot.parse(null).refundPercentFor(1000)).isZero();
        }
    }

    @Nested
    @DisplayName("Hold expiry and seat availability")
    class Availability {

        @Test
        @DisplayName("An active hold within its window has not expired")
        void activeHoldIsLive() {
            assertThat(hold(SeatHold.Status.ACTIVE, 300).isExpired()).isFalse();
        }

        @Test
        @DisplayName("An active hold past its window has expired")
        void lapsedHoldIsExpired() {
            assertThat(hold(SeatHold.Status.ACTIVE, -1).isExpired()).isTrue();
        }

        @Test
        @DisplayName("A released hold is expired regardless of its window")
        void releasedHoldIsExpired() {
            assertThat(hold(SeatHold.Status.RELEASED, 300).isExpired()).isTrue();
        }

        @Test
        @DisplayName("A seat held by a lapsed hold is bookable again")
        void seatWithLapsedHoldIsBookable() {
            ShowSeat seat = new ShowSeat();
            seat.setStatus(ShowSeat.Status.HELD);
            seat.setHold(hold(SeatHold.Status.ACTIVE, -1));

            // This is why a stalled sweeper cannot take inventory offline.
            assertThat(seat.isBookable()).isTrue();
        }

        @Test
        @DisplayName("A seat held by a live hold is not bookable")
        void seatWithLiveHoldIsNotBookable() {
            ShowSeat seat = new ShowSeat();
            seat.setStatus(ShowSeat.Status.HELD);
            seat.setHold(hold(SeatHold.Status.ACTIVE, 300));

            assertThat(seat.isBookable()).isFalse();
        }

        @Test
        @DisplayName("A booked seat is never bookable")
        void bookedSeatIsNotBookable() {
            ShowSeat seat = new ShowSeat();
            seat.setStatus(ShowSeat.Status.BOOKED);

            assertThat(seat.isBookable()).isFalse();
        }

        private SeatHold hold(SeatHold.Status status, long secondsFromNow) {
            SeatHold hold = new SeatHold();
            hold.setStatus(status);
            hold.setExpiresAt(Instant.now().plusSeconds(secondsFromNow));
            return hold;
        }
    }
}
