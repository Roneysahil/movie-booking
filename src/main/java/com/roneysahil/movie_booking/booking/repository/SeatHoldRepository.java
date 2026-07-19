package com.roneysahil.movie_booking.booking.repository;

import com.roneysahil.movie_booking.booking.domain.SeatHold;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    /** Drives the expiry sweeper. */
    @Query("""
           select h from SeatHold h
            where h.status = com.roneysahil.movie_booking.booking.domain.SeatHold$Status.ACTIVE
              and h.expiresAt < :now
           """)
    List<SeatHold> findLapsed(@Param("now") Instant now);
}
