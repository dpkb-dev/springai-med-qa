package com.med.qa.controller.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

/**
 * The eligibility assessment for one claimed line item — advisory only, for a human claims
 * reviewer, never a final coverage decision.
 *
 * @param itemDescription the original claimed item description, echoed back for the reviewer
 * @param claimedAmount   the original claimed amount, echoed back for the reviewer
 * @param verdict         {@code LIKELY_COVERED}, {@code LIKELY_EXCLUDED}, or
 *                        {@code NEEDS_MANUAL_REVIEW}
 * @param rationale       a short explanation grounded in the matched policy clause, if any
 * @param matchedClause   the actual retrieved policy clause text this verdict was based on, or
 *                        {@code null} when no relevant clause was found at all (always forces
 *                        {@code NEEDS_MANUAL_REVIEW})
 */
public record ClaimItemAssessment(
        String itemDescription,
        BigDecimal claimedAmount,
        String verdict,
        String rationale,
        @Nullable String matchedClause) {
}
