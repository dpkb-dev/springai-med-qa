package com.med.qa.controller;

import com.med.qa.audit.annotation.MedAudit;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.ratelimit.annotation.RateLimit;
import com.med.qa.common.result.ApiResult;
import com.med.qa.controller.dto.MedicationConsultRequest;
import com.med.qa.controller.dto.MedicationConsultResponse;
import com.med.qa.controller.dto.ReportSubmissionRequest;
import com.med.qa.service.medication.MedicationConsultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Two-turn medication-safety consultation.
 *
 * <h2>How the two turns work</h2>
 * <p>{@code POST /api/consult/medication} takes the patient's question and answers with the reports
 * a clinician would need to review it — never with a clinical opinion.
 * {@code POST /api/consult/reports} takes those reports and answers with the facts each one states,
 * for a clinician to read.</p>
 *
 * <h2>What this endpoint will not do</h2>
 * <p>It will not tell a patient whether to continue, stop or change a medication, and it will not
 * assess how serious a finding is. Those are diagnoses. Every response carries
 * {@code CLINICIAN_REVIEW_REQUIRED} and a disclaimer stating plainly that the findings are
 * transcribed facts rather than an assessment of what they mean.</p>
 *
 * <h2>Relationship to the claims endpoints</h2>
 * <p>None. A consultation involves no policy, no declaration record and no claim, and nothing a
 * patient says here can reach a coverage decision. The two concerns are deliberately kept apart.</p>
 */
@RestController
@RequestMapping("/api/consult")
@Tag(name = "Medication Consultation", description = "Two-turn medication-safety consultation. "
        + "Returns the reports a clinician needs, then the facts those reports state. Never a "
        + "diagnosis, a severity assessment or a treatment recommendation.")
public class MedicationConsultController {

    private final MedicationConsultService medicationConsultService;

    /**
     * Creates the controller.
     *
     * @param medicationConsultService consultation orchestration, must not be {@code null}
     * @throws NullPointerException if {@code medicationConsultService} is {@code null}
     */
    public MedicationConsultController(MedicationConsultService medicationConsultService) {
        this.medicationConsultService =
                Objects.requireNonNull(medicationConsultService, "medicationConsultService must not be null");
    }

    /**
     * Turn 1: asks which reports this medication question requires.
     *
     * @param request the patient's question
     * @return the required reports and diagnostic checklist, never a clinical answer
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a missing or invalid request
     */
    @MedAudit(action = "MEDICATION_CONSULT_REQUEST", resourceType = "CONSULTATION")
    @RateLimit(rate = 5, durationSeconds = 1)
    @PostMapping("/medication")
    @Operation(summary = "Ask a medication-safety question",
            description = "Returns the clinical reports needed to review the question, plus "
                    + "diagnostic questions worth asking. Never returns a clinical opinion.")
    public ApiResult<MedicationConsultResponse> consult(
            @RequestBody @Nullable MedicationConsultRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "request body must not be empty");
        }
        request.validate();
        return ApiResult.ok(medicationConsultService.consult(request));
    }

    /**
     * Turn 2: submits the requested reports and returns the facts they state.
     *
     * @param request the submitted reports
     * @return the extracted findings with their source excerpts, for clinician review
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a missing or invalid request
     */
    @MedAudit(action = "MEDICATION_CONSULT_REPORTS", resourceType = "CONSULTATION")
    @RateLimit(rate = 5, durationSeconds = 1)
    @PostMapping("/reports")
    @Operation(summary = "Submit clinical reports for a consultation",
            description = "Extracts the facts each report states, with source excerpts so a "
                    + "clinician can verify against the original. Reports are session-scoped and "
                    + "expire with the consultation.")
    public ApiResult<MedicationConsultResponse> submitReports(
            @RequestBody @Nullable ReportSubmissionRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "request body must not be empty");
        }
        request.validate();
        return ApiResult.ok(medicationConsultService.submitReports(request));
    }
}
