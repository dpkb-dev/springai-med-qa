package com.med.qa.service.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.med.qa.controller.dto.ClaimAssessmentRequest;
import com.med.qa.controller.dto.ClaimAssessmentResponse;
import com.med.qa.controller.dto.ClaimConsistencyRequest;
import com.med.qa.controller.dto.ClaimConsistencyResponse;
import com.med.qa.controller.dto.ClaimDocument;
import com.med.qa.controller.dto.ClaimEligibilityRequest;
import com.med.qa.controller.dto.ClaimEligibilityResponse;
import com.med.qa.controller.dto.ClaimItemAssessment;
import com.med.qa.controller.dto.ClaimLineItem;
import com.med.qa.controller.dto.ConsistencyFinding;
import com.med.qa.controller.dto.PolicyEligibilityRequest;
import com.med.qa.controller.dto.PolicyEligibilityResponse;
import com.med.qa.domain.enums.ClaimType;
import com.med.qa.domain.enums.EligibilityVerdict;
import com.med.qa.service.underwriting.PolicyEligibilityService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClaimProcessingService}, the claim assessment orchestrator.
 *
 * <p>All three underlying services are mocked: what is under test here is the <em>sequencing</em> —
 * which stages run, in what order, and when the pipeline short-circuits. The correctness of each
 * individual stage is covered by its own test class.</p>
 */
class ClaimProcessingServiceTest {

    private PolicyEligibilityService policyEligibilityService;
    private ClaimConsistencyService claimConsistencyService;
    private ClaimEligibilityService claimEligibilityService;
    private ClaimProcessingService service;

    @BeforeEach
    void setUp() {
        policyEligibilityService = mock(PolicyEligibilityService.class);
        claimConsistencyService = mock(ClaimConsistencyService.class);
        claimEligibilityService = mock(ClaimEligibilityService.class);
        service = new ClaimProcessingService(
                policyEligibilityService, claimConsistencyService, claimEligibilityService);
    }

    @Nested
    @DisplayName("short-circuiting on a terminal coverage verdict")
    class ShortCircuit {

        @Test
        @DisplayName("a non-disclosure rejection stops the pipeline before any model call is made")
        void nonDisclosureStopsBeforeAnyModelCall() {
            stubEligibility(EligibilityVerdict.REJECTED_NON_DISCLOSURE, true);

            ClaimAssessmentResponse response = service.assess(fullRequest());

            assertThat(response.proceedToNextStage()).isFalse();
            assertThat(response.stoppedAt()).isEqualTo(ClaimAssessmentResponse.STAGE_ELIGIBILITY);
            assertThat(response.consistency()).isNull();
            assertThat(response.lineItems()).isNull();

            // The whole point of stage ordering: the expensive stages never run.
            verify(claimConsistencyService, never()).check(any(ClaimConsistencyRequest.class));
            verify(claimEligibilityService, never()).assess(any(ClaimEligibilityRequest.class));
        }

        @Test
        @DisplayName("a waiting-period rejection also stops the pipeline")
        void waitingPeriodRejectionStopsPipeline() {
            stubEligibility(EligibilityVerdict.WAITING_PERIOD_NOT_MET, true);

            ClaimAssessmentResponse response = service.assess(fullRequest());

            assertThat(response.proceedToNextStage()).isFalse();
            verify(claimEligibilityService, never()).assess(any(ClaimEligibilityRequest.class));
        }
    }

    @Nested
    @DisplayName("running the full pipeline")
    class FullPipeline {

        @Test
        @DisplayName("a covered claim runs every stage and reports them all")
        void coveredClaimRunsEveryStage() {
            stubEligibility(EligibilityVerdict.COVERED, false);
            stubConsistency(List.of());
            stubLineItems("LIKELY_COVERED");

            ClaimAssessmentResponse response = service.assess(fullRequest());

            assertThat(response.proceedToNextStage()).isTrue();
            assertThat(response.stoppedAt()).isNull();
            assertThat(response.eligibility()).isNotNull();
            assertThat(response.consistency()).isNotNull();
            assertThat(response.lineItems()).isNotNull();
        }

        @Test
        @DisplayName("document findings are advisory and never halt the pipeline")
        void documentFindingsDoNotHaltThePipeline() {
            stubEligibility(EligibilityVerdict.COVERED, false);
            stubConsistency(List.of(new ConsistencyFinding(
                    "PATIENT_NAME_MISMATCH", "HIGH", "names differ",
                    List.of("ADMISSION_FORM", "DISCHARGE_SUMMARY"))));
            stubLineItems("LIKELY_COVERED");

            ClaimAssessmentResponse response = service.assess(fullRequest());

            // A HIGH-severity finding is surfaced to the reviewer, not used to stop the claim.
            assertThat(response.proceedToNextStage()).isTrue();
            assertThat(response.lineItems()).isNotNull();
            assertThat(response.reviewerSummary()).contains("1 document inconsistency finding");
        }
    }

