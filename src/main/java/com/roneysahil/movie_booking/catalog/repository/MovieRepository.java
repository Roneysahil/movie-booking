package com.roneysahil.movie_booking.catalog.repository;

import com.roneysahil.movie_booking.catalog.domain.Movie;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    /**
     * Movies with at least one upcoming show in the city. {@code q} is an optional
     * case-insensitive title filter — a convenience for browse, not a search subsystem.
     */
    @Query("""
           select distinct m from Movie m
            where m.active = true
              and (:q is null or lower(m.title) like lower(concat('%', :q, '%')))
              and exists (
                    select 1 from Show s
                     where s.movie = m
                       and s.status = com.roneysahil.movie_booking.show.domain.Show$Status.SCHEDULED
                       and s.startsAt > CURRENT_TIMESTAMP
                       and s.screen.theater.city.id = :cityId)
            order by m.title
           """)
    List<Movie> findShowingInCity(@Param("cityId") Long cityId, @Param("q") String q);
}
