package com.med.qa.controller.dto;

import java.util.List;

/**
 * One inconsistency flagged for a human reviewer — never an automated genuineness or fraud
 * determination, only a structural/logical mismatch a reviewer should look at.
 *
 * @param type               a short machine-readable category, e.g. {@code PATIENT_NAME_MISMATCH},
 *                           {@code DATE_LOGIC_ERROR}, {@code AMOUNT_MISMATCH}, or
 *                           {@code MISSING_KEY_FIELD}
 * @param severity           {@code HIGH}, {@code MEDIUM}, or {@code LOW} — for the reviewer to
 *                           prioritize, not a claim about how serious the underlying issue actually is
 * @param description        a plain-language explanation of what was found
 * @param involvedDocuments  the {@code documentType} values of the documents this finding compares
 */
public record ConsistencyFinding(
        String type, String severity, String description, List<String> involvedDocuments) {
}
