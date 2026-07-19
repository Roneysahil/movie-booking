package com.roneysahil.movie_booking.refund.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "refund_policies")
@Getter
@Setter
@NoArgsConstructor
public class RefundPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /** A partial unique index enforces at most one default policy. */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(nullable = false)
    private boolean active = true;

    /** Ordered so resolution can take the first band the cancellation qualifies for. */
    @OneToMany(mappedBy = "refundPolicy", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("minHoursBeforeShow DESC")
    private List<RefundPolicyTier> tiers = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Refund percentage for a cancellation this many hours before showtime. */
    public int refundPercentFor(long hoursBeforeShow) {
        return tiers.stream()
                .filter(t -> hoursBeforeShow >= t.getMinHoursBeforeShow())
                .map(RefundPolicyTier::getRefundPercent)
                .findFirst()
                .orElse(0);
    }

    public void addTier(RefundPolicyTier tier) {
        tier.setRefundPolicy(this);
        tiers.add(tier);
    }
}
