package com.med.qa.controller;

import com.med.qa.audit.annotation.MedAudit;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.ratelimit.annotation.RateLimit;
import com.med.qa.common.result.ApiResult;
import com.med.qa.controller.dto.PolicyEligibilityRequest;
import com.med.qa.controller.dto.PolicyEligibilityResponse;
import com.med.qa.security.MedRole;
import com.med.qa.security.annotation.RequireDept;
import com.med.qa.service.underwriting.PolicyEligibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Staff-only REST surface for pre-existing-disease eligibility checks at claim pre-authorization.
 *
 * <h2>Where this sits in the claim journey</h2>
 * <p>This endpoint answers "is this condition covered under the policy's PED terms at all?", which
 * is asked at <em>pre-authorization</em>, before treatment is approved. That is a different
 * checkpoint from {@code /api/claims/eligibility-check}, which assesses individual billed line items
 * against policy clauses once a bill exists.</p>
 *
 * <h2>Why some verdicts are terminal, and what that means</h2>
 * <p>Unlike the advisory claims endpoints, this check can return a terminal rejection — notably for
 * non-disclosure of a pre-existing disease. That is deliberate and business-signed: by the time this
 * runs, the insurer's doctor team has already made the medical determination of whether the claim is
 * PED-related, so what remains is a mechanical check of the declaration record. The policyholder's
 * recourse against a terminal verdict is the insurer's grievance cell and, failing that, the IRDAI's
 * IGMS portal, both of which are returned on the response rather than left implicit.</p>
 *
 * <p>No AI model participates in this path at all. Every verdict comes from
 * {@link com.med.qa.service.underwriting.UnderwritingRulesEvaluator}, in plain deterministic Java.</p>
 */
@RestController
@RequestMapping("/api/policy")
@RequireDept(roles = MedRole.STAFF, required = false)
@Tag(name = "Policy Eligibility", description = "Staff-only pre-existing-disease eligibility checks "
        + "at claim pre-authorization. Verdicts are deterministic and never model-generated.")
public class PolicyEligibilityController {

    private final PolicyEligibilityService policyEligibilityService;

    /**
     * Creates the controller.
     *
     * @param policyEligibilityService eligibility orchestration, must not be {@code null}
     * @throws NullPointerException if {@code policyEligibilityService} is {@code null}
     */
    public PolicyEligibilityController(PolicyEligibilityService policyEligibilityService) {
        this.policyEligibilityService =
                Objects.requireNonNull(policyEligibilityService, "policyEligibilityService must not be null");
    }

    /**
     * Checks whether a claimed condition is covered under the policy's pre-existing-disease terms.
     *
     * @param request the policy, condition, claim date and the doctor team's PED determination
     * @return the verdict, its rationale, and the escalation path where the verdict is terminal
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a missing or invalid request
     */
    @MedAudit(action = "POLICY_ELIGIBILITY_CHECK", resourceType = "POLICY")
    @RateLimit(rate = 10, durationSeconds = 1)
    @PostMapping("/eligibility")
    @Operation(summary = "Check pre-existing-disease eligibility for a claimed condition",
            description = "Applies the declaration and waiting-period rules deterministically. "
                    + "Non-disclosure and permanent exclusion are terminal verdicts, returned with "
                    + "the grievance-cell and IRDAI escalation path. Staff only.")
    public ApiResult<PolicyEligibilityResponse> checkEligibility(
            @RequestBody @Nullable PolicyEligibilityRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "request body must not be empty");
        }
        request.validate();
        return ApiResult.ok(policyEligibilityService.check(request));
    }
}
