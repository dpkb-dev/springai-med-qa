package com.med.qa.controller.dto;

import com.med.qa.domain.enums.ClaimType;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

/**
 * The combined result of a claim assessment, carrying whichever stages actually ran.
 *
 * <p>Stages are sequential and short-circuiting, so a {@code null} stage result is meaningful rather
 * than merely absent: it means the assessment stopped before reaching that stage. {@link #stoppedAt}
 * names the stage that halted it, so a reviewer can see at a glance why the later sections are
 * empty.</p>
 *
 * @param claimId              the caller's claim reference, echoed back
 * @param claimType            cashless or reimbursement
 * @param eligibility          the pre-existing-disease coverage verdict, always present — this stage
 *                             always runs first
 * @param consistency          document contradiction findings, or {@code null} if the stage was
 *                             skipped (no documents supplied) or never reached
 * @param lineItems            per-item reimbursability assessment, or {@code null} if the stage was
 *                             skipped (no items supplied) or never reached
 * @param proceedToNextStage   whether the claim may progress — {@code false} when eligibility
 *                             terminally blocked it
 * @param stoppedAt            the stage that halted the assessment, or {@code null} when every
 *                             applicable stage ran
 * @param reviewerSummary      a one-line summary for the human reviewer opening this claim
 */
public record ClaimAssessmentResponse(
        @Nullable String claimId,
        ClaimType claimType,
        PolicyEligibilityResponse eligibility,
        @Nullable ClaimConsistencyResponse consistency,
        @Nullable ClaimEligibilityResponse lineItems,
        boolean proceedToNextStage,
        @Nullable @Schema(example = "ELIGIBILITY") String stoppedAt,
        String reviewerSummary) {

    /** Stage name reported in {@link #stoppedAt} when PED eligibility terminally blocked the claim. */
    public static final String STAGE_ELIGIBILITY = "ELIGIBILITY";
}
