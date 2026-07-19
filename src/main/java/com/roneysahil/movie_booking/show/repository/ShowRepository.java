package com.roneysahil.movie_booking.show.repository;

import com.roneysahil.movie_booking.show.domain.Show;
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
}
