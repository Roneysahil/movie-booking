package com.roneysahil.movie_booking.show.domain;

import com.roneysahil.movie_booking.booking.domain.SeatHold;
import com.roneysahil.movie_booking.catalog.domain.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * THE UNIT OF ALLOCATION. One row per seat per show, materialized when the show is
 * created. Concurrent bookings serialize by locking these rows with SELECT ... FOR
 * UPDATE in id order; see docs/DESIGN.md.
 */
@Entity
@Table(name = "show_seats")
@Getter
@Setter
@NoArgsConstructor
public class ShowSeat {

    public enum Status {
        AVAILABLE,
        HELD,
        BOOKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    /** Set while HELD; cleared when the hold converts, expires or is released. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private SeatHold hold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AVAILABLE;

    /** Resolved at show creation: base x tier multiplier x weekend surcharge. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /**
     * Secondary guard. Pessimistic locking is the primary mechanism, but a stale write
     * arriving outside the lock still fails rather than silently overwriting.
     */
    @Version
    private Integer version;

    /**
     * A held seat whose hold has lapsed is effectively available. Expiry is evaluated on
     * read so correctness never depends on the sweeper having run.
     */
    public boolean isBookable() {
        if (status == Status.AVAILABLE) {
            return true;
        }
        return status == Status.HELD && hold != null && hold.isExpired();
    }
}
