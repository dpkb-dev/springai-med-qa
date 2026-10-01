package com.med.qa.service.medication;

import com.med.qa.controller.dto.ClinicalReport;

/**
 * Extracts structured facts from one clinical report.
 *
 * <h2>Why this is an interface</h2>
 * <p>{@link MedicationConsultService} depends on this abstraction rather than on {@code ChatModel}
 * directly, so the extraction strategy can change — a locally hosted model, a deterministic
 * pattern-matcher for structured lab formats, or a stub in tests — without the service being
 * modified at all.</p>
 *
 * <h2>The contract every implementation is bound by</h2>
 * <p>An implementation must <strong>extract what the report states</strong> and must
 * <strong>never interpret what it means</strong>. Returning a severity rating, a prognosis, or a
 * recommendation to continue or stop a medication violates this contract and blueprint rule BR-2,
 * regardless of how accurate it might be. This is a substitutability requirement, not a style
 * preference: a caller relies on the returned findings being reportable facts a clinician can verify
 * against the source, not conclusions they would have to re-derive.</p>
 */
public interface ClinicalFactExtractor {

    /**
     * Extracts the facts stated in one clinical report.
     *
     * @param report the report to read, must not be {@code null}
     * @return the stated findings, their source excerpt, and any ambiguity noted — never a
     *         diagnosis, severity or recommendation
     * @throws com.med.qa.common.exception.BizException if extraction fails
     */
    ClinicalFindings extract(ClinicalReport report);
}
