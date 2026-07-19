package com.roneysahil.movie_booking.booking.repository;

import com.roneysahil.movie_booking.booking.domain.Booking;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingRef(String bookingRef);

    /** Booking history. Joins are fetched so the listing does not trigger N+1. */
    @Query("""
           select distinct b from Booking b
            join fetch b.show s
            join fetch s.movie
            join fetch s.screen sc
            join fetch sc.theater
            where b.user.email = :email
            order by b.createdAt desc
           """)
    List<Booking> findHistory(@Param("email") String email);

    /** Checkouts abandoned before payment, reclaimed by the sweeper. */
    @Query("""
           select b from Booking b
            where b.status = com.roneysahil.movie_booking.booking.domain.Booking$Status.PENDING_PAYMENT
              and b.createdAt < :cutoff
           """)
    List<Booking> findAbandoned(@Param("cutoff") Instant cutoff);

    /**
     * Guards rescheduling and repricing. Moving a show that people have already paid to
     * attend would invalidate tickets already sold.
     */
    @Query("""
           select count(b) from Booking b
            where b.show.id = :showId
              and b.status = com.roneysahil.movie_booking.booking.domain.Booking$Status.CONFIRMED
           """)
    long countConfirmedForShow(@Param("showId") Long showId);

    /**
     * Confirmed bookings whose show starts inside the reminder window and which have not
     * been reminded yet.
     *
     * <p>The NOT EXISTS is what makes the reminder job idempotent: the unique constraint
     * on (booking_id, type) would also prevent a duplicate, but by raising an error
     * rather than quietly skipping. Filtering here keeps the happy path clean.
     */
    @Query("""
           select b from Booking b
            join fetch b.user
            join fetch b.show s
            join fetch s.movie
            join fetch s.screen sc
            join fetch sc.theater
            where b.status = com.roneysahil.movie_booking.booking.domain.Booking$Status.CONFIRMED
              and s.startsAt between :from and :to
              and not exists (
                    select 1 from NotificationOutbox n
                     where n.bookingId = b.id
                       and n.type = com.roneysahil.movie_booking.notification.domain.NotificationOutbox$Type.SHOW_REMINDER)
           """)
    List<Booking> findNeedingReminder(@Param("from") Instant from, @Param("to") Instant to);
}
