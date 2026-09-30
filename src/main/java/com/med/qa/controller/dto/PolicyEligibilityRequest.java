package com.med.qa.controller.dto;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * A request to check whether a claimed condition is covered under a policy's pre-existing-disease
 * (PED) terms, evaluated at claim pre-authorization.
 *
 * @param policyId      the policy being claimed against, must not be blank
 * @param conditionCode the claimed condition's code, must not be blank
 * @param claimDate     the date of the claim / hospitalisation, must not be {@code null}
 * @param pedRelated    whether the insurer's doctor team determined this claim to be related to a
 *                      pre-existing disease. This is a medical judgment made by a clinician from the
 *                      health reports, consultations, prescriptions and treatment history — it
 *                      arrives here already made, and is never inferred by this application.
 */
public record PolicyEligibilityRequest(
        @Schema(description = "policy identifier", example = "POL-1001") String policyId,
        @Schema(description = "claimed condition code", example = "DIABETES") String conditionCode,
        @Schema(description = "date of claim / hospitalisation", example = "2025-06-10") LocalDate claimDate,
        @Schema(description = "whether the doctor team assessed this claim as PED-related",
                example = "true") boolean pedRelated) {

    /**
     * Validates the request.
     *
     * @throws BizException {@link ErrorCode#BAD_REQUEST} if a required field is missing or blank
     */
    public void validate() {
        if (policyId == null || policyId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "policyId must not be blank");
        }
        if (conditionCode == null || conditionCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "conditionCode must not be blank");
        }
        if (claimDate == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "claimDate must not be null");
        }
    }
}
