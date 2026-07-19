package com.roneysahil.movie_booking.discount.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "discount_codes")
@Getter
@Setter
@NoArgsConstructor
public class DiscountCode {

    public enum Type {
        PERCENT,
        FLAT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal value;

    @Column(name = "max_discount", precision = 10, scale = 2)
    private BigDecimal maxDiscount;

    @Column(name = "min_order_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    /**
     * Incremented inside the booking transaction. A limited code races the same way a
     * seat does, so the increment must be conditional rather than read-then-write.
     */
    @Column(name = "times_used", nullable = false)
    private int timesUsed = 0;

    @Column(name = "per_user_limit")
    private Integer perUserLimit;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isRedeemableAt(Instant when) {
        return active
                && !when.isBefore(validFrom)
                && when.isBefore(validTo)
                && (usageLimit == null || timesUsed < usageLimit);
    }

    /** Discount for a subtotal, honouring the cap and never exceeding the subtotal. */
    public BigDecimal discountFor(BigDecimal subtotal) {
        BigDecimal discount = type == Type.PERCENT
                ? subtotal.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : value;
        if (maxDiscount != null && discount.compareTo(maxDiscount) > 0) {
            discount = maxDiscount;
        }
        if (discount.compareTo(subtotal) > 0) {
            discount = subtotal;
        }
        return discount.setScale(2, RoundingMode.HALF_UP);
    }
}