    @Nested
    @DisplayName("skipping stages with nothing to assess")
    class SkippedStages {

        @Test
        @DisplayName("a cashless pre-authorization with no bill yet skips the line-item stage")
        void cashlessPreAuthSkipsLineItems() {
            stubEligibility(EligibilityVerdict.COVERED, false);
            stubConsistency(List.of());

            ClaimAssessmentRequest request = new ClaimAssessmentRequest(
                    "CLM-1", ClaimType.CASHLESS, "POL-1", "t1", "d1", "DVT",
                    LocalDate.of(2026, 6, 10), true, new BigDecimal("300000"),
                    List.of(doc("ADMISSION_FORM"), doc("PREAUTH_FORM")), null);

            ClaimAssessmentResponse response = service.assess(request);

            assertThat(response.proceedToNextStage()).isTrue();
            assertThat(response.lineItems()).isNull();
            assertThat(response.stoppedAt()).isNull();
            assertThat(response.reviewerSummary()).contains("No billed line items submitted yet");
            verify(claimEligibilityService, never()).assess(any(ClaimEligibilityRequest.class));
        }

        @Test
        @DisplayName("no documents supplied skips the consistency stage")
        void noDocumentsSkipsConsistency() {
            stubEligibility(EligibilityVerdict.COVERED, false);
            stubLineItems("LIKELY_COVERED");

            ClaimAssessmentRequest request = new ClaimAssessmentRequest(
                    "CLM-2", ClaimType.REIMBURSEMENT, "POL-1", "t1", "d1", "DVT",
                    LocalDate.of(2026, 6, 10), true, new BigDecimal("300000"),
                    null, List.of(item()));

            ClaimAssessmentResponse response = service.assess(request);

            assertThat(response.consistency()).isNull();
            assertThat(response.reviewerSummary()).contains("Document consistency not checked");
            verify(claimConsistencyService, never()).check(any(ClaimConsistencyRequest.class));
        }

        @Test
        @DisplayName("a claim unrelated to any PED proceeds normally")
        void notPedRelatedProceeds() {
            stubEligibility(EligibilityVerdict.NOT_PED_RELATED, false);
            stubConsistency(List.of());
            stubLineItems("LIKELY_COVERED");

            ClaimAssessmentResponse response = service.assess(fullRequest());

            assertThat(response.proceedToNextStage()).isTrue();
            assertThat(response.stoppedAt()).isNull();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void stubEligibility(EligibilityVerdict verdict, boolean terminal) {
        when(policyEligibilityService.check(any(PolicyEligibilityRequest.class))).thenReturn(
                new PolicyEligibilityResponse("POL-1", "DVT", verdict, null,
                        "rationale for " + verdict, terminal,
                        terminal ? PolicyEligibilityResponse.ESCALATION_GUIDANCE : null));
    }

    private void stubConsistency(List<ConsistencyFinding> findings) {
        when(claimConsistencyService.check(any(ClaimConsistencyRequest.class))).thenReturn(
                new ClaimConsistencyResponse("CLM-1", findings,
                        ClaimConsistencyResponse.ADVISORY_DISCLAIMER));
    }

    private void stubLineItems(String verdict) {
        when(claimEligibilityService.assess(any(ClaimEligibilityRequest.class))).thenReturn(
                new ClaimEligibilityResponse("CLM-1",
                        List.of(new ClaimItemAssessment("room rent", new BigDecimal("9000"),
                                verdict, "rationale", "clause")),
                        ClaimEligibilityResponse.ADVISORY_DISCLAIMER));
    }

    private static ClaimAssessmentRequest fullRequest() {
        return new ClaimAssessmentRequest(
                "CLM-1", ClaimType.REIMBURSEMENT, "POL-1", "t1", "d1", "DVT",
                LocalDate.of(2026, 6, 10), true, new BigDecimal("300000"),
                List.of(doc("ADMISSION_FORM"), doc("DISCHARGE_SUMMARY")), List.of(item()));
    }

    private static ClaimDocument doc(String type) {
        return new ClaimDocument(type, "Patient: Rohan Sharma. Admitted 2026-06-08.");
    }

    private static ClaimLineItem item() {
        return new ClaimLineItem("room rent", new BigDecimal("9000"), 3);
    }
}
