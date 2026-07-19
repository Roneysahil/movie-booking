package com.roneysahil.movie_booking.show.repository;

import com.roneysahil.movie_booking.show.domain.Show;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShowRepository extends JpaRepository<Show, Long> {

    /** Show detail with the joins the booking and browse paths always need. */
    @Query("""
           select s from Show s
            join fetch s.movie
            join fetch s.screen sc
            join fetch sc.theater t
            join fetch t.city
            where s.id = :id
           """)
    Optional<Show> findDetailById(@Param("id") Long id);

    /** Customer browse. Every filter is optional. */
    @Query("""
           select s from Show s
            join fetch s.movie m
            join fetch s.screen sc
            join fetch sc.theater t
            join fetch t.city c
            where s.status = com.roneysahil.movie_booking.show.domain.Show$Status.SCHEDULED
              and s.startsAt > CURRENT_TIMESTAMP
              and (:cityId is null or c.id = :cityId)
              and (:movieId is null or m.id = :movieId)
              and (:theaterId is null or t.id = :theaterId)
              and (cast(:dayStart as timestamp) is null or s.startsAt >= :dayStart)
              and (cast(:dayEnd as timestamp) is null or s.startsAt < :dayEnd)
            order by s.startsAt
           """)
    List<Show> findForBrowse(
            @Param("cityId") Long cityId,
            @Param("movieId") Long movieId,
            @Param("theaterId") Long theaterId,
            @Param("dayStart") Instant dayStart,
            @Param("dayEnd") Instant dayEnd);

    /** Admin listing: includes cancelled and past shows. */
    @Query("""
           select s from Show s
            join fetch s.movie
            join fetch s.screen sc
            join fetch sc.theater t
            join fetch t.city
            where (:theaterId is null or t.id = :theaterId)
              and (cast(:dayStart as timestamp) is null or s.startsAt >= :dayStart)
              and (cast(:dayEnd as timestamp) is null or s.startsAt < :dayEnd)
            order by s.startsAt
           """)
    List<Show> findForAdmin(
            @Param("theaterId") Long theaterId,
            @Param("dayStart") Instant dayStart,
            @Param("dayEnd") Instant dayEnd);

    /**
     * Guards seat-layout edits. Remapping seats under a scheduled show would invalidate
     * the show_seat rows already materialized against them.
     */
    @Query("""
           select count(s) from Show s
            where s.screen.id = :screenId
              and s.status = com.roneysahil.movie_booking.show.domain.Show$Status.SCHEDULED
              and s.startsAt > CURRENT_TIMESTAMP
           """)
    long countScheduledOnScreen(@Param("screenId") Long screenId);
}
