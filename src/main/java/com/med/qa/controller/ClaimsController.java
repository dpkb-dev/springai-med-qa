package com.med.qa.controller;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.ratelimit.annotation.RateLimit;
import com.med.qa.common.result.ApiResult;
import com.med.qa.controller.dto.ClaimConsistencyRequest;
import com.med.qa.controller.dto.ClaimConsistencyResponse;
import com.med.qa.controller.dto.ClaimEligibilityRequest;
import com.med.qa.controller.dto.ClaimEligibilityResponse;
import com.med.qa.audit.annotation.MedAudit;
import com.med.qa.service.claims.ClaimConsistencyService;
import com.med.qa.service.claims.ClaimEligibilityService;
import com.med.qa.security.MedRole;
import com.med.qa.security.annotation.RequireDept;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Staff-only REST surface for AI-assisted reimbursement-claim eligibility checks and claim document
 * consistency checks.
 *
 * <h2>What this is, and what it is not</h2>
 * <p>{@code POST /api/claims/eligibility-check} retrieves the ingested policy clause most relevant to
 * each claimed line item and asks the chat model for an advisory verdict grounded in that clause.
 * {@code POST /api/claims/consistency-check} extracts patient name and admission/discharge dates from
 * each submitted document and deterministically flags name mismatches or impossible date ordering.
 * Both are decision <em>support</em> for a human claims reviewer — every response carries a fixed
 * advisory disclaimer, and this controller has no write access to any claims-processing system of
 * record. Neither is, or should ever be treated as, an automated approval/denial or a fraud/genuineness
 * determination.</p>
 *
 * <h2>Authorization</h2>
 * <p>Staff-only, same pattern as {@link RagAdminController}: {@link RequireDept} with
 * {@code roles = STAFF} refuses a patient principal or an anonymous call with {@code 403} before the
 * handler runs. Scope for the eligibility check travels inside the JSON body, so the interceptor is
 * {@code required = false}; the actual isolation for that endpoint is enforced by
 * {@link com.med.qa.rag.MedRetrievalService} via the same tag-scoped filtering used everywhere else in
 * the RAG corpus. The consistency check has no tenant/dept scope at all, since it compares only the
 * documents supplied in a single request against each other.</p>
 */
@RestController
@RequestMapping("/api/claims")
@RequireDept(roles = MedRole.STAFF, required = false)
@Tag(name = "Claims Assessment", description = "Staff-only, AI-assisted reimbursement-claim eligibility "
        + "and document consistency checks. Advisory decision support only — never an automated "
        + "approval, denial, or fraud/genuineness determination.")
public class ClaimsController {

    private final ClaimEligibilityService claimEligibilityService;

    private final ClaimConsistencyService claimConsistencyService;

    /**
     * Creates the controller.
     *
     * @param claimEligibilityService eligibility-check orchestration, must not be {@code null}
     * @param claimConsistencyService consistency-check orchestration, must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public ClaimsController(
            ClaimEligibilityService claimEligibilityService, ClaimConsistencyService claimConsistencyService) {
        this.claimEligibilityService =
                Objects.requireNonNull(claimEligibilityService, "claimEligibilityService must not be null");
        this.claimConsistencyService =
                Objects.requireNonNull(claimConsistencyService, "claimConsistencyService must not be null");
    }

    /**
     * Assesses every line item of a reimbursement claim against the ingested policy corpus.
     *
     * @param request the claim scope and line items, must not be {@code null}
     * @return one advisory assessment per line item, plus the fixed disclaimer
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a missing/invalid request,
     *                       {@link ErrorCode#LLM_SERVICE_ERROR} if the chat model is unavailable or a
     *                       model call fails
     */
    @MedAudit(action = "CLAIM_ELIGIBILITY_CHECK", resourceType = "CLAIM")
    @RateLimit(rate = 5, durationSeconds = 1)
    @PostMapping("/eligibility-check")
    @Operation(summary = "Check reimbursement-claim eligibility against ingested policy documents",
            description = "For each claimed line item, retrieves the most relevant policy clause and "
                    + "asks the model for an advisory LIKELY_COVERED / LIKELY_EXCLUDED / "
                    + "NEEDS_MANUAL_REVIEW verdict. Decision support only; staff only.")
    public ApiResult<ClaimEligibilityResponse> checkEligibility(
            @RequestBody @Nullable ClaimEligibilityRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "request body must not be empty");
        }
        request.validate();
        return ApiResult.ok(claimEligibilityService.assess(request));
    }

    /**
     * Checks a claim's document bundle for a patient-name mismatch or impossible admission/discharge
     * date ordering.
     *
     * @param request the document bundle, must not be {@code null} and must contain at least two
     *                documents
     * @return every flagged finding, plus the fixed disclaimer
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a missing/invalid request,
     *                       {@link ErrorCode#LLM_SERVICE_ERROR} if the chat model is unavailable or a
     *                       model call fails
     */
    @MedAudit(action = "CLAIM_CONSISTENCY_CHECK", resourceType = "CLAIM")
    @RateLimit(rate = 5, durationSeconds = 1)
    @PostMapping("/consistency-check")
    @Operation(summary = "Check a claim document bundle for name/date inconsistencies",
            description = "Extracts patient name and admission/discharge dates from each submitted "
                    + "document, then deterministically flags name mismatches and impossible date "
                    + "ordering. Decision support only; staff only.")
    public ApiResult<ClaimConsistencyResponse> checkConsistency(
            @RequestBody @Nullable ClaimConsistencyRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "request body must not be empty");
        }
        request.validate();
        return ApiResult.ok(claimConsistencyService.check(request));
    }
}
