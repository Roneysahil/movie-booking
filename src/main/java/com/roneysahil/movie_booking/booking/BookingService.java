package com.roneysahil.movie_booking.booking;

import com.roneysahil.movie_booking.booking.BookingDtos.BookingDto;
import com.roneysahil.movie_booking.booking.BookingDtos.CreateBookingRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.CreateHoldRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.HoldDto;
import com.roneysahil.movie_booking.booking.BookingDtos.PaymentRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.RefundDto;
import java.util.List;

public interface BookingService {

    /**
     * Claims seats for a short window. This is the contention point: the real
     * implementation locks the show_seat rows in id order and re-checks status while
     * holding the lock. Throws SeatUnavailableException if any seat was taken first.
     */
    HoldDto createHold(String username, CreateHoldRequest request);

    HoldDto getHold(String username, String holdId);

    void releaseHold(String username, String holdId);

    /** Prices the held seats and applies any discount. No money moves yet. */
    BookingDto createBooking(String username, CreateBookingRequest request);

    /** Confirms payment and flips the seats to BOOKED. Idempotent per idempotencyKey. */
    BookingDto pay(String username, String bookingRef, PaymentRequest request);

    List<BookingDto> listBookings(String username, String status);

    BookingDto getBooking(String username, String bookingRef);

    /** Cancels and computes the refund from the policy snapshotted on the booking. */
    RefundDto cancel(String username, String bookingRef);

    RefundDto getRefund(String username, String bookingRef);
}
