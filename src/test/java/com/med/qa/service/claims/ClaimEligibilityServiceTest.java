package com.med.qa.service.claims;

import com.med.qa.common.exception.BizException;
import com.med.qa.controller.dto.ClaimEligibilityRequest;
import com.med.qa.controller.dto.ClaimEligibilityResponse;
import com.med.qa.controller.dto.ClaimItemAssessment;
import com.med.qa.controller.dto.ClaimLineItem;
import com.med.qa.rag.MedRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link ClaimEligibilityService}'s orchestration: does it call retrieval with the
 * right scope, correctly build and send the fact-extraction prompt, and correctly hand the model's
 * {@link ClauseAnalysis} facts to {@link CapCalculator}?
 *
 * <p>Only {@link MedRetrievalService} and {@link ChatModel} are mocked — no vector store, no OpenAI,
 * no Redis, no Spring context. {@link ChatModel#call(Prompt)} is stubbed directly (returning a real
 * {@link ChatResponse}), so the real {@code ChatClient}/{@code BeanOutputConverter} machinery
 * genuinely parses the JSON into a real {@link ClauseAnalysis}, the same as it would against a real
 * model — this exercises real Spring AI code, not a hand-faked substitute for it. The actual cap
 * arithmetic itself is not re-verified here; that is {@link CapCalculatorTest}'s job. This test only
 * confirms the wiring between the two is correct.</p>
 */
class ClaimEligibilityServiceTest {

    private MedRetrievalService retrievalService;

    private ObjectProvider<ChatModel> chatModelProvider;

    private ChatModel chatModel;

    private ClaimEligibilityService service;

    @BeforeEach
    void setUp() {
        retrievalService = mock(MedRetrievalService.class);
        chatModel = mock(ChatModel.class);
        chatModelProvider = mock(ObjectProvider.class);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        service = new ClaimEligibilityService(retrievalService, chatModelProvider);
    }

