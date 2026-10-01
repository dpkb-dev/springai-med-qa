package com.med.qa.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One clinical report submitted in support of a medication-safety consultation.
 *
 * <h2>Why this is not the same as an ingested RAG document</h2>
 * <p>A document ingested through {@code /api/rag/documents/ingest} becomes permanent, embedded,
 * tenant-wide knowledge that any later question can retrieve. A clinical report is the opposite:
 * evidence for <em>one</em> consultation, read once, never embedded, never shared. Routing reports
 * through the RAG ingestion path would place a patient's private Doppler report into the shared
 * department index, where it could surface in an unrelated patient's search — a data leak, and a
 * direct violation of blueprint rule BR-5.</p>
 *
 * @param reportType a short label such as {@code ECG}, {@code DOPPLER_ULTRASOUND_LOWER_LIMB} or
 *                   {@code COAGULATION_PANEL}, matching what
 *                   {@link com.med.qa.service.medication.MedicationRiskRules} asked for; free text
 *                   rather than a closed enum, since report naming varies by hospital
 * @param text       the report's text content, must not be blank
 */
public record ClinicalReport(
        @Schema(example = "DOPPLER_ULTRASOUND_LOWER_LIMB") String reportType,
        @Schema(example = "Left lower limb venous Doppler: partially recanalised thrombus in the "
                + "popliteal vein. No fresh thrombus. Flow partially restored.") String text) {

    /**
     * Validates this report.
     *
     * @throws IllegalArgumentException if {@code reportType} or {@code text} is blank
     */
    public void validate() {
        if (reportType == null || reportType.isBlank()) {
            throw new IllegalArgumentException("reportType must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("report text must not be blank");
        }
    }
}
