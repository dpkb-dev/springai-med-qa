package com.med.qa.service.claims;

import org.springframework.lang.Nullable;

/**
 * Facts extracted from a single claim document by the model — never a cross-document comparison and
 * never a judgment about consistency. Comparison across documents happens separately, deterministically,
 * in {@link ConsistencyChecker}.
 *
 * <p>Dates are extracted as plain strings, not parsed by the model into any date type — Java parses
 * them explicitly in {@link ConsistencyChecker}, so a date the model formats unexpectedly fails safe
 * (reported as unparseable) rather than being silently misinterpreted.</p>
 *
 * @param patientName    the patient's name as it appears in this document, or {@code null} if not
 *                       stated
 * @param admissionDate  the admission date as it literally appears in the text (e.g.
 *                       {@code "2026-03-14"} or {@code "14 March 2026"}), or {@code null} if not stated
 * @param dischargeDate  the discharge date as it literally appears in the text, or {@code null} if not
 *                       stated
 * @param extractionNote a short note from the model about anything unclear or ambiguous while reading
 *                       this specific document
 */
public record DocumentFacts(
        @Nullable String patientName,
        @Nullable String admissionDate,
        @Nullable String dischargeDate,
        String extractionNote) {
}
