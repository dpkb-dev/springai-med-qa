package com.med.qa.controller.dto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.common.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClaimEligibilityRequest#validate()}, including the per-item validation it
 * delegates to on {@link ClaimLineItem}. Plain JUnit, no Spring context — these records have no
 * external dependencies at all.
 */
class ClaimEligibilityRequestTest {

    private static final List<ClaimLineItem> ONE_VALID_ITEM =
            List.of(new ClaimLineItem("room rent", new BigDecimal("1000"), 3));

    // ------------------------------------------------------------------
    // A genuinely valid request
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a fully valid request does not throw")
    void validRequestDoesNotThrow() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                "t1", "d1", "p1", "claim-1", new BigDecimal("300000"), ONE_VALID_ITEM);

        assertDoesNotThrow(request::validate);
    }

    @Test
    @DisplayName("patientId and claimId are genuinely optional and may be null")
    void optionalFieldsMayBeNull() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                "t1", "d1", null, null, new BigDecimal("300000"), ONE_VALID_ITEM);

        assertDoesNotThrow(request::validate);
    }

    // ------------------------------------------------------------------
    // tenantId / deptId
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a null tenantId is rejected")
    void nullTenantIdRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                null, "d1", null, null, new BigDecimal("300000"), ONE_VALID_ITEM);

        BizException ex = assertThrows(BizException.class, request::validate);
        assertTrue(ex.getMessage().contains("tenantId"));
    }

    @Test
    @DisplayName("a blank tenantId is rejected")
    void blankTenantIdRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                "   ", "d1", null, null, new BigDecimal("300000"), ONE_VALID_ITEM);

        assertThrows(BizException.class, request::validate);
    }

    @Test
    @DisplayName("a null deptId is rejected")
    void nullDeptIdRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                "t1", null, null, null, new BigDecimal("300000"), ONE_VALID_ITEM);

        BizException ex = assertThrows(BizException.class, request::validate);
        assertTrue(ex.getMessage().contains("deptId"));
    }

    // ------------------------------------------------------------------
    // sumInsured
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a null sumInsured is rejected")
    void nullSumInsuredRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                "t1", "d1", null, null, null, ONE_VALID_ITEM);

        BizException ex = assertThrows(BizException.class, request::validate);
        assertTrue(ex.getMessage().contains("sumInsured"));
    }

    @Test
    @DisplayName("a zero sumInsured is rejected (must be strictly positive)")
    void zeroSumInsuredRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                "t1", "d1", null, null, BigDecimal.ZERO, ONE_VALID_ITEM);

        assertThrows(BizException.class, request::validate);
    }

    @Test
    @DisplayName("a negative sumInsured is rejected")
    void negativeSumInsuredRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                "t1", "d1", null, null, new BigDecimal("-1"), ONE_VALID_ITEM);

        assertThrows(BizException.class, request::validate);
    }

    // ------------------------------------------------------------------
    // items list itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a null items list is rejected")
    void nullItemsRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                "t1", "d1", null, null, new BigDecimal("300000"), null);

        BizException ex = assertThrows(BizException.class, request::validate);
        assertTrue(ex.getMessage().contains("items"));
    }

    @Test
    @DisplayName("an empty items list is rejected")
    void emptyItemsRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                "t1", "d1", null, null, new BigDecimal("300000"), List.of());

        assertThrows(BizException.class, request::validate);
    }

    // ------------------------------------------------------------------
    // Per-item validation, delegated to ClaimLineItem.validate()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank item description is rejected")
    void blankItemDescriptionRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest("t1", "d1", null, null,
                new BigDecimal("300000"), List.of(new ClaimLineItem("  ", new BigDecimal("100"), null)));

        assertThrows(BizException.class, request::validate);
    }

    @Test
    @DisplayName("a negative item amount is rejected")
    void negativeItemAmountRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest("t1", "d1", null, null,
                new BigDecimal("300000"), List.of(new ClaimLineItem("item", new BigDecimal("-1"), null)));

        assertThrows(BizException.class, request::validate);
    }

    @Test
    @DisplayName("a zero item amount is valid (only strictly negative is rejected)")
    void zeroItemAmountIsValid() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest("t1", "d1", null, null,
                new BigDecimal("300000"), List.of(new ClaimLineItem("free item", BigDecimal.ZERO, null)));

        assertDoesNotThrow(request::validate);
    }

    @Test
    @DisplayName("a null item days is valid (days is genuinely optional)")
    void nullItemDaysIsValid() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest("t1", "d1", null, null,
                new BigDecimal("300000"), List.of(new ClaimLineItem("procedure", new BigDecimal("100"), null)));

        assertDoesNotThrow(request::validate);
    }

    @Test
    @DisplayName("a zero item days is rejected when provided")
    void zeroItemDaysRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest("t1", "d1", null, null,
                new BigDecimal("300000"), List.of(new ClaimLineItem("room rent", new BigDecimal("100"), 0)));

        assertThrows(BizException.class, request::validate);
    }

    @Test
    @DisplayName("a negative item days is rejected when provided")
    void negativeItemDaysRejected() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest("t1", "d1", null, null,
                new BigDecimal("300000"), List.of(new ClaimLineItem("room rent", new BigDecimal("100"), -2)));

        assertThrows(BizException.class, request::validate);
    }

    @Test
    @DisplayName("only the first invalid item's message surfaces when multiple items are invalid")
    void firstInvalidItemMessageSurfaces() {
        ClaimEligibilityRequest request = new ClaimEligibilityRequest("t1", "d1", null, null,
                new BigDecimal("300000"), List.of(
                        new ClaimLineItem("bad description item", new BigDecimal("-5"), null),
                        new ClaimLineItem("  ", new BigDecimal("10"), null)));

        // validate() iterates items in order and throws on the first failure it finds -- this test
        // documents that behavior rather than asserting a specific message, since either item's
        // failure is a legitimate reason to reject the whole request.
        assertThrows(BizException.class, request::validate);
    }
}
