package com.med.qa.controller.dto;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Turn 1 of a medication-safety consultation: the patient's question.
 *
 * <p>This request never produces a clinical answer on its own. A question about a high-risk
 * medication cannot be reviewed without the supporting reports (blueprint rule BR-1), so the
 * response to this is a request for those reports, not an opinion.</p>
 *
 * @param sessionId  the consultation session, which scopes the reports submitted in turn 2; must not
 *                   be blank
 * @param medication the medication being asked about, must not be blank
 * @param question   the patient's question in their own words, scanned for condition keywords that
 *                   change which reports are needed; must not be blank
 */
public record MedicationConsultRequest(
        @Schema(example = "consult-9f2c1a") String sessionId,
        @Schema(example = "Pradaxa") String medication,
        @Schema(example = "I have DVT in my left leg, should I continue taking Pradaxa?")
        String question) {

    /**
     * Validates the request.
     *
     * @throws BizException {@link ErrorCode#BAD_REQUEST} if a required field is missing or blank
     */
    public void validate() {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sessionId must not be blank");
        }
        if (medication == null || medication.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "medication must not be blank");
        }
        if (question == null || question.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "question must not be blank");
        }
    }
}
