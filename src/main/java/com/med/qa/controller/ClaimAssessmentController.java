package com.med.qa.controller;

import com.med.qa.audit.annotation.MedAudit;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.ratelimit.annotation.RateLimit;
import com.med.qa.common.result.ApiResult;
import com.med.qa.controller.dto.ClaimAssessmentRequest;
import com.med.qa.controller.dto.ClaimAssessmentResponse;
import com.med.qa.security.MedRole;
import com.med.qa.security.annotation.RequireDept;
import com.med.qa.service.claims.ClaimProcessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Staff-only entry point for assessing a submitted claim end to end.
 *
 * <h2>What this endpoint is</h2>
 * <p>{@code POST /api/claims/assess} runs one claim through all three assessment stages in order —
 * pre-existing-disease coverage, document consistency, then billed line items — stopping as soon as
 * a stage terminally blocks it. It is the single call a claims-processing system makes, rather than
 * orchestrating the three underlying endpoints itself.</p>
 *
 * <h2>Cashless and reimbursement</h2>
 * <p>Both claim types use this same endpoint and the same assessment. A cashless claim is assessed
 * at pre-authorization, raised by the hospital's TPA desk while the patient is still admitted, and
 * typically carries no billed line items yet — that stage is simply skipped. A reimbursement claim
 * arrives after discharge with the full document set and bill, so every stage runs.</p>
 *
 * <h2>What is decided here, and what is not</h2>
 * <p>Only the coverage stage can terminally block a claim, and it does so on deterministic rules
 * over the declaration record — never on a model's judgment. The document-consistency and line-item
 * stages are AI-assisted and explicitly advisory: their findings are surfaced for the reviewer and
 * never halt the assessment. The claim decision itself remains with the insurer's doctor and claims
 * teams, exactly as it does today.</p>
 */
@RestController
@RequestMapping("/api/claims")
@RequireDept(roles = MedRole.STAFF, required = false)
@Tag(name = "Claim Assessment", description = "Staff-only end-to-end claim assessment: coverage, "
        + "document consistency and line items in one call. Only the deterministic coverage stage "
        + "can block a claim.")
public class ClaimAssessmentController {

    private final ClaimProcessingService claimProcessingService;

    /**
     * Creates the controller.
     *
     * @param claimProcessingService the assessment orchestrator, must not be {@code null}
     * @throws NullPointerException if {@code claimProcessingService} is {@code null}
     */
    public ClaimAssessmentController(ClaimProcessingService claimProcessingService) {
        this.claimProcessingService =
                Objects.requireNonNull(claimProcessingService, "claimProcessingService must not be null");
    }

    /**
     * Assesses one claim through every applicable stage.
     *
     * @param request the claim and its supporting documents and line items
     * @return whichever stage results ran, plus the stage that halted the assessment if one did
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a missing or invalid request
     */
    @MedAudit(action = "CLAIM_ASSESSMENT", resourceType = "CLAIM")
    @RateLimit(rate = 5, durationSeconds = 1)
    @PostMapping("/assess")
    @Operation(summary = "Assess a claim end to end",
            description = "Runs coverage, document consistency and line-item assessment in order, "
                    + "short-circuiting if coverage terminally blocks the claim. Stages with no "
                    + "supplied input are skipped rather than failed. Staff only.")
    public ApiResult<ClaimAssessmentResponse> assess(
            @RequestBody @Nullable ClaimAssessmentRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "request body must not be empty");
        }
        request.validate();
        return ApiResult.ok(claimProcessingService.assess(request));
    }
}
