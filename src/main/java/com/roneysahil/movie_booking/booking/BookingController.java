package com.roneysahil.movie_booking.booking;

import com.roneysahil.movie_booking.booking.BookingDtos.BookingDto;
import com.roneysahil.movie_booking.booking.BookingDtos.CreateBookingRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.PaymentRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.RefundDto;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Booking lifecycle. Every route resolves the caller from the Principal and enforces
 * ownership in the service — role alone would let any customer read another's booking.
 */
@RestController
@RequestMapping("/api/bookings")
@PreAuthorize("hasRole('CUSTOMER')")
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingDto create(Principal principal, @Valid @RequestBody CreateBookingRequest request) {
        return bookings.createBooking(principal.getName(), request);
    }

    @PostMapping("/{bookingRef}/payment")
    public BookingDto pay(
            Principal principal,
            @PathVariable String bookingRef,
            @Valid @RequestBody PaymentRequest request) {
        return bookings.pay(principal.getName(), bookingRef, request);
    }

    @GetMapping
    public List<BookingDto> history(
            Principal principal, @RequestParam(required = false) String status) {
        return bookings.listBookings(principal.getName(), status);
    }

    @GetMapping("/{bookingRef}")
    public BookingDto get(Principal principal, @PathVariable String bookingRef) {
        return bookings.getBooking(principal.getName(), bookingRef);
    }

    @PostMapping("/{bookingRef}/cancel")
    public RefundDto cancel(Principal principal, @PathVariable String bookingRef) {
        return bookings.cancel(principal.getName(), bookingRef);
    }

    @GetMapping("/{bookingRef}/refund")
    public RefundDto refund(Principal principal, @PathVariable String bookingRef) {
        return bookings.getRefund(principal.getName(), bookingRef);
    }
}
