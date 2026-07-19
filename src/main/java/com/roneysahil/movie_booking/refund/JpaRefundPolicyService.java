package com.roneysahil.movie_booking.refund;

import com.roneysahil.movie_booking.common.exception.ApiExceptions.BusinessRuleException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.NotFoundException;
import com.roneysahil.movie_booking.refund.RefundPolicyDtos.RefundPolicyDto;
import com.roneysahil.movie_booking.refund.RefundPolicyDtos.RefundPolicyRequest;
import com.roneysahil.movie_booking.refund.RefundPolicyDtos.RefundTierDto;
import com.roneysahil.movie_booking.refund.domain.RefundPolicy;
import com.roneysahil.movie_booking.refund.domain.RefundPolicyTier;
import com.roneysahil.movie_booking.refund.repository.RefundPolicyRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaRefundPolicyService implements RefundPolicyService {

    private final RefundPolicyRepository policies;

    public JpaRefundPolicyService(RefundPolicyRepository policies) {
        this.policies = policies;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefundPolicyDto> listPolicies() {
        return policies.findAll().stream()
                .sorted(Comparator.comparing(RefundPolicy::getId))
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RefundPolicyDto getPolicy(Long policyId) {
        return toDto(requirePolicy(policyId));
    }

    @Override
    @Transactional
    public RefundPolicyDto createPolicy(RefundPolicyRequest request) {
        validateTiers(request.tiers());
        policies.findByName(request.name()).ifPresent(existing -> {
            throw new BusinessRuleException("A policy named " + request.name() + " already exists");
        });

        RefundPolicy policy = new RefundPolicy();
        policy.setName(request.name());
        policy.setDefault(false);
        policy.setActive(true);
        applyTiers(policy, request.tiers());
        return toDto(policies.save(policy));
    }

    @Override
    @Transactional
    public RefundPolicyDto updatePolicy(Long policyId, RefundPolicyRequest request) {
        validateTiers(request.tiers());
        RefundPolicy policy = requirePolicy(policyId);
        policy.setName(request.name());
        policy.getTiers().clear();
        applyTiers(policy, request.tiers());
        // Bookings already made are unaffected: their terms were snapshotted at
        // confirmation, so this edit only governs future bookings.
        return toDto(policy);
    }

    @Override
    @Transactional
    public RefundPolicyDto setDefault(Long policyId) {
        RefundPolicy target = requirePolicy(policyId);
        if (!target.isActive()) {
            throw new BusinessRuleException("An inactive policy cannot be made the default");
        }

        // Demote first and flush: a partial unique index permits only one default, so
        // promoting before demoting would collide.
        policies.findDefaultWithTiers().ifPresent(current -> current.setDefault(false));
        policies.flush();

        target.setDefault(true);
        return toDto(target);
    }

    @Override
    @Transactional
    public void deactivatePolicy(Long policyId) {
        RefundPolicy policy = requirePolicy(policyId);
        if (policy.isDefault()) {
            throw new BusinessRuleException(
                    "Cannot deactivate the default policy; promote another one first");
        }
        policy.setActive(false);
    }

    @Override
    @Transactional(readOnly = true)
    public int refundPercentFor(Long policyId, long hoursBeforeShow) {
        RefundPolicy policy = policyId == null
                ? policies.findDefaultWithTiers()
                        .orElseThrow(() -> new NotFoundException("No default refund policy"))
                : requirePolicy(policyId);
        return policy.refundPercentFor(hoursBeforeShow);
    }

    @Override
    @Transactional(readOnly = true)
    public RefundPolicyDto defaultPolicy() {
        return toDto(policies.findDefaultWithTiers()
                .orElseThrow(() -> new NotFoundException("No default refund policy configured")));
    }

    // --- helpers -----------------------------------------------------------

    /**
     * A policy must cover every cancellation time, so it needs a band at zero hours.
     * Without one, a late cancellation falls through to an undefined refund.
     */
    private void validateTiers(List<RefundTierDto> tiers) {
        if (tiers.stream().noneMatch(t -> t.minHoursBeforeShow() == 0)) {
            throw new BusinessRuleException(
                    "Policy must define a tier at 0 hours to cover late cancellations");
        }
        long distinct = tiers.stream().map(RefundTierDto::minHoursBeforeShow).distinct().count();
        if (distinct != tiers.size()) {
            throw new BusinessRuleException("Duplicate minHoursBeforeShow in policy tiers");
        }
    }

    private void applyTiers(RefundPolicy policy, List<RefundTierDto> tiers) {
        tiers.stream()
                .sorted(Comparator.comparingInt(RefundTierDto::minHoursBeforeShow).reversed())
                .forEach(t -> {
                    RefundPolicyTier tier = new RefundPolicyTier();
                    tier.setMinHoursBeforeShow(t.minHoursBeforeShow());
                    tier.setRefundPercent(t.refundPercent());
                    policy.addTier(tier);
                });
    }

    private RefundPolicy requirePolicy(Long policyId) {
        return policies.findWithTiers(policyId)
                .orElseThrow(() -> new NotFoundException("Refund policy " + policyId + " not found"));
    }

    private RefundPolicyDto toDto(RefundPolicy p) {
        return new RefundPolicyDto(
                p.getId(),
                p.getName(),
                p.getTiers().stream()
                        .sorted(Comparator.comparingInt(RefundPolicyTier::getMinHoursBeforeShow)
                                .reversed())
                        .map(t -> new RefundTierDto(t.getMinHoursBeforeShow(), t.getRefundPercent()))
                        .toList(),
                p.isDefault(),
                p.isActive());
    }
}
