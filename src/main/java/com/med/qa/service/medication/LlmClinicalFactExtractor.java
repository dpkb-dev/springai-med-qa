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

    /**
     * Appended to {@code extractionNote} when the returned excerpt cannot be found in the report, so
     * a clinician reading the findings knows the excerpt cannot be relied on for verification.
     */
    static final String EXCERPT_NOT_VERBATIM_NOTE =
            "WARNING: the quoted source excerpt could not be found verbatim in the submitted report. "
                    + "Verify these findings against the original document directly.";

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
            return verifyExcerpt(findings, report);
        } catch (RuntimeException ex) {
            log.error("fact extraction failed for report type {}", report.reportType(), ex);
            throw new BizException(ErrorCode.LLM_SERVICE_ERROR,
                    "failed to extract facts from the " + report.reportType() + " report", ex);
        }
    }

    /**
     * Confirms the excerpt the model returned actually appears in the report it was given.
     *
     * <p>The prompt asks the model to quote the source passage, but asking is not the same as
     * knowing. {@code sourceExcerpt} is the clinician's audit trail — it is what lets them check an
     * extracted finding against the original without re-reading the whole report — so an excerpt
     * that was paraphrased, or invented outright, silently breaks the verification step it exists to
     * enable. A model that quotes correctly on nine reports and paraphrases on the tenth is the
     * dangerous case, because nothing in the output would look different.</p>
     *
     * <p>Whitespace is normalised before comparison, since a model reflowing line breaks from a
     * report is a formatting difference rather than a fabrication. When the excerpt genuinely does
     * not appear, the finding is returned with a warning appended to {@code extractionNote} rather
     * than discarded: the stated findings may still be accurate, and a clinician who is told the
     * excerpt is unreliable can judge that for themselves. Silently dropping the excerpt would hide
     * the problem instead of surfacing it.</p>
     *
     * @param findings the model's extraction
     * @param report   the report it was given
     * @return the findings unchanged when the excerpt is verbatim, or with a warning note appended
     */
    private ClinicalFindings verifyExcerpt(ClinicalFindings findings, ClinicalReport report) {
        String excerpt = findings.sourceExcerpt();
        if (excerpt == null || excerpt.isBlank()) {
            return findings;
        }
        if (normaliseWhitespace(report.text()).contains(normaliseWhitespace(excerpt))) {
            return findings;
        }

        log.warn("sourceExcerpt for report type {} does not appear verbatim in the submitted report",
                report.reportType());

        String note = findings.extractionNote() == null || findings.extractionNote().isBlank()
                ? EXCERPT_NOT_VERBATIM_NOTE
                : findings.extractionNote() + " " + EXCERPT_NOT_VERBATIM_NOTE;

        return new ClinicalFindings(
                findings.reportType(), findings.statedFindings(), findings.sourceExcerpt(), note);
    }

    private static String normaliseWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
