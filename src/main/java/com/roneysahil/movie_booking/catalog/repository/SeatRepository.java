package com.roneysahil.movie_booking.catalog.repository;

import com.roneysahil.movie_booking.catalog.domain.Seat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScreenIdOrderByRowLabelAscSeatNumberAsc(Long screenId);

    void deleteByScreenId(Long screenId);

    /** Guards deletion of a pricing tier still referenced by a seat layout. */
    @Query("select count(s) from Seat s where s.tier = :tier")
    long countByTier(@Param("tier") String tier);
}
