package com.med.qa.service.claims;

import com.med.qa.controller.dto.ClaimAssessmentRequest;
import com.med.qa.controller.dto.ClaimAssessmentResponse;
import com.med.qa.controller.dto.ClaimConsistencyRequest;
import com.med.qa.controller.dto.ClaimConsistencyResponse;
import com.med.qa.controller.dto.ClaimEligibilityRequest;
import com.med.qa.controller.dto.ClaimEligibilityResponse;
import com.med.qa.controller.dto.PolicyEligibilityRequest;
import com.med.qa.controller.dto.PolicyEligibilityResponse;
import com.med.qa.service.underwriting.PolicyEligibilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Runs a submitted claim through the three assessment stages in order, stopping as soon as one of
 * them terminally blocks it.
 *
 * <h2>Why an orchestrator rather than three separate calls</h2>
 * <p>The stages are genuinely sequential, not independent. If the claimed condition is not covered
 * under the policy's pre-existing-disease terms, there is nothing to gain from extracting facts out
 * of the submitted documents or costing the billed line items — both of which cost real money in
 * model calls. Short-circuiting is the point: the cheapest, most decisive check runs first.</p>
 *
 * <h2>Stage order, and why it is this order</h2>
 * <ol>
 *   <li><b>PED eligibility</b> — deterministic, no model, no network beyond one indexed row read.
 *       Also the only stage that can terminally reject, so it belongs first.</li>
 *   <li><b>Document consistency</b> — one model call per document, but cheap extraction only, and
 *       its findings are advisory rather than blocking.</li>
 *   <li><b>Line-item assessment</b> — the most expensive stage: a RAG retrieval plus a model call
 *       per line item.</li>
 * </ol>
 *
 * <h2>What this class does not decide</h2>
 * <p>Nothing. It calls three services and assembles their outputs. Every verdict belongs to the
 * service that produced it, and the human reviewer remains accountable for the claim decision
 * itself. Stages 2 and 3 are explicitly advisory — only stage 1 can halt the assessment, and it does
 * so on deterministic rules rather than a model's judgment.</p>
 *
 * <h2>Cashless and reimbursement</h2>
 * <p>Both claim types run the identical assessment. They differ in when it happens — a cashless
 * claim is assessed at pre-authorization while the patient is still admitted, a reimbursement claim
 * after discharge once documents are submitted — and in which sections are populated: a cashless
 * pre-authorization typically carries no billed line items yet, so that stage is simply skipped
 * rather than failed.</p>
 */
@Service
public class ClaimProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ClaimProcessingService.class);

    private final PolicyEligibilityService policyEligibilityService;

    private final ClaimConsistencyService claimConsistencyService;

    private final ClaimEligibilityService claimEligibilityService;

    /**
     * Creates the orchestrator.
     *
     * @param policyEligibilityService stage 1, PED coverage, must not be {@code null}
     * @param claimConsistencyService  stage 2, document contradictions, must not be {@code null}
     * @param claimEligibilityService  stage 3, line-item caps, must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public ClaimProcessingService(
            PolicyEligibilityService policyEligibilityService,
            ClaimConsistencyService claimConsistencyService,
            ClaimEligibilityService claimEligibilityService) {
        this.policyEligibilityService =
                Objects.requireNonNull(policyEligibilityService, "policyEligibilityService must not be null");
        this.claimConsistencyService =
                Objects.requireNonNull(claimConsistencyService, "claimConsistencyService must not be null");
        this.claimEligibilityService =
                Objects.requireNonNull(claimEligibilityService, "claimEligibilityService must not be null");
    }

    /**
     * Assesses one claim through every applicable stage.
     *
     * @param request the claim, must already be validated
     * @return whichever stage results actually ran, plus the stage that stopped the assessment if
     *         one did
     */
    public ClaimAssessmentResponse assess(ClaimAssessmentRequest request) {

        // Stage 1 - deterministic PED coverage. The only stage that can terminally block.
        PolicyEligibilityResponse eligibility = policyEligibilityService.check(
                new PolicyEligibilityRequest(request.policyId(), request.conditionCode(),
                        request.claimDate(), request.pedRelated()));

        if (eligibility.terminalRejection()) {
            log.info("claim {} halted at eligibility: {}", request.claimId(), eligibility.verdict());
            return new ClaimAssessmentResponse(
                    request.claimId(), request.claimType(), eligibility, null, null, false,
                    ClaimAssessmentResponse.STAGE_ELIGIBILITY,
                    "Assessment stopped at the coverage check: " + eligibility.rationale());
        }

        // Stage 2 - document contradictions. Advisory: findings never halt the assessment, they are
        // flagged for the reviewer. Skipped when the caller supplied nothing to compare.
        ClaimConsistencyResponse consistency = null;
        if (request.documents() != null && request.documents().size() >= 2) {
            consistency = claimConsistencyService.check(
                    new ClaimConsistencyRequest(request.claimId(), request.documents()));
        }

        // Stage 3 - line-item caps. Skipped when billing has not happened yet, which is the normal
        // state of a cashless pre-authorization.
        ClaimEligibilityResponse lineItems = null;
        if (request.items() != null && !request.items().isEmpty()) {
            lineItems = claimEligibilityService.assess(
                    new ClaimEligibilityRequest(request.tenantId(), request.deptId(), null,
                            request.claimId(), request.sumInsured(), request.items()));
        }

        return new ClaimAssessmentResponse(
                request.claimId(), request.claimType(), eligibility, consistency, lineItems, true,
                null, buildSummary(eligibility, consistency, lineItems));
    }

    private static String buildSummary(
            PolicyEligibilityResponse eligibility,
            ClaimConsistencyResponse consistency,
            ClaimEligibilityResponse lineItems) {

        StringBuilder summary = new StringBuilder("Coverage: ")
                .append(eligibility.verdict()).append('.');

        if (consistency == null) {
            summary.append(" Document consistency not checked.");
        } else if (consistency.findings().isEmpty()) {
            summary.append(" No document inconsistencies found.");
        } else {
            summary.append(' ').append(consistency.findings().size())
                    .append(" document inconsistency finding(s) to review.");
        }

        if (lineItems == null) {
            summary.append(" No billed line items submitted yet.");
        } else {
            long needingReview = lineItems.assessments().stream()
                    .filter(assessment -> !"LIKELY_COVERED".equals(assessment.verdict()))
                    .count();
            summary.append(' ').append(lineItems.assessments().size())
                    .append(" line item(s) assessed, ").append(needingReview)
                    .append(" needing reviewer attention.");
        }

        return summary.toString();
    }
}
