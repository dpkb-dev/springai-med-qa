package com.med.qa.service.medication;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.controller.dto.ClinicalReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Extracts clinical facts using the configured chat model.
 *
 * <h2>Why a fresh ChatClient rather than the shared builder</h2>
 * <p>The application's shared {@code ChatClient.Builder} carries {@code MessageChatMemoryAdvisor} as
 * a default advisor, for the medical-consultation chat feature. These calls are stateless, one-shot
 * extractions with no conversation id, so running them through that advisor would fail on Spring
 * AI's literal {@code "default"} conversation id. Building through the static
 * {@link ChatClient#builder(ChatModel)} factory yields a client with no advisors at all.</p>
 *
 * <h2>One report at a time, deliberately</h2>
 * <p>Each call sees exactly one report and nothing else. The model therefore cannot correlate an ECG
 * against a Doppler even if prompted to — correlation across reports is clinical reasoning, and this
 * class is not where that happens.</p>
 */
@Component
public class LlmClinicalFactExtractor implements ClinicalFactExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmClinicalFactExtractor.class);

    private static final String PROMPT_TEMPLATE = """
            You are a clinical report transcription assistant. Your ONLY job is to extract the facts
            that the report below explicitly states.

            You must NOT:
            - interpret what any finding means for the patient
            - assess severity, risk or urgency
            - give a prognosis
            - recommend continuing, stopping or changing any medication
            - infer anything the report does not say

            A qualified clinician reads your output and draws every conclusion themselves. If you
            state a conclusion, you have failed the task.

            Extract:
            - reportType: echo back the report type given below
            - statedFindings: each finding the report states, in the report's own terms
            - sourceExcerpt: the passage the findings were drawn from, quoted from the report
            - extractionNote: anything unclear or ambiguous in this report, or an empty string

            [Report type]: %s

            [Report text]
            ---------------------
            %s
            ---------------------
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;

    /**
     * Creates the extractor.
     *
     * @param chatModelProvider provider for the chat model; empty when {@code spring.ai.model.chat}
     *                          is not enabled
     * @throws NullPointerException if {@code chatModelProvider} is {@code null}
     */
    public LlmClinicalFactExtractor(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider =
                Objects.requireNonNull(chatModelProvider, "chatModelProvider must not be null");
    }

    @Override
    public ClinicalFindings extract(ClinicalReport report) {
        Objects.requireNonNull(report, "report must not be null");

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new BizException(ErrorCode.LLM_SERVICE_ERROR,
                    "chat model not configured; enable spring.ai.model.chat and provide an API key");
        }

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        String prompt = PROMPT_TEMPLATE.formatted(report.reportType(), report.text());

        try {
            ClinicalFindings findings = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(ClinicalFindings.class);

            if (findings == null) {
                log.warn("model returned no usable findings for report type {}", report.reportType());
                return new ClinicalFindings(report.reportType(), List.of(), "",
                        "The model did not return a usable extraction for this report.");
            }
            return findings;
        } catch (RuntimeException ex) {
            log.error("fact extraction failed for report type {}", report.reportType(), ex);
            throw new BizException(ErrorCode.LLM_SERVICE_ERROR,
                    "failed to extract facts from the " + report.reportType() + " report", ex);
        }
    }
}
