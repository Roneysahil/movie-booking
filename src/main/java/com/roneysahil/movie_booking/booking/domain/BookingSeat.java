package com.roneysahil.movie_booking.booking.domain;

import com.roneysahil.movie_booking.show.domain.ShowSeat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Join row between a booking and an allocated show seat.
 *
 * <p>A partial unique index on {@code (show_seat_id) WHERE active} is the hard guarantee
 * against double allocation: even with buggy locking, a seat cannot land in two live
 * bookings. Cancellation clears {@code active} rather than deleting the row, preserving
 * history while freeing the seat.
 */
@Entity
@Table(name = "booking_seats")
@Getter
@Setter
@NoArgsConstructor
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_seat_id", nullable = false)
    private ShowSeat showSeat;

    /** Price as charged. Repricing a tier later must not change what was paid. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean active = true;
}
