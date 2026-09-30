package com.med.qa.controller.dto;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.enums.ClaimType;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One claim submitted for assessment, carrying everything the three assessment stages need.
 *
 * <p>A single request rather than three separate calls, because the stages are sequential and
 * short-circuiting: if the condition is not covered under the policy's pre-existing-disease terms,
 * there is no point extracting facts from the documents or costing the line items.</p>
 *
 * @param claimId       caller-supplied claim reference, echoed through the whole assessment
 * @param claimType     cashless or reimbursement — same assessment, different entry point
 * @param policyId      the policy being claimed against, must not be blank
 * @param tenantId      insurer/tenant scope for policy-document retrieval, must not be blank
 * @param deptId        line-of-business scope for policy-document retrieval, must not be blank
 * @param conditionCode the claimed condition's code, must not be blank
 * @param claimDate     date of hospitalisation / claim, must not be {@code null}
 * @param pedRelated    whether the insurer's doctor team assessed this claim as related to a
 *                      pre-existing disease, from their review of the pre-authorization form and
 *                      supporting records. Never inferred by this application.
 * @param sumInsured    the policy's sum insured, needed for percentage-based line-item caps
 * @param documents     the submitted claim documents, checked for internal contradictions; at least
 *                      two are needed for a meaningful cross-document comparison, or none to skip
 *                      that stage
 * @param items         the billed line items to cost against policy clauses, may be empty when the
 *                      claim has not yet reached billing (a cashless pre-authorization, typically)
 */
public record ClaimAssessmentRequest(
        @Nullable @Schema(example = "CLM-2026-0001") String claimId,
        @Schema(example = "CASHLESS") ClaimType claimType,
        @Schema(example = "POL-1001") String policyId,
        @Schema(example = "insurerone") String tenantId,
        @Schema(example = "healthclaims") String deptId,
        @Schema(example = "DVT") String conditionCode,
        @Schema(example = "2026-06-10") LocalDate claimDate,
        @Schema(example = "true") boolean pedRelated,
        @Schema(example = "300000") BigDecimal sumInsured,
        @Nullable List<ClaimDocument> documents,
        @Nullable List<ClaimLineItem> items) {

    /**
     * Validates the request and every nested document and line item.
     *
     * @throws BizException {@link ErrorCode#BAD_REQUEST} if a required field is missing or any
     *                       nested element is invalid
     */
    public void validate() {
        if (claimType == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "claimType must not be null");
        }
        if (policyId == null || policyId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "policyId must not be blank");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "tenantId must not be blank");
        }
        if (deptId == null || deptId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "deptId must not be blank");
        }
        if (conditionCode == null || conditionCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "conditionCode must not be blank");
        }
        if (claimDate == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "claimDate must not be null");
        }
        if (sumInsured == null || sumInsured.signum() <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sumInsured must be present and positive");
        }
        if (documents != null && documents.size() == 1) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "supply at least two documents for a consistency check, or none to skip it");
        }
        if (documents != null) {
            for (ClaimDocument document : documents) {
                try {
                    document.validate();
                } catch (IllegalArgumentException ex) {
                    throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
                }
            }
        }
        if (items != null) {
            for (ClaimLineItem item : items) {
                try {
                    item.validate();
                } catch (IllegalArgumentException ex) {
                    throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
                }
            }
        }
    }
}
