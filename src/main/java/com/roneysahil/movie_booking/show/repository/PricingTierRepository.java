package com.roneysahil.movie_booking.show.repository;

import com.roneysahil.movie_booking.show.domain.PricingTier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricingTierRepository extends JpaRepository<PricingTier, Long> {

    Optional<PricingTier> findByTier(String tier);

    List<PricingTier> findByActiveTrueOrderByTierAsc();
}
