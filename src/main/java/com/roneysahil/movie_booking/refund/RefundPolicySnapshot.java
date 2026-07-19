package com.roneysahil.movie_booking.refund;

import com.roneysahil.movie_booking.refund.domain.RefundPolicy;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refund terms as frozen onto a booking at confirmation time.
 *
 * <p>Stored as JSON rather than referenced by foreign key on purpose: an admin editing a
 * policy tomorrow must not change the terms a customer already agreed to today.
 */
public record RefundPolicySnapshot(String policyName, List<Band> bands) {

    /** Cancel at least {@code minHoursBeforeShow} before showtime to get this percent. */
    public record Band(int minHoursBeforeShow, int refundPercent) {}

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public static String serialize(RefundPolicy policy) {
        List<Band> bands = policy.getTiers().stream()
                .map(t -> new Band(t.getMinHoursBeforeShow(), t.getRefundPercent()))
                .sorted(Comparator.comparingInt(Band::minHoursBeforeShow).reversed())
                .toList();
        return MAPPER.writeValueAsString(new RefundPolicySnapshot(policy.getName(), bands));
    }

    public static RefundPolicySnapshot parse(String json) {
        if (json == null || json.isBlank()) {
            // A booking predating snapshots refunds nothing rather than guessing terms.
            return new RefundPolicySnapshot("UNKNOWN", List.of(new Band(0, 0)));
        }
        return MAPPER.readValue(json, RefundPolicySnapshot.class);
    }

    /** First band the cancellation qualifies for, scanning from the most generous. */
    public int refundPercentFor(long hoursBeforeShow) {
        return bands.stream()
                .sorted(Comparator.comparingInt(Band::minHoursBeforeShow).reversed())
                .filter(b -> hoursBeforeShow >= b.minHoursBeforeShow())
                .map(Band::refundPercent)
                .findFirst()
                .orElse(0);
    }
}
