package com.med.qa.controller.dto;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * The full consistency-check result for a claim's document bundle.
 *
 * <p>An empty {@code findings} list means no inconsistencies were detected — it does not mean the
 * documents are confirmed genuine; this check never makes that determination. {@code disclaimer} is
 * always populated with the same fixed advisory statement, for the same reason as
 * {@link ClaimEligibilityResponse#ADVISORY_DISCLAIMER}: this is decision support for a human
 * reviewer, never an automated finding of fraud or authenticity.</p>
 *
 * @param claimId    echoes the caller-supplied claim id, or {@code null} if none was given
 * @param findings   every flagged inconsistency; empty when none were found
 * @param disclaimer fixed advisory statement, always non-null
 */
public record ClaimConsistencyResponse(
        @Nullable String claimId, List<ConsistencyFinding> findings, String disclaimer) {

    /** The fixed advisory statement attached to every response. */
    public static final String ADVISORY_DISCLAIMER =
            "This check flags structural or logical inconsistencies (patient name and admission/"
                    + "discharge date ordering) across the submitted documents for human-reviewer "
                    + "attention only. It does not verify document authenticity and is not a fraud "
                    + "determination. An absence of findings does not confirm the documents are genuine.";
}
