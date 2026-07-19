package com.roneysahil.movie_booking.refund.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One band of a refund policy: cancel at least {@code minHoursBeforeShow} before
 * showtime and this percentage is refunded.
 */
@Entity
@Table(name = "refund_policy_tiers")
@Getter
@Setter
@NoArgsConstructor
public class RefundPolicyTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refund_policy_id", nullable = false)
    private RefundPolicy refundPolicy;

    @Column(name = "min_hours_before_show", nullable = false)
    private int minHoursBeforeShow;

    @Column(name = "refund_percent", nullable = false)
    private int refundPercent;
}
