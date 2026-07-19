package com.roneysahil.movie_booking.refund;

import com.roneysahil.movie_booking.common.exception.ApiExceptions.BusinessRuleException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.NotFoundException;
import com.roneysahil.movie_booking.refund.RefundPolicyDtos.RefundPolicyDto;
import com.roneysahil.movie_booking.refund.RefundPolicyDtos.RefundPolicyRequest;
import com.roneysahil.movie_booking.refund.RefundPolicyDtos.RefundTierDto;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * In-memory placeholder. Ships one seeded STANDARD policy matching docs/DESIGN.md:
 * >48h full refund, 24-48h 75%, 6-24h 50%, under 6h nothing.
 */
@Service
public class StubRefundPolicyService implements RefundPolicyService {

    private final Map<Long, RefundPolicyDto> policies = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    private static final List<RefundTierDto> STANDARD_TIERS = List.of(
            new RefundTierDto(48, 100),
            new RefundTierDto(24, 75),
            new RefundTierDto(6, 50),
            new RefundTierDto(0, 0));

    public StubRefundPolicyService() {
        policies.put(1L, new RefundPolicyDto(1L, "STANDARD", STANDARD_TIERS, true, true));
    }

    @Override
    public List<RefundPolicyDto> listPolicies() {
        return policies.values().stream()
                .sorted(Comparator.comparing(RefundPolicyDto::id))
                .toList();
    }

    @Override
    public RefundPolicyDto getPolicy(Long policyId) {
        RefundPolicyDto policy = policies.get(policyId);
        if (policy == null) {
            throw new NotFoundException("Refund policy " + policyId + " not found");
        }
        return policy;
    }

    @Override
    public RefundPolicyDto createPolicy(RefundPolicyRequest request) {
        List<RefundTierDto> tiers = validateTiers(request.tiers());
        long id = ids.incrementAndGet();
        RefundPolicyDto policy = new RefundPolicyDto(id, request.name(), tiers, false, true);
        policies.put(id, policy);
        return policy;
    }

    @Override
    public RefundPolicyDto updatePolicy(Long policyId, RefundPolicyRequest request) {
        RefundPolicyDto existing = getPolicy(policyId);
        List<RefundTierDto> tiers = validateTiers(request.tiers());
        RefundPolicyDto updated = new RefundPolicyDto(
                policyId, request.name(), tiers, existing.isDefault(), existing.active());
        policies.put(policyId, updated);
        return updated;
    }

    @Override
    public RefundPolicyDto setDefault(Long policyId) {
        RefundPolicyDto target = getPolicy(policyId);
        policies.replaceAll((id, p) -> p.isDefault()
                ? new RefundPolicyDto(p.id(), p.name(), p.tiers(), false, p.active())
                : p);
        RefundPolicyDto promoted = new RefundPolicyDto(
                target.id(), target.name(), target.tiers(), true, target.active());
        policies.put(policyId, promoted);
        return promoted;
    }

    @Override
    public void deactivatePolicy(Long policyId) {
        RefundPolicyDto policy = getPolicy(policyId);
        if (policy.isDefault()) {
            throw new BusinessRuleException(
                    "Cannot deactivate the default policy; promote another one first");
        }
        policies.put(
                policyId,
                new RefundPolicyDto(
                        policy.id(), policy.name(), policy.tiers(), false, false));
    }

    @Override
    public int refundPercentFor(Long policyId, long hoursBeforeShow) {
        RefundPolicyDto policy = policyId == null ? defaultPolicy() : getPolicy(policyId);
        return policy.tiers().stream()
                .sorted(Comparator.comparing(RefundTierDto::minHoursBeforeShow).reversed())
                .filter(t -> hoursBeforeShow >= t.minHoursBeforeShow())
                .map(RefundTierDto::refundPercent)
                .findFirst()
                .orElse(0);
    }

    @Override
    public RefundPolicyDto defaultPolicy() {
        return policies.values().stream()
                .filter(RefundPolicyDto::isDefault)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("No default refund policy configured"));
    }

    /**
     * A policy must cover every cancellation time, so it needs a 0-hour band; without one
     * a late cancellation would fall through to an undefined refund.
     */
    private List<RefundTierDto> validateTiers(List<RefundTierDto> tiers) {
        boolean hasFloor = tiers.stream().anyMatch(t -> t.minHoursBeforeShow() == 0);
        if (!hasFloor) {
            throw new BusinessRuleException(
                    "Policy must define a tier at 0 hours to cover late cancellations");
        }
        long distinctHours =
                tiers.stream().map(RefundTierDto::minHoursBeforeShow).distinct().count();
        if (distinctHours != tiers.size()) {
            throw new BusinessRuleException("Duplicate minHoursBeforeShow in policy tiers");
        }
        return tiers.stream()
                .sorted(Comparator.comparing(RefundTierDto::minHoursBeforeShow).reversed())
                .toList();
    }
}
