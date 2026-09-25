package com.med.qa.service.claims;

import java.math.BigDecimal;

/**
 * Deterministic, model-free business logic that turns a {@link ClauseAnalysis} (facts extracted from
 * policy text by the model) into an actual monetary cap and a final verdict.
 *
 * <p>This class contains zero calls to any AI model. Every branch is a plain, testable Java
 * conditional over real numbers — the same claim, the same clause, and the same sum insured will
 * always produce the exact same cap and verdict, regardless of which LLM (or which run of the same
 * LLM) produced the {@link ClauseAnalysis} facts. Financial arithmetic and the coverage decision
 * belong here, never in a prompt.</p>
 */
final class CapCalculator {

    private CapCalculator() {
    }

    /**
     * Computes the final, deterministic outcome for one claimed line item.
     *
     * @param analysis      the model-extracted facts about the matched clause, must not be {@code null}
     * @param sumInsured    the policy's total sum insured, must not be {@code null}
     * @param claimedAmount the claimed amount for this line item, must not be {@code null}
     * @param days          the number of days this item covers; required only when
     *                      {@code analysis.capBasis()} is {@code PERCENT_OF_SUM_INSURED_PER_DAY}, may
     *                      be {@code null} otherwise
     * @return the deterministic verdict, the calculated cap (if any), and a plain-language note
     *         describing the calculation performed, for display to the reviewer
     */
    static CapOutcome evaluate(
            ClauseAnalysis analysis, BigDecimal sumInsured, BigDecimal claimedAmount, Integer days) {

        String applicability = analysis.applicability() == null ? "UNCLEAR" : analysis.applicability();

        return switch (applicability) {
            case "EXCLUDED" -> new CapOutcome("LIKELY_EXCLUDED", null,
                    "The matched clause excludes this item; no cap calculation applies.");
            case "COVERED_NO_LIMIT" -> new CapOutcome("LIKELY_COVERED", null,
                    "The matched clause covers this item with no stated monetary limit.");
            case "COVERED_WITH_CAP" -> evaluateCappedItem(analysis, sumInsured, claimedAmount, days);
            default -> new CapOutcome("NEEDS_MANUAL_REVIEW", null,
                    "The matched clause's applicability to this item could not be determined with "
                            + "confidence and requires manual review.");
        };
    }

    private static CapOutcome evaluateCappedItem(
            ClauseAnalysis analysis, BigDecimal sumInsured, BigDecimal claimedAmount, Integer days) {

        String basis = analysis.capBasis();
        BigDecimal capValue = analysis.capValue();
        if (basis == null || capValue == null) {
            return new CapOutcome("NEEDS_MANUAL_REVIEW", null,
                    "The clause states a cap applies, but its basis or value could not be extracted "
                            + "reliably from the clause text; requires manual review.");
        }

        BigDecimal cap = calculateCap(basis, capValue, sumInsured, days);
        if (cap == null) {
            String reason = "PERCENT_OF_SUM_INSURED_PER_DAY".equals(basis)
                    ? "the clause's cap is per day, but the number of days for this item was not provided"
                    : "the cap basis reported was not one this calculator recognizes ('" + basis + "')";
            return new CapOutcome("NEEDS_MANUAL_REVIEW", null,
                    "Cannot calculate the applicable cap because " + reason + "; requires manual review.");
        }

        if (claimedAmount.compareTo(cap) <= 0) {
            return new CapOutcome("LIKELY_COVERED", cap,
                    "Calculated cap is %s; claimed amount %s is within this cap.".formatted(cap, claimedAmount));
        }
        BigDecimal excess = claimedAmount.subtract(cap);
        return new CapOutcome("NEEDS_MANUAL_REVIEW", cap,
                "Calculated cap is %s; claimed amount %s exceeds the cap by %s."
                        .formatted(cap, claimedAmount, excess));
    }

    /**
     * Performs the actual cap arithmetic for a recognized basis, or returns {@code null} when the
     * basis is unrecognized or its required inputs are missing.
     */
    private static BigDecimal calculateCap(String basis, BigDecimal capValue, BigDecimal sumInsured, Integer days) {
        return switch (basis) {
            case "PERCENT_OF_SUM_INSURED_PER_DAY" -> {
                if (days == null || days <= 0) {
                    yield null;
                }
                yield sumInsured
                        .multiply(capValue)
                        .divide(BigDecimal.valueOf(100), 10, ClaimCapPolicy.CAP_ROUNDING)
                        .multiply(BigDecimal.valueOf(days))
                        .setScale(ClaimCapPolicy.CAP_SCALE, ClaimCapPolicy.CAP_ROUNDING);
            }
            case "PERCENT_OF_SUM_INSURED_FLAT" -> sumInsured
                    .multiply(capValue)
                    .divide(BigDecimal.valueOf(100), ClaimCapPolicy.CAP_SCALE, ClaimCapPolicy.CAP_ROUNDING);
            case "FLAT_AMOUNT" -> capValue.setScale(ClaimCapPolicy.CAP_SCALE, ClaimCapPolicy.CAP_ROUNDING);
            default -> null;
        };
    }

    /**
     * The deterministic outcome of evaluating one line item against its matched clause.
     *
     * @param verdict         {@code LIKELY_COVERED}, {@code LIKELY_EXCLUDED}, or
     *                        {@code NEEDS_MANUAL_REVIEW} — decided entirely by this calculator, never
     *                        by the model
     * @param calculatedCap   the actual monetary cap computed from the clause and sum insured, or
     *                        {@code null} when no cap applies or it could not be calculated
     * @param calculationNote a plain-language explanation of the calculation performed
     */
    record CapOutcome(String verdict, BigDecimal calculatedCap, String calculationNote) {
    }
}
