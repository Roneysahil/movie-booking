package com.roneysahil.movie_booking.show.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Seat tier multiplier, e.g. PREMIUM x1.5. Admin-configurable. */
@Entity
@Table(name = "pricing_tiers")
@Getter
@Setter
@NoArgsConstructor
public class PricingTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String tier;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal multiplier;

    @Column(nullable = false)
    private boolean active = true;
}
