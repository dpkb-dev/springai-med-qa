package com.med.qa.service.claims;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.controller.dto.ClaimConsistencyRequest;
import com.med.qa.controller.dto.ClaimConsistencyResponse;
import com.med.qa.controller.dto.ClaimDocument;
import com.med.qa.controller.dto.ConsistencyFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates a claim document consistency check: for each submitted document, asks the model to
 * extract structured facts ({@link DocumentFacts}) from that document's text alone, then hands the
 * full set of per-document facts to {@link ConsistencyChecker} — plain, deterministic Java — to find
 * any name mismatch or impossible date ordering.
 *
 * <h2>Why the model is called once per document, never across documents</h2>
 * <p>Each model call sees exactly one document's text and nothing else — it has no way to compare
 * documents even if asked to, because it is never shown more than one at a time. All comparison
 * happens afterward, in {@link ConsistencyChecker}, over the collected facts. This mirrors
 * {@link ClaimEligibilityService}'s split between fact-extraction (the model's job) and decision-making
 * (deterministic Java's job).</p>
 *
 * <h2>Why this builds its own ChatClient from ChatModel</h2>
 * <p>Same reason as {@link ClaimEligibilityService}: the shared, dependency-injected
 * {@code ChatClient.Builder} carries {@code MessageChatMemoryAdvisor} as a default advisor, meant for
 * the medical-consultation use case. These calls are stateless, one-shot extractions with no
 * conversation id, so building via the static {@link ChatClient#builder(ChatModel)} factory avoids
 * that advisor entirely.</p>
 *
 * <h2>Design boundary — advisory only, never a genuineness or fraud determination</h2>
 * <p>Every response carries {@link ClaimConsistencyResponse#ADVISORY_DISCLAIMER}. This service flags
 * objective, structural disagreements (names, date ordering) for a human reviewer; it never claims to
 * verify document authenticity, and an empty findings list does not mean the documents are genuine.</p>
 */
@Service
public class ClaimConsistencyService {

    private static final Logger log = LoggerFactory.getLogger(ClaimConsistencyService.class);

    private static final String PROMPT_TEMPLATE = """
            You are a document-fact extraction assistant. Your ONLY job is to extract structured facts
            from the single claim document below. You must NOT compare this document to any other
            document and you must NOT make any judgment about consistency, genuineness, or validity.
            Any comparison is performed separately by deterministic logic, not by you.

            Do not use any knowledge other than the text given here.

            Based ONLY on the document text below, extract:
            - patientName: the patient's full name as it appears in the document, or null if not stated
            - admissionDate: the admission date exactly as written in the document (do not reformat
              it), or null if not stated
            - dischargeDate: the discharge date exactly as written in the document, or null if not
              stated
            - extractionNote: a short note on anything unclear or ambiguous in this document, or an
              empty string if nothing is unclear

            [Document type]: %s

            [Document text]
            ---------------------
            %s
            ---------------------
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;

    /**
     * Creates the service.
     *
     * @param chatModelProvider provider for the raw chat model; empty when {@code spring.ai.model.chat}
     *                          is not enabled
     */
    public ClaimConsistencyService(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = Objects.requireNonNull(chatModelProvider, "chatModelProvider must not be null");
    }

    /**
     * Extracts facts from every document in the bundle and deterministically checks them for
     * inconsistencies.
     *
     * @param request the document bundle, must already be validated (at least two documents)
     * @return every flagged finding, plus the fixed advisory disclaimer
     * @throws BizException {@link ErrorCode#LLM_SERVICE_ERROR} if the chat model is not configured or
     *                       a model call fails
     */
    public ClaimConsistencyResponse check(ClaimConsistencyRequest request) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new BizException(ErrorCode.LLM_SERVICE_ERROR,
                    "chat model not configured; enable spring.ai.model.chat and provide an API key");
        }
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        List<ConsistencyChecker.DocumentWithFacts> documentsWithFacts = new ArrayList<>(request.documents().size());
        for (ClaimDocument document : request.documents()) {
            DocumentFacts facts = extractFacts(chatClient, document);
            documentsWithFacts.add(new ConsistencyChecker.DocumentWithFacts(document.documentType(), facts));
        }

        List<ConsistencyFinding> findings = ConsistencyChecker.check(documentsWithFacts);
        return new ClaimConsistencyResponse(request.claimId(), findings, ClaimConsistencyResponse.ADVISORY_DISCLAIMER);
    }

    private DocumentFacts extractFacts(ChatClient chatClient, ClaimDocument document) {
        String prompt = PROMPT_TEMPLATE.formatted(document.documentType(), document.text());
        try {
            DocumentFacts facts = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(DocumentFacts.class);
            if (facts == null) {
                log.warn("model returned no usable facts for document: {}", document.documentType());
                return new DocumentFacts(null, null, null, "The model did not return a usable extraction.");
            }
            return facts;
        } catch (RuntimeException ex) {
            log.error("fact extraction failed for document: {}", document.documentType(), ex);
            throw new BizException(ErrorCode.LLM_SERVICE_ERROR,
                    "failed to extract facts from document: " + document.documentType(), ex);
        }
    }
}
