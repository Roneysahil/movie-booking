package com.roneysahil.movie_booking.show.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/** Single-row configuration table; a CHECK constraint enforces the singleton. */
@Entity
@Table(name = "pricing_config")
@Getter
@Setter
@NoArgsConstructor
public class PricingConfig {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id = SINGLETON_ID;

    @Column(name = "weekend_multiplier", nullable = false, precision = 6, scale = 3)
    private BigDecimal weekendMultiplier;

    @Column(name = "default_base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal defaultBasePrice;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
