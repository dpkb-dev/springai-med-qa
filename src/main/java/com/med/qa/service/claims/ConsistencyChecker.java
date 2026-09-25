package com.med.qa.service.claims;

import com.med.qa.controller.dto.ConsistencyFinding;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, model-free comparison logic that turns per-document {@link DocumentFacts} into a
 * list of {@link ConsistencyFinding}s.
 *
 * <p>This class contains zero calls to any AI model — every check is a plain, testable comparison
 * over facts the model already extracted. The same set of {@link DocumentFacts} always produces the
 * exact same findings, regardless of which model or model run produced those facts.</p>
 *
 * <p>Two checks only, matching exactly what this feature is scoped to cover:</p>
 * <ol>
 *   <li><b>Name mismatch</b> — do all documents that state a patient name agree with each other?</li>
 *   <li><b>Date logic</b> — for any document that states both dates, is the admission date on or
 *       before the discharge date? (Admission after discharge is medically impossible.)</li>
 * </ol>
 * <p>A document that simply does not mention a name or a date is not flagged — only actual
 * disagreement or actual impossible ordering is reported.</p>
 */
final class ConsistencyChecker {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMMM yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"));

    private ConsistencyChecker() {
    }

    /**
     * One submitted document paired with the facts extracted from it.
     *
     * @param documentType the document's type label, echoed into any finding it contributes to
     * @param facts        the facts extracted from this document
     */
    record DocumentWithFacts(String documentType, DocumentFacts facts) {
    }

    /**
     * Compares facts across every document in the bundle and returns every inconsistency found.
     *
     * @param documents the documents and their extracted facts, must contain at least two
     * @return every flagged finding; empty when nothing inconsistent was detected
     */
    static List<ConsistencyFinding> check(List<DocumentWithFacts> documents) {
        List<ConsistencyFinding> findings = new ArrayList<>();

        ConsistencyFinding nameFinding = checkPatientNames(documents);
        if (nameFinding != null) {
            findings.add(nameFinding);
        }

        for (DocumentWithFacts doc : documents) {
            ConsistencyFinding dateFinding = checkDateOrder(doc);
            if (dateFinding != null) {
                findings.add(dateFinding);
            }
        }

        return findings;
    }

    /**
     * Checks whether every document that states a patient name states the same one.
     *
     * @return a finding if two or more different names were found, otherwise {@code null}
     */
    private static ConsistencyFinding checkPatientNames(List<DocumentWithFacts> documents) {
        String firstName = null;
        String firstDocType = null;
        List<String> mismatchedDocTypes = new ArrayList<>();
        List<String> mismatchedNames = new ArrayList<>();

        for (DocumentWithFacts doc : documents) {
            String name = doc.facts().patientName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String normalized = name.trim().toUpperCase();

            if (firstName == null) {
                firstName = normalized;
                firstDocType = doc.documentType();
                continue;
            }
            if (!normalized.equals(firstName)) {
                mismatchedDocTypes.add(doc.documentType());
                mismatchedNames.add(normalized);
            }
        }

        if (mismatchedDocTypes.isEmpty()) {
            return null;
        }

        List<String> involved = new ArrayList<>();
        involved.add(firstDocType);
        involved.addAll(mismatchedDocTypes);

        String description = firstDocType + " states '" + firstName + "', but "
                + String.join(", ", mismatchedDocTypes) + " states '"
                + String.join("', '", mismatchedNames) + "'.";

        return new ConsistencyFinding("PATIENT_NAME_MISMATCH", "HIGH", description, involved);
    }

    /**
     * Checks whether a single document's own admission date is after its own discharge date.
     *
     * @return a finding if the dates are out of order, otherwise {@code null} (including when either
     *         date is missing or unparseable — this method only flags a confirmed logical impossibility)
     */
    private static ConsistencyFinding checkDateOrder(DocumentWithFacts doc) {
        LocalDate admission = tryParse(doc.facts().admissionDate());
        LocalDate discharge = tryParse(doc.facts().dischargeDate());

        if (admission == null || discharge == null) {
            return null;
        }
        if (admission.isAfter(discharge)) {
            String description = doc.documentType() + " states an admission date (" + admission
                    + ") after its discharge date (" + discharge + "), which is not possible.";
            return new ConsistencyFinding("DATE_LOGIC_ERROR", "HIGH", description, List.of(doc.documentType()));
        }
        return null;
    }

    private static LocalDate tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw.trim(), format);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return null;
    }
}
