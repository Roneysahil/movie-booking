package com.roneysahil.movie_booking.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class BookingDtos {

    private BookingDtos() {}

    // --- holds -------------------------------------------------------------

    public record CreateHoldRequest(
            @NotNull Long showId, @NotEmpty List<Long> showSeatIds) {}

    public record HoldDto(
            String holdId,
            Long showId,
            List<Long> showSeatIds,
            Instant expiresAt,
            long secondsRemaining,
            String status) {}

    // --- bookings ----------------------------------------------------------

    /** Booking is created from an existing hold; seats are already claimed by then. */
    public record CreateBookingRequest(@NotBlank String holdId, String discountCode) {}

    public record BookingSeatDto(Long showSeatId, String label, String tier, BigDecimal price) {}

    public record BookingDto(
            String bookingRef,
            Long showId,
            String movieTitle,
            String theaterName,
            Instant startsAt,
            String status,
            List<BookingSeatDto> seats,
            BigDecimal subtotal,
            BigDecimal discountAmount,
            BigDecimal totalAmount,
            String discountCode,
            Instant createdAt) {}

    /**
     * {@code idempotencyKey} lets a client safely retry a payment that timed out without
     * risking a double charge.
     */
    public record PaymentRequest(
            @NotBlank String method, @NotBlank String idempotencyKey) {}

    // --- cancellation ------------------------------------------------------

    public record RefundDto(
            String bookingRef,
            BigDecimal amountPaid,
            BigDecimal refundAmount,
            int refundPercent,
            String policyName,
            String status) {}
}
