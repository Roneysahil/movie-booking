package com.roneysahil.movie_booking.catalog.repository;

import com.roneysahil.movie_booking.catalog.domain.Screen;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScreenRepository extends JpaRepository<Screen, Long> {

    @Query("""
           select s from Screen s
            join fetch s.theater
            where (:theaterId is null or s.theater.id = :theaterId)
            order by s.id
           """)
    List<Screen> findAllWithTheater(@Param("theaterId") Long theaterId);

    @Query("select s from Screen s join fetch s.theater t join fetch t.city where s.id = :id")
    Optional<Screen> findWithTheater(@Param("id") Long id);
}
