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
    // The casts are load-bearing: without them Postgres cannot infer a type for a null
    // :q and binds it as bytea, so lower(...) fails with "function lower(bytea) does not
    // exist" whenever the filter is omitted.
    @Query("""
           select distinct m from Movie m
            where m.active = true
              and (cast(:q as string) is null
                   or lower(m.title) like lower(concat('%', cast(:q as string), '%')))
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