    /** Stubs {@link ChatModel#call(Prompt)} to return the given raw JSON as the model's reply text. */
    private void stubModelJson(String json) {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    private static Document policyDocument(String text) {
        return Document.builder().text(text).build();
    }

    private static ClaimEligibilityRequest oneItemRequest(BigDecimal sumInsured, ClaimLineItem item) {
        return new ClaimEligibilityRequest("t1", "d1", null, "claim-1", sumInsured, List.of(item));
    }

    @Nested
    @DisplayName("no matching policy clause")
    class NoMatch {

        @Test
        @DisplayName("an item with no retrieval matches needs manual review, and the model is never called")
        void noMatchesNeedsReviewWithoutCallingModel() {
            when(retrievalService.search(anyString(), any())).thenReturn(List.of());

            ClaimEligibilityResponse response = service.assess(oneItemRequest(
                    new BigDecimal("100000"), new ClaimLineItem("mystery item", new BigDecimal("500"), null)));

            ClaimItemAssessment assessment = response.assessments().get(0);
            assertThat(assessment.verdict()).isEqualTo("NEEDS_MANUAL_REVIEW");
            assertThat(assessment.matchedClause()).isNull();
            verify(chatModel, never()).call(any(Prompt.class));
        }
    }

    @Nested
    @DisplayName("retrieval failure (not just an empty result)")
    class RetrievalFailure {

        @Test
        @DisplayName("a genuine retrieval failure is wrapped as a clean BizException, and the model is never called")
        void retrievalFailureIsWrappedCleanly() {
            when(retrievalService.search(anyString(), any()))
                    .thenThrow(new RuntimeException("Redis connection refused"));

            assertThatThrownBy(() -> service.assess(oneItemRequest(
                    new BigDecimal("100000"), new ClaimLineItem("room rent", new BigDecimal("500"), null))))
                    .isInstanceOf(BizException.class);

            verify(chatModel, never()).call(any(Prompt.class));
        }
    }

    @Nested
    @DisplayName("a matched clause is correctly wired through to CapCalculator")
    class Wiring {

        @Test
        @DisplayName("a per-day capped clause uses the request's sumInsured and the item's days correctly")
        void perDayCapWiresCorrectly() {
            when(retrievalService.search(anyString(), any()))
                    .thenReturn(List.of(policyDocument("Room rent covered up to 1% of sum insured per day.")));
            stubModelJson("""
                    {"applicability":"COVERED_WITH_CAP","capBasis":"PERCENT_OF_SUM_INSURED_PER_DAY",\
                    "capValue":1,"clauseSummary":"Room rent capped at 1% of sum insured per day."}""");

            ClaimEligibilityResponse response = service.assess(oneItemRequest(
                    new BigDecimal("300000"), new ClaimLineItem("room rent", new BigDecimal("9000"), 3)));

            ClaimItemAssessment assessment = response.assessments().get(0);
            assertThat(assessment.verdict()).isEqualTo("LIKELY_COVERED");
            assertThat(assessment.rationale()).contains("9000.00");
            assertThat(assessment.matchedClause()).contains("Room rent covered up to 1%");
        }

        @Test
        @DisplayName("an amount exceeding the calculated cap comes back NEEDS_MANUAL_REVIEW")
        void overCapWiresToManualReview() {
            when(retrievalService.search(anyString(), any()))
                    .thenReturn(List.of(policyDocument("Room rent covered up to 1% of sum insured per day.")));
            stubModelJson("""
                    {"applicability":"COVERED_WITH_CAP","capBasis":"PERCENT_OF_SUM_INSURED_PER_DAY",\
                    "capValue":1,"clauseSummary":"Room rent capped at 1% of sum insured per day."}""");

            ClaimEligibilityResponse response = service.assess(oneItemRequest(
                    new BigDecimal("300000"), new ClaimLineItem("room rent", new BigDecimal("15000"), 3)));

            assertThat(response.assessments().get(0).verdict()).isEqualTo("NEEDS_MANUAL_REVIEW");
        }

        @Test
        @DisplayName("an excluded clause is reported as LIKELY_EXCLUDED")
        void excludedClauseWiresCorrectly() {
            when(retrievalService.search(anyString(), any()))
                    .thenReturn(List.of(policyDocument("OTC medicine without prescription is excluded.")));
            stubModelJson("""
                    {"applicability":"EXCLUDED","capBasis":null,"capValue":null,\
                    "clauseSummary":"Not covered without a prescription."}""");

            ClaimEligibilityResponse response = service.assess(oneItemRequest(
                    new BigDecimal("300000"), new ClaimLineItem("ibuprofen OTC", new BigDecimal("250"), null)));

            assertThat(response.assessments().get(0).verdict()).isEqualTo("LIKELY_EXCLUDED");
        }
    }

    @Nested
    @DisplayName("multiple line items")
    class MultipleItems {

        @Test
        @DisplayName("assesses every line item and preserves request order in the response")
        void assessesEveryItemInOrder() {
            when(retrievalService.search(anyString(), any()))
                    .thenReturn(List.of(policyDocument("Covered with no limit.")));
            stubModelJson("""
                    {"applicability":"COVERED_NO_LIMIT","capBasis":null,"capValue":null,\
                    "clauseSummary":"Fully covered."}""");

            ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                    "t1", "d1", null, "claim-1", new BigDecimal("300000"),
                    List.of(
                            new ClaimLineItem("item A", new BigDecimal("100"), null),
                            new ClaimLineItem("item B", new BigDecimal("200"), null)));

            ClaimEligibilityResponse response = service.assess(request);

            assertThat(response.assessments())
                    .extracting(ClaimItemAssessment::itemDescription)
                    .containsExactly("item A", "item B");
        }
    }

    @Nested
    @DisplayName("response shape")
    class ResponseShape {

        @Test
        @DisplayName("every response carries the fixed advisory disclaimer and echoes the claim id")
        void carriesDisclaimerAndClaimId() {
            when(retrievalService.search(anyString(), any())).thenReturn(List.of());

            ClaimEligibilityResponse response = service.assess(oneItemRequest(
                    new BigDecimal("300000"), new ClaimLineItem("item", new BigDecimal("1"), null)));

            assertThat(response.claimId()).isEqualTo("claim-1");
            assertThat(response.disclaimer()).isEqualTo(ClaimEligibilityResponse.ADVISORY_DISCLAIMER);
        }
    }

    @Nested
    @DisplayName("model unavailable")
    class ModelUnavailable {

        @Test
        @DisplayName("throws a BizException when spring.ai.model.chat is not enabled")
        void throwsWhenModelMissing() {
            when(chatModelProvider.getIfAvailable()).thenReturn(null);

            assertThatThrownBy(() -> service.assess(oneItemRequest(
                    new BigDecimal("300000"), new ClaimLineItem("item", new BigDecimal("1"), null))))
                    .isInstanceOf(BizException.class);
        }
    }
}
