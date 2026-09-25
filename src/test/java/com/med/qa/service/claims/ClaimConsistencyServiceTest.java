package com.med.qa.service.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.med.qa.common.exception.BizException;
import com.med.qa.controller.dto.ClaimConsistencyRequest;
import com.med.qa.controller.dto.ClaimConsistencyResponse;
import com.med.qa.controller.dto.ClaimDocument;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests of {@link ClaimConsistencyService}'s orchestration: does it call the model once per
 * document, correctly collect the returned {@link DocumentFacts}, and correctly hand them to
 * {@link ConsistencyChecker}?
 *
 * <p>Only {@link ChatModel} is mocked — no Spring context, and no vector store, since this feature
 * does not use RAG retrieval at all (each document is analyzed independently, never compared against
 * an ingested corpus). {@link ChatModel#call(Prompt)} is stubbed with a sequence of responses, one per
 * document, matching the order {@link ClaimConsistencyService} calls it in. The actual comparison
 * logic itself is not re-verified here; that is {@link ConsistencyCheckerTest}'s job — this test only
 * confirms the wiring between the per-document model calls and the checker is correct.</p>
 */
class ClaimConsistencyServiceTest {

    private ObjectProvider<ChatModel> chatModelProvider;

    private ChatModel chatModel;

    private ClaimConsistencyService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        chatModelProvider = mock(ObjectProvider.class);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        service = new ClaimConsistencyService(chatModelProvider);
    }

    /** Stubs {@link ChatModel#call(Prompt)} to return each JSON string in order, one call at a time. */
    private void stubModelJsonSequence(String... jsonResponses) {
        OngoingStubbing<ChatResponse> stub = when(chatModel.call(any(Prompt.class)));
        for (String json : jsonResponses) {
            ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
            stub = stub.thenReturn(response);
        }
    }

    @Nested
    @DisplayName("consistent documents")
    class Consistent {

        @Test
        @DisplayName("matching names and valid date order across documents produce no findings")
        void consistentDocumentsProduceNoFindings() {
            stubModelJsonSequence(
                    """
                    {"patientName":"Rohan Sharma","admissionDate":null,"dischargeDate":null,"extractionNote":""}""",
                    """
                    {"patientName":"Rohan Sharma","admissionDate":"2026-03-08","dischargeDate":"2026-03-10",\
                    "extractionNote":""}""");

            ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-1", List.of(
                    new ClaimDocument("ADMISSION_FORM", "Patient: Rohan Sharma."),
                    new ClaimDocument("DISCHARGE_SUMMARY",
                            "Patient: Rohan Sharma. Admitted 8 March, discharged 10 March.")));

            ClaimConsistencyResponse response = service.check(request);

            assertThat(response.findings()).isEmpty();
            assertThat(response.claimId()).isEqualTo("claim-1");
            assertThat(response.disclaimer()).isEqualTo(ClaimConsistencyResponse.ADVISORY_DISCLAIMER);
        }
    }

    @Nested
    @DisplayName("inconsistent documents")
    class Inconsistent {

        @Test
        @DisplayName("a name mismatch extracted from two documents is correctly reported")
        void nameMismatchIsReported() {
            stubModelJsonSequence(
                    """
                    {"patientName":"Rohan Sharma","admissionDate":null,"dischargeDate":null,"extractionNote":""}""",
                    """
                    {"patientName":"Rohit Sharma","admissionDate":null,"dischargeDate":null,"extractionNote":""}""");

            ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-2", List.of(
                    new ClaimDocument("ADMISSION_FORM", "Patient: Rohan Sharma."),
                    new ClaimDocument("DISCHARGE_SUMMARY", "Patient: Rohit Sharma.")));

            ClaimConsistencyResponse response = service.check(request);

            assertThat(response.findings()).hasSize(1);
            assertThat(response.findings().get(0).type()).isEqualTo("PATIENT_NAME_MISMATCH");
        }

        @Test
        @DisplayName("a date logic error extracted from a single document is correctly reported")
        void dateLogicErrorIsReported() {
            stubModelJsonSequence(
                    """
                    {"patientName":"Rohan Sharma","admissionDate":null,"dischargeDate":null,"extractionNote":""}""",
                    """
                    {"patientName":"Rohan Sharma","admissionDate":"2026-03-10","dischargeDate":"2026-03-08",\
                    "extractionNote":""}""");

            ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-3", List.of(
                    new ClaimDocument("ADMISSION_FORM", "Patient: Rohan Sharma."),
                    new ClaimDocument("DISCHARGE_SUMMARY", "Admitted 10 March, discharged 8 March.")));

            ClaimConsistencyResponse response = service.check(request);

            assertThat(response.findings()).hasSize(1);
            assertThat(response.findings().get(0).type()).isEqualTo("DATE_LOGIC_ERROR");
        }
    }

    @Nested
    @DisplayName("model unavailable")
    class ModelUnavailable {

        @Test
        @DisplayName("throws a BizException when spring.ai.model.chat is not enabled")
        void throwsWhenModelMissing() {
            when(chatModelProvider.getIfAvailable()).thenReturn(null);

            ClaimConsistencyRequest request = new ClaimConsistencyRequest("claim-4", List.of(
                    new ClaimDocument("ADMISSION_FORM", "text one"),
                    new ClaimDocument("DISCHARGE_SUMMARY", "text two")));

            assertThatThrownBy(() -> service.check(request)).isInstanceOf(BizException.class);
        }
    }
}
