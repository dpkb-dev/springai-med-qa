package com.med.qa.controller.dto;

import com.med.qa.service.medication.ClinicalFindings;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * The response to either consultation turn.
 *
 * <p>Which fields are populated depends on {@link #status}: turn 1 returns
 * {@code REPORTS_REQUIRED} with the report list and checklist filled in, turn 2 returns
 * {@code FINDINGS_READY} with the extracted findings. The disclaimer is present on both, because
 * neither is a clinical answer.</p>
 *
 * @param sessionId          the consultation session
 * @param status             {@code REPORTS_REQUIRED} or {@code FINDINGS_READY}
 * @param medication         the medication asked about, echoed back
 * @param requiredReports    the reports that must be submitted, on turn 1
 * @param diagnosticChecklist questions worth putting to the patient alongside the reports, on turn 1
 * @param findings           the facts extracted from each submitted report, on turn 2
 * @param escalation         always {@code CLINICIAN_REVIEW_REQUIRED} — a consultation never
 *                           concludes without a clinician
 * @param disclaimer         the fixed statement that this is not a diagnosis
 */
public record MedicationConsultResponse(
        String sessionId,
        @Schema(example = "REPORTS_REQUIRED") String status,
        String medication,
        @Nullable List<String> requiredReports,
        @Nullable List<String> diagnosticChecklist,
        @Nullable List<ClinicalFindings> findings,
        String escalation,
        String disclaimer) {

    /** Turn 1 status: the question cannot be reviewed until these reports are supplied. */
    public static final String STATUS_REPORTS_REQUIRED = "REPORTS_REQUIRED";

    /** Turn 2 status: facts have been extracted and are ready for a clinician to read. */
    public static final String STATUS_FINDINGS_READY = "FINDINGS_READY";

    /**
     * The only escalation value this feature produces. A medication-safety consultation always ends
     * with a clinician, never with the application's own conclusion.
     */
    public static final String ESCALATION_CLINICIAN_REVIEW = "CLINICIAN_REVIEW_REQUIRED";

    /**
     * The fixed statement attached to every response. It is a field rather than documentation so no
     * caller can display findings without it.
     */
    public static final String DISCLAIMER =
            "This is not a diagnosis and is not medical advice. The findings listed are facts "
                    + "transcribed from the reports you submitted, not an assessment of what they "
                    + "mean for you. Only a qualified clinician can interpret them and advise on "
                    + "whether to continue, stop or change any medication. Please discuss these "
                    + "reports with your doctor before making any change to your treatment.";
}
