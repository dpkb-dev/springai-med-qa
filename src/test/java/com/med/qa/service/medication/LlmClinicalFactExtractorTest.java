package com.med.qa.service.medication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.med.qa.common.exception.BizException;
import com.med.qa.controller.dto.ClinicalReport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link LlmClinicalFactExtractor}, focused on the excerpt verification.
 *
 * <p>{@link ChatModel#call(Prompt)} is stubbed directly rather than mocking the fluent
 * {@code ChatClient} chain, so the real {@code BeanOutputConverter} parses the JSON into a real
 * {@link ClinicalFindings} — the same path a live model response takes.</p>
 *
 * <p>What is under test is not the model's accuracy, which cannot be unit tested. It is whether this
 * class notices when the model returns an excerpt that is not actually in the report, since
 * {@code sourceExcerpt} is the clinician's verification trail and a fabricated one silently breaks
 * the check it exists to support.</p>
 */
class LlmClinicalFactExtractorTest {

    private static final String REPORT_TEXT =
            "Left lower limb venous Doppler study. Partially recanalised thrombus noted in the "
                    + "left popliteal vein. No fresh thrombus identified.";

    private ChatModel chatModel;
    private ObjectProvider<ChatModel> chatModelProvider;
    private LlmClinicalFactExtractor extractor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatModel = mock(ChatModel.class);
        chatModelProvider = mock(ObjectProvider.class);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        extractor = new LlmClinicalFactExtractor(chatModelProvider);
    }

    @Nested
    @DisplayName("excerpt verification")
    class ExcerptVerification {

        @Test
        @DisplayName("a verbatim excerpt passes through with no warning added")
        void verbatimExcerptPassesThrough() {
            stubModel("""
                    {"reportType":"DOPPLER","statedFindings":["Partially recanalised thrombus"],\
                    "sourceExcerpt":"Partially recanalised thrombus noted in the left popliteal vein",\
                    "extractionNote":""}""");

            ClinicalFindings findings = extractor.extract(report());

            assertThat(findings.extractionNote()).isEmpty();
        }

        @Test
        @DisplayName("an excerpt differing only in whitespace still passes - reflowing is not fabrication")
        void whitespaceDifferenceStillPasses() {
            // The model has reflowed line breaks into single spaces, which is a formatting
            // difference rather than an invented quote.
            stubModel("""
                    {"reportType":"DOPPLER","statedFindings":["Partially recanalised thrombus"],\
                    "sourceExcerpt":"Partially   recanalised\\n thrombus noted in the left popliteal vein",\
                    "extractionNote":""}""");

            ClinicalFindings findings = extractor.extract(report());

            assertThat(findings.extractionNote()).isEmpty();
        }

        @Test
        @DisplayName("an excerpt that is not in the report is flagged, not silently accepted")
        void fabricatedExcerptIsFlagged() {
            stubModel("""
                    {"reportType":"DOPPLER","statedFindings":["Complete occlusion of the femoral vein"],\
                    "sourceExcerpt":"Complete occlusion of the femoral vein was observed",\
                    "extractionNote":""}""");

            ClinicalFindings findings = extractor.extract(report());

            assertThat(findings.extractionNote())
                    .isEqualTo(LlmClinicalFactExtractor.EXCERPT_NOT_VERBATIM_NOTE);
        }

        @Test
        @DisplayName("the findings are kept when the excerpt fails - the clinician decides, not this class")
        void findingsKeptWhenExcerptFails() {
            stubModel("""
                    {"reportType":"DOPPLER","statedFindings":["A finding","Another finding"],\
                    "sourceExcerpt":"text that is not in the report at all",\
                    "extractionNote":""}""");

            ClinicalFindings findings = extractor.extract(report());

            // Discarding the findings would hide the problem. Flagging it surfaces it.
            assertThat(findings.statedFindings()).hasSize(2);
            assertThat(findings.extractionNote()).contains("WARNING");
        }

        @Test
        @DisplayName("an existing extraction note is preserved alongside the warning")
        void existingNotePreserved() {
            stubModel("""
                    {"reportType":"DOPPLER","statedFindings":["A finding"],\
                    "sourceExcerpt":"not in the report",\
                    "extractionNote":"The report date was illegible."}""");

            ClinicalFindings findings = extractor.extract(report());

            assertThat(findings.extractionNote()).contains("The report date was illegible.");
            assertThat(findings.extractionNote()).contains("WARNING");
        }

        @Test
        @DisplayName("a blank excerpt is not treated as a verification failure")
        void blankExcerptIsNotAFailure() {
            stubModel("""
                    {"reportType":"DOPPLER","statedFindings":["A finding"],\
                    "sourceExcerpt":"","extractionNote":""}""");

            ClinicalFindings findings = extractor.extract(report());

            assertThat(findings.extractionNote()).isEmpty();
        }
    }

    @Nested
    @DisplayName("model availability")
    class ModelAvailability {

        @Test
        @DisplayName("a missing chat model fails with a clear BizException")
        void missingModelFailsClearly() {
            when(chatModelProvider.getIfAvailable()).thenReturn(null);

            assertThatThrownBy(() -> extractor.extract(report()))
                    .isInstanceOf(BizException.class);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void stubModel(String json) {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
    }

    private static ClinicalReport report() {
        return new ClinicalReport("DOPPLER_ULTRASOUND_LOWER_LIMB", REPORT_TEXT);
    }
}
