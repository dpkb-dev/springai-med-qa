package com.med.qa.service.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.controller.dto.ConsistencyFinding;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConsistencyChecker}, the deterministic, model-free comparison logic behind
 * the claim document consistency check.
 *
 * <p>Every test here runs with zero AI model calls, zero Spring context, and zero mocking — the same
 * benefit {@link CapCalculatorTest} gets from {@link CapCalculator} being pure Java.</p>
 */
class ConsistencyCheckerTest {

    // ------------------------------------------------------------------
    // Patient name checks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no finding when no document states a patient name")
    void noNamesStatedNoFinding() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("ADMISSION_FORM", null, null, null),
                doc("DISCHARGE_SUMMARY", null, null, null));

        assertTrue(ConsistencyChecker.check(documents).isEmpty());
    }

    @Test
    @DisplayName("no finding when all documents agree on the name, ignoring case and whitespace")
    void matchingNamesNoFinding() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("ADMISSION_FORM", "Rohan Sharma", null, null),
                doc("DISCHARGE_SUMMARY", "  rohan sharma  ", null, null));

        assertTrue(ConsistencyChecker.check(documents).isEmpty());
    }

    @Test
    @DisplayName("a differing patient name is flagged HIGH severity, naming both documents")
    void differingNameIsFlagged() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("ADMISSION_FORM", "Rohan Sharma", null, null),
                doc("DISCHARGE_SUMMARY", "Rohit Sharma", null, null));

        List<ConsistencyFinding> findings = ConsistencyChecker.check(documents);

        assertEquals(1, findings.size());
        ConsistencyFinding finding = findings.get(0);
        assertEquals("PATIENT_NAME_MISMATCH", finding.type());
        assertEquals("HIGH", finding.severity());
        assertEquals(List.of("ADMISSION_FORM", "DISCHARGE_SUMMARY"), finding.involvedDocuments());
    }

    @Test
    @DisplayName("with three documents, only the differing one is named as the mismatch")
    void onlyDifferingDocumentIsNamedAmongThree() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("ADMISSION_FORM", "Rohan Sharma", null, null),
                doc("LAB_REPORT", "Rohan Sharma", null, null),
                doc("DISCHARGE_SUMMARY", "Rohit Sharma", null, null));

        List<ConsistencyFinding> findings = ConsistencyChecker.check(documents);

        assertEquals(1, findings.size());
        assertEquals(List.of("ADMISSION_FORM", "DISCHARGE_SUMMARY"), findings.get(0).involvedDocuments());
    }

    // ------------------------------------------------------------------
    // Date order checks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no finding when a document states no dates at all")
    void noDatesNoFinding() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("ADMISSION_FORM", "Rohan Sharma", null, null),
                doc("DISCHARGE_SUMMARY", "Rohan Sharma", null, null));

        assertTrue(ConsistencyChecker.check(documents).isEmpty());
    }

    @Test
    @DisplayName("no finding when only one of the two dates is stated")
    void onlyOneDateStatedNoFinding() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("ADMISSION_FORM", "Rohan Sharma", "2026-03-10", null),
                doc("DISCHARGE_SUMMARY", "Rohan Sharma", null, null));

        assertTrue(ConsistencyChecker.check(documents).isEmpty());
    }

    @Test
    @DisplayName("admission on the same day as discharge is valid (e.g. day surgery), not flagged")
    void sameDayAdmissionAndDischargeIsValid() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("DAY_SURGERY_RECORD", "Rohan Sharma", "2026-03-10", "2026-03-10"));

        assertTrue(ConsistencyChecker.check(documents).isEmpty());
    }

    @Test
    @DisplayName("admission before discharge is valid, not flagged")
    void admissionBeforeDischargeIsValid() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("ADMISSION_FORM", "Rohan Sharma", null, null),
                doc("DISCHARGE_SUMMARY", "Rohan Sharma", "2026-03-08", "2026-03-10"));

        assertTrue(ConsistencyChecker.check(documents).isEmpty());
    }

    @Test
    @DisplayName("admission after discharge within one document is flagged HIGH, scoped to that document")
    void admissionAfterDischargeIsFlagged() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("ADMISSION_FORM", "Rohan Sharma", null, null),
                doc("DISCHARGE_SUMMARY", "Rohan Sharma", "2026-03-10", "2026-03-08"));

        List<ConsistencyFinding> findings = ConsistencyChecker.check(documents);

        assertEquals(1, findings.size());
        ConsistencyFinding finding = findings.get(0);
        assertEquals("DATE_LOGIC_ERROR", finding.type());
        assertEquals("HIGH", finding.severity());
        assertEquals(List.of("DISCHARGE_SUMMARY"), finding.involvedDocuments());
    }

    @Test
    @DisplayName("an unparseable date is silently skipped, not flagged (documents the known trade-off)")
    void unparseableDateIsSilentlySkipped() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("DISCHARGE_SUMMARY", "Rohan Sharma", "March 10th, 2026", "2026-03-08"));

        // "March 10th, 2026" matches none of the three supported formats, so admission fails to parse
        // and the whole date-order check for this document is skipped entirely.
        assertTrue(ConsistencyChecker.check(documents).isEmpty());
    }

    @Test
    @DisplayName("supports the alternate 'd MMMM yyyy' and 'dd/MM/yyyy' formats, not just ISO")
    void supportsAlternateDateFormats() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("DISCHARGE_SUMMARY", "Rohan Sharma", "10 March 2026", "08/03/2026"));

        // 08/03/2026 parses as dd/MM/yyyy = 8 March 2026, which is BEFORE the 10 March admission --
        // so this should flag DATE_LOGIC_ERROR, proving both alternate formats parsed correctly.
        List<ConsistencyFinding> findings = ConsistencyChecker.check(documents);
        assertEquals(1, findings.size());
        assertEquals("DATE_LOGIC_ERROR", findings.get(0).type());
    }

    // ------------------------------------------------------------------
    // Combined
    // ------------------------------------------------------------------

    @Test
    @DisplayName("both a name mismatch and a date logic error are reported together when both occur")
    void bothFindingTypesReportedTogether() {
        List<ConsistencyChecker.DocumentWithFacts> documents = List.of(
                doc("ADMISSION_FORM", "Rohan Sharma", null, null),
                doc("DISCHARGE_SUMMARY", "Rohit Sharma", "2026-03-10", "2026-03-08"));

        List<ConsistencyFinding> findings = ConsistencyChecker.check(documents);

        assertEquals(2, findings.size());
        assertTrue(findings.stream().anyMatch(f -> f.type().equals("PATIENT_NAME_MISMATCH")));
        assertTrue(findings.stream().anyMatch(f -> f.type().equals("DATE_LOGIC_ERROR")));
    }

    // ------------------------------------------------------------------
    // Test helper
    // ------------------------------------------------------------------

    private static ConsistencyChecker.DocumentWithFacts doc(
            String documentType, String patientName, String admissionDate, String dischargeDate) {
        return new ConsistencyChecker.DocumentWithFacts(
                documentType, new DocumentFacts(patientName, admissionDate, dischargeDate, ""));
    }
}
