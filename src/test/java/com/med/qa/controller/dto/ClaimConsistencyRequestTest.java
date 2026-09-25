package com.med.qa.controller.dto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.common.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClaimConsistencyRequest#validate()}, including the per-document validation it
 * delegates to on {@link ClaimDocument}. Plain JUnit, no Spring context.
 */
class ClaimConsistencyRequestTest {

    private static final ClaimDocument VALID_DOC_1 =
            new ClaimDocument("ADMISSION_FORM", "Patient: Rohan Sharma.");

    private static final ClaimDocument VALID_DOC_2 =
            new ClaimDocument("DISCHARGE_SUMMARY", "Patient: Rohan Sharma. Discharged.");

    // ------------------------------------------------------------------
    // A genuinely valid request
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a request with exactly two valid documents does not throw (the minimum boundary)")
    void exactlyTwoValidDocumentsDoesNotThrow() {
        ClaimConsistencyRequest request =
                new ClaimConsistencyRequest("claim-1", List.of(VALID_DOC_1, VALID_DOC_2));

        assertDoesNotThrow(request::validate);
    }

    @Test
    @DisplayName("more than two valid documents also does not throw")
    void moreThanTwoValidDocumentsDoesNotThrow() {
        ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-1",
                List.of(VALID_DOC_1, VALID_DOC_2, new ClaimDocument("LAB_REPORT", "Blood test results.")));

        assertDoesNotThrow(request::validate);
    }

    @Test
    @DisplayName("claimId is genuinely optional and may be null")
    void claimIdMayBeNull() {
        ClaimConsistencyRequest request = new ClaimConsistencyRequest(null, List.of(VALID_DOC_1, VALID_DOC_2));

        assertDoesNotThrow(request::validate);
    }

    // ------------------------------------------------------------------
    // The document-count rule itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a null documents list is rejected")
    void nullDocumentsRejected() {
        ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-1", null);

        BizException ex = assertThrows(BizException.class, request::validate);
        assertTrue(ex.getMessage().contains("at least two documents"));
    }

    @Test
    @DisplayName("an empty documents list is rejected")
    void emptyDocumentsRejected() {
        ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-1", List.of());

        assertThrows(BizException.class, request::validate);
    }

    @Test
    @DisplayName("exactly one document is rejected -- consistency needs something to compare against")
    void singleDocumentRejected() {
        ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-1", List.of(VALID_DOC_1));

        BizException ex = assertThrows(BizException.class, request::validate);
        assertTrue(ex.getMessage().contains("at least two documents"));
    }

    // ------------------------------------------------------------------
    // Per-document validation, delegated to ClaimDocument.validate()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank documentType is rejected")
    void blankDocumentTypeRejected() {
        ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-1",
                List.of(new ClaimDocument("  ", "some text"), VALID_DOC_2));

        assertThrows(BizException.class, request::validate);
    }

    @Test
    @DisplayName("blank document text is rejected")
    void blankDocumentTextRejected() {
        ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-1",
                List.of(new ClaimDocument("ADMISSION_FORM", "   "), VALID_DOC_2));

        assertThrows(BizException.class, request::validate);
    }

    @Test
    @DisplayName("a null document text is rejected")
    void nullDocumentTextRejected() {
        ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-1",
                List.of(new ClaimDocument("ADMISSION_FORM", null), VALID_DOC_2));

        assertThrows(BizException.class, request::validate);
    }
}
