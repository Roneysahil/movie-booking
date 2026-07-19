package com.roneysahil.movie_booking.refund.repository;

import com.roneysahil.movie_booking.refund.domain.RefundPolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundPolicyRepository extends JpaRepository<RefundPolicy, Long> {

    @Query("select p from RefundPolicy p join fetch p.tiers where p.isDefault = true")
    Optional<RefundPolicy> findDefaultWithTiers();

    @Query("select p from RefundPolicy p join fetch p.tiers where p.id = :id")
    Optional<RefundPolicy> findWithTiers(@Param("id") Long id);

    Optional<RefundPolicy> findByName(String name);
}
