package com.roneysahil.movie_booking.booking.repository;

import com.roneysahil.movie_booking.booking.domain.BookingSeat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {

    /** Fetches the show seat and its physical seat, needed to render seat labels. */
    @Query("""
           select bs from BookingSeat bs
            join fetch bs.showSeat ss
            join fetch ss.seat
            where bs.booking.id = :bookingId
            order by ss.id
           """)
    List<BookingSeat> findByBookingId(@Param("bookingId") Long bookingId);
}
