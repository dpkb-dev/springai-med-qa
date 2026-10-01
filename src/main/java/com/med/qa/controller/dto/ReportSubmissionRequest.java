package com.med.qa.controller.dto;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Turn 2 of a medication-safety consultation: the reports the patient was asked for.
 *
 * @param sessionId the consultation session from turn 1, must not be blank
 * @param reports   the submitted reports, must contain at least one
 */
public record ReportSubmissionRequest(
        @Schema(example = "consult-9f2c1a") String sessionId,
        List<ClinicalReport> reports) {

    /**
     * Validates the request and every submitted report.
     *
     * @throws BizException {@link ErrorCode#BAD_REQUEST} if the session is blank, no reports were
     *                       supplied, or any report is invalid
     */
    public void validate() {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sessionId must not be blank");
        }
        if (reports == null || reports.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "at least one report must be submitted");
        }
        for (ClinicalReport report : reports) {
            try {
                report.validate();
            } catch (IllegalArgumentException ex) {
                throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
            }
        }
    }
}
