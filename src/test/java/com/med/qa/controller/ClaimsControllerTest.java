package com.med.qa.controller;

import com.med.qa.controller.dto.ClaimEligibilityRequest;
import com.med.qa.controller.dto.ClaimEligibilityResponse;
import com.med.qa.controller.dto.ClaimItemAssessment;
import com.med.qa.controller.dto.ClaimConsistencyRequest;
import com.med.qa.controller.dto.ClaimConsistencyResponse;
import com.med.qa.controller.dto.ConsistencyFinding;
import com.med.qa.service.claims.ClaimConsistencyService;
import com.med.qa.service.claims.ClaimEligibilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests of {@link ClaimsController}, matching {@link ChatControllerTest}'s exact
 * offline-boot pattern: the real Spring context is booted, but Flyway, the API-key filter, rate
 * limiting, and the real chat model are all disabled via {@code @TestPropertySource}, and both claims
 * services are {@code @MockitoBean}-ed — no Docker, no MySQL, no Redis, no OpenAI key required to run
 * this class.
 *
 * <p>{@code spring.ai.model.chat=none} is set explicitly here rather than relied upon from
 * {@code application.yml}'s own default: Spring eagerly instantiates every registered singleton bean
 * at context startup (including {@code OpenAiChatModel}, if its auto-configuration condition
 * matches), regardless of whether any mocked service would ever actually call it — so if a profile
 * with {@code spring.ai.model.chat=openai} happens to be active for a given test run (e.g. via an IDE
 * run configuration default), the real bean still gets constructed and fails on a missing API key.
 * Overriding it here, per test class, makes the offline-boot guarantee hold regardless of whatever
 * profile the surrounding run configuration happens to have active.</p>
 *
 * <p>This class deliberately does not re-verify any business logic — {@link CapCalculatorTest},
 * {@link com.med.qa.service.claims.ConsistencyCheckerTest}, and the two service-orchestration test
 * classes already cover that exhaustively. This class only confirms the HTTP-level contract: correct
 * status codes, correct routing to each endpoint, and correct handling of a validation failure. Both
 * services are replaced with {@code @MockitoBean} — Spring Framework's non-deprecated replacement for
 * Spring Boot's {@code @MockBean}, in effect since Spring Boot 3.4.</p>
 *
 * <p><b>Note on status codes:</b> {@code GlobalExceptionHandler.handleBizException} is annotated
 * {@code @ResponseStatus(HttpStatus.OK)} for every {@link com.med.qa.common.exception.BizException},
 * regardless of its {@code ErrorCode} — so a validation failure from {@code request.validate()} still
 * returns HTTP 200, with the real failure signaled by {@code "success":false} and the numeric error
 * code inside the JSON body, not by the HTTP status itself.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "med.security.enabled=false",
        "med.rate-limit.enabled=false",
        "spring.ai.model.chat=none"
})
class ClaimsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClaimEligibilityService claimEligibilityService;

    @MockitoBean
    private ClaimConsistencyService claimConsistencyService;

    // ------------------------------------------------------------------
    // /api/claims/eligibility-check
    // ------------------------------------------------------------------

    @Test
    @DisplayName("eligibility-check returns the service's assessment for a valid request")
    void eligibilityCheckReturnsAssessment() throws Exception {
        ClaimItemAssessment assessment = new ClaimItemAssessment(
                "room rent", new BigDecimal("9000"), "LIKELY_COVERED", "within the cap", "policy clause text");
        ClaimEligibilityResponse response = new ClaimEligibilityResponse(
                "claim-1", List.of(assessment), ClaimEligibilityResponse.ADVISORY_DISCLAIMER);
        when(claimEligibilityService.assess(any(ClaimEligibilityRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/claims/eligibility-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"t1","deptId":"d1","claimId":"claim-1","sumInsured":300000,
                                "items":[{"description":"room rent","amount":9000,"days":3}]}"""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":0")))
                .andExpect(content().string(containsString("LIKELY_COVERED")));
    }

    @Test
    @DisplayName("eligibility-check with no line items fails validation with BAD_REQUEST (40000), HTTP still 200")
    void eligibilityCheckRejectsEmptyItems() throws Exception {
        mockMvc.perform(post("/api/claims/eligibility-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"t1","deptId":"d1","sumInsured":300000,"items":[]}"""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":40000")))
                .andExpect(content().string(containsString("\"success\":false")));
    }

    // ------------------------------------------------------------------
    // /api/claims/consistency-check
    // ------------------------------------------------------------------

    @Test
    @DisplayName("consistency-check returns the service's findings for a valid request")
    void consistencyCheckReturnsFindings() throws Exception {
        ConsistencyFinding finding = new ConsistencyFinding(
                "PATIENT_NAME_MISMATCH", "HIGH", "names differ", List.of("ADMISSION_FORM", "DISCHARGE_SUMMARY"));
        ClaimConsistencyResponse response = new ClaimConsistencyResponse(
                "claim-2", List.of(finding), ClaimConsistencyResponse.ADVISORY_DISCLAIMER);
        when(claimConsistencyService.check(any(ClaimConsistencyRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/claims/consistency-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"claimId":"claim-2","documents":[
                                {"documentType":"ADMISSION_FORM","text":"Patient: Rohan Sharma."},
                                {"documentType":"DISCHARGE_SUMMARY","text":"Patient: Rohit Sharma."}]}"""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":0")))
                .andExpect(content().string(containsString("PATIENT_NAME_MISMATCH")));
    }

    @Test
    @DisplayName("consistency-check with fewer than two documents fails validation with BAD_REQUEST (40000)")
    void consistencyCheckRejectsSingleDocument() throws Exception {
        mockMvc.perform(post("/api/claims/consistency-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"claimId":"claim-3","documents":[
                                {"documentType":"ADMISSION_FORM","text":"Patient: Rohan Sharma."}]}"""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":40000")))
                .andExpect(content().string(containsString("\"success\":false")));
    }
}
