package com.med.qa.service.medication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.med.qa.controller.dto.ClinicalReport;
import com.med.qa.controller.dto.MedicationConsultRequest;
import com.med.qa.controller.dto.MedicationConsultResponse;
import com.med.qa.controller.dto.ReportSubmissionRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MedicationConsultService}.
 *
 * <p>The extractor and the session store are mocked, so what is under test is the orchestration:
 * which reports are requested, whether reports are stored before extraction, and whether every
 * response carries the clinician escalation and disclaimer.</p>
 */
class MedicationConsultServiceTest {

    private ClinicalFactExtractor factExtractor;
    private ConsultationSessionStore sessionStore;
    private MedicationConsultService service;

    @BeforeEach
    void setUp() {
        factExtractor = mock(ClinicalFactExtractor.class);
        sessionStore = mock(ConsultationSessionStore.class);
        service = new MedicationConsultService(factExtractor, sessionStore);
    }

    @Nested
    @DisplayName("turn 1 - the question alone")
    class Turn1 {

        @Test
        @DisplayName("a medication question is never answered, only met with a request for reports")
        void questionIsNeverAnswered() {
            MedicationConsultResponse response = service.consult(new MedicationConsultRequest(
                    "consult-1", "Pradaxa", "Can I continue taking Pradaxa?"));

            assertThat(response.status())
                    .isEqualTo(MedicationConsultResponse.STATUS_REPORTS_REQUIRED);
            assertThat(response.requiredReports()).isNotEmpty();
            assertThat(response.findings()).isNull();

            // The model is not consulted at all on turn 1 - there is nothing yet to extract from.
            verify(factExtractor, never()).extract(any(ClinicalReport.class));
        }

        @Test
        @DisplayName("a DVT mention adds limb imaging to the requested reports")
        void dvtMentionAddsImaging() {
            MedicationConsultResponse response = service.consult(new MedicationConsultRequest(
                    "consult-1", "Pradaxa", "I have DVT in my left leg, should I continue?"));

            assertThat(response.requiredReports()).contains("DOPPLER_ULTRASOUND_LOWER_LIMB");
        }

        @Test
        @DisplayName("the diagnostic checklist is returned alongside the reports")
        void checklistIsReturned() {
            MedicationConsultResponse response = service.consult(new MedicationConsultRequest(
                    "consult-1", "Warfarin", "Can I continue?"));

            assertThat(response.diagnosticChecklist()).isNotEmpty();
        }

        @Test
        @DisplayName("an unrecognised medication still asks for reports rather than answering")
        void unrecognisedMedicationStillAsksForReports() {
            MedicationConsultResponse response = service.consult(new MedicationConsultRequest(
                    "consult-1", "SomeNewDrug", "Is this safe with my other tablets?"));

            assertThat(response.status())
                    .isEqualTo(MedicationConsultResponse.STATUS_REPORTS_REQUIRED);
            assertThat(response.requiredReports()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("turn 2 - submitted reports")
    class Turn2 {

        @Test
        @DisplayName("each submitted report is extracted exactly once")
        void eachReportExtractedOnce() {
            stubExtractor();

            service.submitReports(new ReportSubmissionRequest("consult-1", List.of(
                    report("ECG"), report("DOPPLER_ULTRASOUND_LOWER_LIMB"), report("COAGULATION_PANEL"))));

            verify(factExtractor, times(3)).extract(any(ClinicalReport.class));
        }

        @Test
        @DisplayName("reports are stored for the session before extraction")
        void reportsAreStoredForTheSession() {
            stubExtractor();
            List<ClinicalReport> reports = List.of(report("ECG"));

            service.submitReports(new ReportSubmissionRequest("consult-1", reports));

            verify(sessionStore).saveReports("consult-1", reports);
        }

        @Test
        @DisplayName("findings are returned with the ready status and no report request")
        void findingsReturnedWithReadyStatus() {
            stubExtractor();

            MedicationConsultResponse response = service.submitReports(
                    new ReportSubmissionRequest("consult-1", List.of(report("ECG"))));

            assertThat(response.status()).isEqualTo(MedicationConsultResponse.STATUS_FINDINGS_READY);
            assertThat(response.findings()).hasSize(1);
            assertThat(response.requiredReports()).isNull();
        }
    }

    @Nested
    @DisplayName("the clinician boundary, on every response")
    class ClinicianBoundary {

        @Test
        @DisplayName("turn 1 carries the clinician escalation and disclaimer")
        void turn1CarriesEscalationAndDisclaimer() {
            MedicationConsultResponse response = service.consult(new MedicationConsultRequest(
                    "consult-1", "Pradaxa", "Can I continue?"));

            assertThat(response.escalation())
                    .isEqualTo(MedicationConsultResponse.ESCALATION_CLINICIAN_REVIEW);
            assertThat(response.disclaimer()).isEqualTo(MedicationConsultResponse.DISCLAIMER);
        }

        @Test
        @DisplayName("turn 2 carries them too - findings never conclude a consultation")
        void turn2CarriesEscalationAndDisclaimer() {
            stubExtractor();

            MedicationConsultResponse response = service.submitReports(
                    new ReportSubmissionRequest("consult-1", List.of(report("ECG"))));

            assertThat(response.escalation())
                    .isEqualTo(MedicationConsultResponse.ESCALATION_CLINICIAN_REVIEW);
            assertThat(response.disclaimer()).isEqualTo(MedicationConsultResponse.DISCLAIMER);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void stubExtractor() {
        when(factExtractor.extract(any(ClinicalReport.class))).thenAnswer(invocation -> {
            ClinicalReport report = invocation.getArgument(0);
            return new ClinicalFindings(report.reportType(),
                    List.of("a finding stated in the report"), "source excerpt", "");
        });
    }

    private static ClinicalReport report(String type) {
        return new ClinicalReport(type, "Report text for " + type + ".");
    }
}
