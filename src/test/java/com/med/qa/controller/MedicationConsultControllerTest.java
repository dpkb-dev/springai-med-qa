package com.med.qa.controller;

import com.med.qa.controller.dto.MedicationConsultRequest;
import com.med.qa.controller.dto.MedicationConsultResponse;
import com.med.qa.controller.dto.ReportSubmissionRequest;
import com.med.qa.service.medication.ClinicalFindings;
import com.med.qa.service.medication.MedicationConsultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link MedicationConsultController}, following the same offline-boot pattern as
 * {@link ClaimsControllerTest}: the real Spring context starts, but Flyway, the API-key filter, rate
 * limiting and the real chat model are all disabled, and the consultation service is replaced with
 * {@code @MockitoBean}.
 *
 * <p>{@code spring.ai.model.chat=none} is set explicitly rather than relied upon from
 * {@code application.yml}: Spring instantiates every singleton at startup, including
 * {@code OpenAiChatModel} if its auto-configuration condition matches, regardless of whether a mocked
 * service would ever call it — so a run configuration with a chat-enabled profile active would
 * otherwise fail on a missing API key.</p>
 *
 * <p>This class does not re-verify consultation logic — {@code MedicationConsultServiceTest} and
 * {@code MedicationRiskRulesTest} cover that. What is tested here is the HTTP contract: routing,
 * request validation, and that the clinician boundary survives serialisation to JSON.</p>
 *
 * <p><b>On status codes:</b> {@code GlobalExceptionHandler} annotates every {@code BizException}
 * handler with {@code @ResponseStatus(HttpStatus.OK)}, so a validation failure still returns HTTP
 * 200 with the failure signalled by {@code "success":false} and the numeric code in the body.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "med.security.enabled=false",
        "med.rate-limit.enabled=false",
        "spring.ai.model.chat=none"
})
class MedicationConsultControllerTest {

    private static final String CONSULT_URL = "/api/consult/medication";
    private static final String REPORTS_URL = "/api/consult/reports";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MedicationConsultService medicationConsultService;

    // ------------------------------------------------------------------
    // Turn 1 - POST /api/consult/medication
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("turn 1 - the medication question")
    class Turn1 {

        @Test
        @DisplayName("a valid question returns the required reports and the clinician escalation")
        void validQuestionReturnsRequiredReports() throws Exception {
            when(medicationConsultService.consult(any(MedicationConsultRequest.class)))
                    .thenReturn(new MedicationConsultResponse(
                            "consult-1",
                            MedicationConsultResponse.STATUS_REPORTS_REQUIRED,
                            "Pradaxa",
                            List.of("ECG", "DOPPLER_ULTRASOUND_LOWER_LIMB"),
                            List.of("Any chest pain or angina, at rest or on exertion?"),
                            null,
                            MedicationConsultResponse.ESCALATION_CLINICIAN_REVIEW,
                            MedicationConsultResponse.DISCLAIMER));

            mockMvc.perform(post(CONSULT_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"consult-1","medication":"Pradaxa",
                                    "question":"I have DVT in my left leg, should I continue?"}"""))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"code\":0")))
                    .andExpect(content().string(containsString("REPORTS_REQUIRED")))
                    .andExpect(content().string(containsString("DOPPLER_ULTRASOUND_LOWER_LIMB")))
                    .andExpect(content().string(containsString("CLINICIAN_REVIEW_REQUIRED")));
        }

        @Test
        @DisplayName("a blank medication is rejected before the service is reached")
        void blankMedicationRejected() throws Exception {
            mockMvc.perform(post(CONSULT_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"consult-1","medication":"  ","question":"Can I continue?"}"""))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"code\":40000")))
                    .andExpect(content().string(containsString("\"success\":false")));

            verify(medicationConsultService, never()).consult(any(MedicationConsultRequest.class));
        }

        @Test
        @DisplayName("a blank sessionId is rejected")
        void blankSessionIdRejected() throws Exception {
            mockMvc.perform(post(CONSULT_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"","medication":"Pradaxa","question":"Can I continue?"}"""))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"code\":40000")));
        }

        @Test
        @DisplayName("a blank question is rejected")
        void blankQuestionRejected() throws Exception {
            mockMvc.perform(post(CONSULT_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"consult-1","medication":"Pradaxa","question":"   "}"""))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"code\":40000")));
        }
    }

    // ------------------------------------------------------------------
    // Turn 2 - POST /api/consult/reports
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("turn 2 - submitted reports")
    class Turn2 {

        @Test
        @DisplayName("submitted reports return the extracted findings with their source excerpts")
        void submittedReportsReturnFindings() throws Exception {
            when(medicationConsultService.submitReports(any(ReportSubmissionRequest.class)))
                    .thenReturn(new MedicationConsultResponse(
                            "consult-1",
                            MedicationConsultResponse.STATUS_FINDINGS_READY,
                            null, null, null,
                            List.of(new ClinicalFindings(
                                    "DOPPLER_ULTRASOUND_LOWER_LIMB",
                                    List.of("Partially recanalised thrombus in the popliteal vein"),
                                    "partially recanalised thrombus in the popliteal vein",
                                    "")),
                            MedicationConsultResponse.ESCALATION_CLINICIAN_REVIEW,
                            MedicationConsultResponse.DISCLAIMER));

            mockMvc.perform(post(REPORTS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"consult-1","reports":[
                                    {"reportType":"DOPPLER_ULTRASOUND_LOWER_LIMB",
                                    "text":"Left lower limb venous Doppler: partially recanalised thrombus."}]}"""))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"code\":0")))
                    .andExpect(content().string(containsString("FINDINGS_READY")))
                    .andExpect(content().string(containsString("sourceExcerpt")));
        }

        @Test
        @DisplayName("an empty report list is rejected before the service is reached")
        void emptyReportListRejected() throws Exception {
            mockMvc.perform(post(REPORTS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"consult-1","reports":[]}"""))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"code\":40000")));

            verify(medicationConsultService, never()).submitReports(any(ReportSubmissionRequest.class));
        }

        @Test
        @DisplayName("a report with blank text is rejected")
        void blankReportTextRejected() throws Exception {
            mockMvc.perform(post(REPORTS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"consult-1","reports":[
                                    {"reportType":"ECG","text":"   "}]}"""))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"code\":40000")));
        }
    }

    // ------------------------------------------------------------------
    // The clinician boundary must survive serialisation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the clinician boundary over the wire")
    class ClinicianBoundary {

        @Test
        @DisplayName("the disclaimer reaches the client intact, not only the escalation flag")
        void disclaimerReachesTheClient() throws Exception {
            when(medicationConsultService.consult(any(MedicationConsultRequest.class)))
                    .thenReturn(new MedicationConsultResponse(
                            "consult-1",
                            MedicationConsultResponse.STATUS_REPORTS_REQUIRED,
                            "Warfarin",
                            List.of("COAGULATION_PANEL_INR"),
                            List.of("Any bleeding, unusual bruising, or blood in urine or stool?"),
                            null,
                            MedicationConsultResponse.ESCALATION_CLINICIAN_REVIEW,
                            MedicationConsultResponse.DISCLAIMER));

            // A caller must never receive findings or a report request without the statement that
            // this is not a diagnosis, so the full text is asserted rather than the field's presence.
            mockMvc.perform(post(CONSULT_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"consult-1","medication":"Warfarin",
                                    "question":"Can I continue?"}"""))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("This is not a diagnosis")))
                    .andExpect(content().string(containsString("qualified clinician")));
        }
    }
}
