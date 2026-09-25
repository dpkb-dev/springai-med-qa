package com.med.qa.service.claims;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

/**
 * Facts extracted from a policy clause by the model — never a coverage decision, and never a
 * calculation.
 *
 * <p>This is deliberately narrow: the model's only job is turning unstructured clause text into a
 * few structured fields, a task LLMs are genuinely reliable at. The actual monetary arithmetic and
 * the final verdict are computed separately, deterministically, in {@link CapCalculator} — never here
 * and never in the model.</p>
 *
 * @param applicability  one of {@code COVERED_NO_LIMIT}, {@code COVERED_WITH_CAP}, {@code EXCLUDED},
 *                       or {@code UNCLEAR}
 * @param capBasis       when {@code applicability} is {@code COVERED_WITH_CAP}, one of
 *                       {@code PERCENT_OF_SUM_INSURED_PER_DAY}, {@code PERCENT_OF_SUM_INSURED_FLAT},
 *                       or {@code FLAT_AMOUNT}; {@code null} otherwise
 * @param capValue       the raw numeric value stated in the clause (e.g. {@code 1} for "1%", or a
 *                       flat currency figure); {@code null} when {@code capBasis} is {@code null}
 * @param clauseSummary  a short, plain-language summary of what the clause says about this item, for
 *                       display to the human reviewer
 */
public record ClauseAnalysis(
        String applicability,
        @Nullable String capBasis,
        @Nullable BigDecimal capValue,
        String clauseSummary) {
}
