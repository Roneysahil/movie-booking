package com.roneysahil.movie_booking.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Transactional outbox row. Written in the same transaction as the booking and
 * dispatched asynchronously, so a mail provider outage can never roll back a paid
 * booking.
 *
 * <p>The booking_id is a plain column rather than an association: the outbox is a queue,
 * and dispatching should not drag a booking object graph into memory.
 */
@Entity
@Table(name = "notification_outbox")
@Getter
@Setter
@NoArgsConstructor
public class NotificationOutbox {

    public enum Type {
        BOOKING_CONFIRMATION,
        SHOW_REMINDER,
        REFUND_PROCESSED,
        BOOKING_CANCELLED
    }

    public enum Status {
        PENDING,
        SENT,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id")
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private String recipient;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    /** Drives exponential backoff between delivery attempts. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
