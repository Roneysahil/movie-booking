package com.roneysahil.movie_booking.booking;

import com.roneysahil.movie_booking.booking.BookingDtos.CreateHoldRequest;
import com.roneysahil.movie_booking.booking.BookingDtos.HoldDto;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seat holds. POST /api/holds is the contention point of the whole system: concurrent
 * requests for the same seat must serialize, with exactly one winner.
 */
@RestController
@RequestMapping("/api/holds")
@PreAuthorize("hasRole('CUSTOMER')")
public class HoldController {

    private final BookingService bookings;

    public HoldController(BookingService bookings) {
        this.bookings = bookings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldDto create(Principal principal, @Valid @RequestBody CreateHoldRequest request) {
        return bookings.createHold(principal.getName(), request);
    }

    @GetMapping("/{holdId}")
    public HoldDto get(Principal principal, @PathVariable String holdId) {
        return bookings.getHold(principal.getName(), holdId);
    }

    @DeleteMapping("/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(Principal principal, @PathVariable String holdId) {
        bookings.releaseHold(principal.getName(), holdId);
    }
}
