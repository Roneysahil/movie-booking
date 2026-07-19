package com.roneysahil.movie_booking.catalog.repository;

import com.roneysahil.movie_booking.catalog.domain.Theater;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TheaterRepository extends JpaRepository<Theater, Long> {

    @Query("""
           select t from Theater t
            join fetch t.city
            where (:cityId is null or t.city.id = :cityId)
            order by t.id
           """)
    List<Theater> findAllWithCity(@Param("cityId") Long cityId);

    @Query("""
           select t from Theater t
            join fetch t.city
            where t.city.id = :cityId and t.active = true
            order by t.name
           """)
    List<Theater> findActiveInCity(@Param("cityId") Long cityId);

    @Query("select t from Theater t join fetch t.city where t.id = :id")
    Optional<Theater> findWithCity(@Param("id") Long id);
}
