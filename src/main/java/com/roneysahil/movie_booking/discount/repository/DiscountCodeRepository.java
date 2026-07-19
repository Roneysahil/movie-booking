package com.roneysahil.movie_booking.discount.repository;

import com.roneysahil.movie_booking.discount.domain.DiscountCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiscountCodeRepository extends JpaRepository<DiscountCode, Long> {

    Optional<DiscountCode> findByCodeIgnoreCase(String code);

    /**
     * Race-safe consumption of one use. The limit test lives in the WHERE clause, so the
     * database picks the winner: concurrent callers competing for the last remaining use
     * produce exactly one row update, and the losers get 0.
     *
     * <p>Reading times_used and writing it back would let both callers pass the check and
     * overshoot the limit — the same class of bug as double-booking a seat.
     */
    // flushAutomatically pushes pending changes before the bulk update. clearAutomatically
    // is deliberately NOT set: clearing the persistence context mid-transaction would
    // detach the show, hold and seats the caller is still working with.
    @Modifying(flushAutomatically = true)
    @Query("""
           update DiscountCode d
              set d.timesUsed = d.timesUsed + 1
            where d.id = :id
              and (d.usageLimit is null or d.timesUsed < d.usageLimit)
           """)
    int tryConsume(@Param("id") Long id);
}
