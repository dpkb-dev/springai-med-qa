package com.med.qa.controller.dto;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * A reimbursement claim's line items, plus the policy scope to check them against.
 *
 * <p>{@code tenantId}/{@code deptId} identify which ingested policy corpus to search — matching the
 * same tags used when the policy's terms and exclusions were ingested via
 * {@code POST /api/rag/documents/ingest}. {@code patientId} is optional: when present, retrieval also
 * considers any patient-specific policy riders; when absent, only department-wide (shared) policy
 * documents are searched.</p>
 *
 * @param tenantId   insurer/tenant scope, must not be blank
 * @param deptId     policy line-of-business scope (e.g. a product or department code), must not be blank
 * @param patientId  optional policyholder scope for patient-specific riders
 * @param claimId    optional caller-supplied claim identifier, echoed back for the CC team's tracking
 * @param sumInsured the policy's total sum insured, in the same currency as each line item's amount —
 *                    required so the model can actually check percentage-of-sum-insured caps (e.g. a
 *                    "room rent up to 1% of sum insured per day" clause) instead of confirming coverage
 *                    in principle while silently skipping the cap check
 * @param items      the claimed line items to assess, must contain at least one item
 */
public record ClaimEligibilityRequest(
        String tenantId,
        String deptId,
        @Nullable String patientId,
        @Nullable String claimId,
        BigDecimal sumInsured,
        List<ClaimLineItem> items) {

    /**
     * Validates the request as a whole, including every line item.
     *
     * @throws BizException {@link ErrorCode#BAD_REQUEST} if a required field is missing, the item
     *                       list is empty, {@code sumInsured} is missing/non-positive, or any
     *                       individual item fails its own validation
     */
    public void validate() {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "tenantId must not be blank");
        }
        if (deptId == null || deptId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "deptId must not be blank");
        }
        if (sumInsured == null || sumInsured.signum() <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sumInsured must be present and positive");
        }
        if (items == null || items.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "items must contain at least one line item");
        }
        for (ClaimLineItem item : items) {
            try {
                item.validate();
            } catch (IllegalArgumentException ex) {
                throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
            }
        }
    }
}