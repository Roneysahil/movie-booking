package com.roneysahil.movie_booking.show.repository;

import com.roneysahil.movie_booking.show.domain.ShowSeat;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

    /**
     * THE SERIALIZATION POINT. Takes exclusive row locks on the requested seats and
     * blocks until any competing transaction commits.
     *
     * <p>{@code ORDER BY ss.id} is not cosmetic: two concurrent requests for overlapping
     * selections ({A5,A6} and {A6,A5}) would otherwise acquire locks in opposite order
     * and deadlock. Consistent ordering makes that impossible.
     *
     * <p>Callers MUST re-check seat status after this returns. Checking before the lock
     * is held is a time-of-check/time-of-use bug that defeats the mechanism entirely.
     *
     * <p>The lock timeout bounds how long a caller waits behind a slow transaction rather
     * than blocking indefinitely.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("""
           select ss from ShowSeat ss
            where ss.show.id = :showId and ss.id in :seatIds
            order by ss.id
           """)
    List<ShowSeat> lockSeatsForUpdate(
            @Param("showId") Long showId, @Param("seatIds") Collection<Long> seatIds);

    /** Seat map read. Fetches seat and hold eagerly to avoid N+1 across 50 seats. */
    @Query("""
           select ss from ShowSeat ss
            join fetch ss.seat
            left join fetch ss.hold
            where ss.show.id = :showId
            order by ss.id
           """)
    List<ShowSeat> findSeatMap(@Param("showId") Long showId);

    @Query("""
           select count(ss) from ShowSeat ss
            where ss.show.id = :showId
              and (ss.status = com.roneysahil.movie_booking.show.domain.ShowSeat$Status.AVAILABLE
                   or (ss.status = com.roneysahil.movie_booking.show.domain.ShowSeat$Status.HELD
                       and ss.hold.expiresAt < CURRENT_TIMESTAMP))
           """)
    long countAvailable(@Param("showId") Long showId);
}
