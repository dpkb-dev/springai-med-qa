package com.med.qa.service.medication;

import com.med.qa.controller.dto.ClinicalReport;
import com.med.qa.controller.dto.MedicationConsultRequest;
import com.med.qa.controller.dto.MedicationConsultResponse;
import com.med.qa.controller.dto.ReportSubmissionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates a two-turn medication-safety consultation.
 *
 * <h2>Turn 1 — the question alone is never enough</h2>
 * <p>A question such as "can I continue taking Pradaxa?" is not answered. {@link MedicationRiskRules}
 * determines which reports a clinician would need, and the response asks for them. This is blueprint
 * rule BR-1, and it holds even when the patient asks a second time: the application has no mechanism
 * to answer without evidence, because it never attempts an answer at all.</p>
 *
 * <h2>Turn 2 — facts are extracted, conclusions are not</h2>
 * <p>Each submitted report is sent to {@link ClinicalFactExtractor} on its own, and what comes back
 * is a transcription of what that report states. The response carries those findings, their source
 * excerpts, and an unconditional {@code CLINICIAN_REVIEW_REQUIRED}. Nothing in this class decides
 * whether the medication should be continued — that is a diagnosis, and it stays with the clinician
 * (blueprint rule BR-2).</p>
 *
 * <h2>Why reports are session-scoped</h2>
 * <p>{@link ConsultationSessionStore} holds submitted reports only for the life of the consultation.
 * A later consultation re-requests current reports rather than reusing old ones, because answering a
 * fresh question against stale evidence is a safety risk, and retaining clinical detail indefinitely
 * is a privacy liability (blueprint rule BR-5).</p>
 */
@Service
public class MedicationConsultService {

    private static final Logger log = LoggerFactory.getLogger(MedicationConsultService.class);

    private final ClinicalFactExtractor factExtractor;

    private final ConsultationSessionStore sessionStore;

    /**
     * Creates the service.
     *
     * @param factExtractor the extraction strategy, must not be {@code null}
     * @param sessionStore  session-scoped report storage, must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public MedicationConsultService(
            ClinicalFactExtractor factExtractor, ConsultationSessionStore sessionStore) {
        this.factExtractor = Objects.requireNonNull(factExtractor, "factExtractor must not be null");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
    }

    /**
     * Turn 1: determines which reports the question requires, and asks for them.
     *
     * @param request the patient's question, must already be validated
     * @return a {@code REPORTS_REQUIRED} response listing the reports and diagnostic checklist
     */
    public MedicationConsultResponse consult(MedicationConsultRequest request) {
        List<String> requiredReports =
                MedicationRiskRules.requiredReportsFor(request.medication(), request.question());

        log.debug("consultation {} for medication {} requires {} report(s)",
                request.sessionId(), request.medication(), requiredReports.size());

        return new MedicationConsultResponse(
                request.sessionId(),
                MedicationConsultResponse.STATUS_REPORTS_REQUIRED,
                request.medication(),
                requiredReports,
                MedicationRiskRules.generalDiagnosticChecklist(),
                null,
                MedicationConsultResponse.ESCALATION_CLINICIAN_REVIEW,
                MedicationConsultResponse.DISCLAIMER);
    }

    /**
     * Turn 2: stores the submitted reports for the session and extracts the facts each one states.
     *
     * @param request the submitted reports, must already be validated
     * @return a {@code FINDINGS_READY} response carrying one set of findings per report
     */
    public MedicationConsultResponse submitReports(ReportSubmissionRequest request) {
        sessionStore.saveReports(request.sessionId(), request.reports());

        List<ClinicalFindings> findings = new ArrayList<>(request.reports().size());
        for (ClinicalReport report : request.reports()) {
            findings.add(factExtractor.extract(report));
        }

        log.debug("consultation {} extracted findings from {} report(s)",
                request.sessionId(), findings.size());

        return new MedicationConsultResponse(
                request.sessionId(),
                MedicationConsultResponse.STATUS_FINDINGS_READY,
                null,
                null,
                null,
                findings,
                MedicationConsultResponse.ESCALATION_CLINICIAN_REVIEW,
                MedicationConsultResponse.DISCLAIMER);
    }
}
