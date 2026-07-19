package com.roneysahil.movie_booking.refund;

import com.roneysahil.movie_booking.refund.RefundPolicyDtos.RefundPolicyDto;
import com.roneysahil.movie_booking.refund.RefundPolicyDtos.RefundPolicyRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin management of refund policies. Theatres are assigned one via AdminCatalogController. */
@RestController
@RequestMapping("/api/admin/refund-policies")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRefundPolicyController {

    private final RefundPolicyService policies;

    public AdminRefundPolicyController(RefundPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    public List<RefundPolicyDto> list() {
        return policies.listPolicies();
    }

    @GetMapping("/{policyId}")
    public RefundPolicyDto get(@PathVariable Long policyId) {
        return policies.getPolicy(policyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RefundPolicyDto create(@Valid @RequestBody RefundPolicyRequest request) {
        return policies.createPolicy(request);
    }

    @PutMapping("/{policyId}")
    public RefundPolicyDto update(
            @PathVariable Long policyId, @Valid @RequestBody RefundPolicyRequest request) {
        return policies.updatePolicy(policyId, request);
    }

    @PutMapping("/{policyId}/default")
    public RefundPolicyDto setDefault(@PathVariable Long policyId) {
        return policies.setDefault(policyId);
    }

    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable Long policyId) {
        policies.deactivatePolicy(policyId);
    }
}
