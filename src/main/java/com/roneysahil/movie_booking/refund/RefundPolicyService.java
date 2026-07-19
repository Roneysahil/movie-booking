package com.roneysahil.movie_booking.refund;

import com.roneysahil.movie_booking.refund.RefundPolicyDtos.RefundPolicyDto;
import com.roneysahil.movie_booking.refund.RefundPolicyDtos.RefundPolicyRequest;
import java.util.List;

public interface RefundPolicyService {

    List<RefundPolicyDto> listPolicies();

    RefundPolicyDto getPolicy(Long policyId);

    RefundPolicyDto createPolicy(RefundPolicyRequest request);

    RefundPolicyDto updatePolicy(Long policyId, RefundPolicyRequest request);

    /** Only one policy is the system default; promoting one demotes the previous. */
    RefundPolicyDto setDefault(Long policyId);

    void deactivatePolicy(Long policyId);

    /**
     * Resolves the refund percentage for a cancellation this many hours before showtime.
     * Called by the booking service against the policy snapshotted on the booking.
     */
    int refundPercentFor(Long policyId, long hoursBeforeShow);

    RefundPolicyDto defaultPolicy();
}
