package com.roneysahil.movie_booking.notification.repository;

import com.roneysahil.movie_booking.notification.domain.NotificationOutbox;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    /** Dispatcher poll: oldest due first, bounded so one pass cannot run away. */
    @Query("""
           select n from NotificationOutbox n
            where n.status = com.roneysahil.movie_booking.notification.domain.NotificationOutbox$Status.PENDING
              and n.nextAttemptAt <= :now
            order by n.nextAttemptAt asc
           """)
    List<NotificationOutbox> findDue(@Param("now") Instant now, Limit limit);
}
