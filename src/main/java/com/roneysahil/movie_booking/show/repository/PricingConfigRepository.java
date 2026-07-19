package com.roneysahil.movie_booking.show.repository;

import com.roneysahil.movie_booking.show.domain.PricingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** Single-row table; a CHECK constraint enforces the singleton. */
public interface PricingConfigRepository extends JpaRepository<PricingConfig, Short> {}
