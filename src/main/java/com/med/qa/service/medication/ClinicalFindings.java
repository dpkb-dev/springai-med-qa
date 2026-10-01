package com.med.qa.service.medication;

import java.util.List;

/**
 * Facts extracted from one clinical report by the model.
 *
 * <h2>The boundary this record enforces</h2>
 * <p>Every field here is something the report <em>states</em>. Nothing here is something a clinician
 * would <em>conclude</em>. There is deliberately no field for risk level, severity, prognosis, or a
 * recommendation to continue or stop a medication: reading a Doppler result and deciding what it
 * means for this patient's anticoagulant therapy is a diagnosis, and diagnosis stays with the
 * clinician (blueprint rule BR-2).</p>
 *
 * <p>That boundary is enforced by the shape of this record, not merely by the prompt. A model
 * instructed to extract facts might still volunteer an opinion in free text — but it has nowhere to
 * put one here, so the opinion cannot reach the response.</p>
 *
 * @param reportType     the report this came from, echoed back for display
 * @param statedFindings the findings the report states, each in the report's own terms rather than
 *                       paraphrased into a conclusion
 * @param sourceExcerpt  the passage the findings were drawn from, so a clinician verifies against
 *                       the source rather than trusting the extraction
 * @param extractionNote anything unclear or ambiguous in this specific report, or an empty string
 */
public record ClinicalFindings(
        String reportType,
        List<String> statedFindings,
        String sourceExcerpt,
        String extractionNote) {
}
