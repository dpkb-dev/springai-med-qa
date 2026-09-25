package com.med.qa.service.claims;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.controller.dto.ClaimEligibilityRequest;
import com.med.qa.controller.dto.ClaimEligibilityResponse;
import com.med.qa.controller.dto.ClaimItemAssessment;
import com.med.qa.controller.dto.ClaimLineItem;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates a reimbursement-eligibility check: for each claimed line item, retrieves the single
 * most relevant ingested policy clause via {@link MedRetrievalService}, asks the model to extract
 * structured facts from that clause ({@link ClauseAnalysis}), then hands those facts to
 * {@link CapCalculator} — plain, deterministic Java — to compute the actual monetary cap and decide
 * the final verdict.
 *
 * <h2>Why the model never decides the verdict or does any arithmetic</h2>
 * <p>Two different models — or two runs of the same model — are not guaranteed to compute the same
 * percentage-of-sum-insured cap, or reach the same coverage conclusion, from identical input. That is
 * unacceptable for a calculation that affects a real payout amount. So the model's role here is
 * deliberately narrow: turn unstructured clause text into a handful of structured fields (a task LLMs
 * are genuinely reliable at). The multiplication, the cap comparison, and the final
 * covered/excluded/needs-review verdict all happen in {@link CapCalculator} — ordinary, testable Java
 * that produces the exact same answer every time for the same inputs, regardless of which model or
 * model version extracted the underlying facts.</p>
 *
 * <h2>Why request/response, not streaming</h2>
 * <p>A claims reviewer needs the complete, structured assessment for every line item together — not
 * a token-by-token narrative for one item at a time — so this service uses {@code .call()}, not
 * {@code .stream()}.</p>
 *
 * <h2>Why this builds its own ChatClient from ChatModel, not the shared ChatClient.Builder</h2>
 * <p>The application's shared, dependency-injected {@code ChatClient.Builder} has
 * {@code MessageChatMemoryAdvisor} attached as a default advisor (see {@code ChatMemoryConfig}), for
 * the medical-consultation use case. This service's calls are deliberately stateless, one-shot
 * fact-extraction calls with no conversation id — running them through the memory advisor without one
 * throws, since it falls back to Spring AI's literal {@code "default"} conversation id, which this
 * project's own {@code SessionCoordinate.parse()} correctly rejects as not matching
 * {@code tenant:dept:session}. Building via the static {@link ChatClient#builder(ChatModel)} factory
 * instead constructs a completely fresh client with no advisors at all, sidestepping the shared
 * builder's customizer.</p>
 *
 * <h2>Design boundary — advisory only, never a decision of record</h2>
 * <p>Every response carries {@link ClaimEligibilityResponse#ADVISORY_DISCLAIMER}. This service has no
 * write access to any claims-processing table and never persists a verdict anywhere that could be
 * mistaken for an automated approval/denial system.</p>
 *
 * <h2>Why the matched clause is never asked of the model</h2>
 * <p>The clause text shown to the reviewer in {@link ClaimItemAssessment#matchedClause()} always comes
 * directly from the retrieved {@link Document}, never from the model echoing it back — so the excerpt
 * a reviewer sees is guaranteed to be the real source text, not a paraphrase.</p>
 */
@Service
public class ClaimEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(ClaimEligibilityService.class);

    private static final String PROMPT_TEMPLATE = """
            You are a policy-clause analysis assistant. Your ONLY job is to extract structured facts
            from the policy clause below. You must NOT decide a coverage outcome and you must NOT
            perform any calculation. All monetary arithmetic and the final coverage decision are
            performed separately by deterministic business logic, not by you.

            Do not use any knowledge of insurance policies other than the clause text given here.

            Based ONLY on the policy clause below, extract:
            - applicability: exactly one of COVERED_NO_LIMIT, COVERED_WITH_CAP, EXCLUDED, UNCLEAR
            - capBasis: only when applicability is COVERED_WITH_CAP, exactly one of
              PERCENT_OF_SUM_INSURED_PER_DAY, PERCENT_OF_SUM_INSURED_FLAT, FLAT_AMOUNT; otherwise null
            - capValue: the raw numeric value stated in the clause (for example 1 for "1%%", or a flat
              currency figure); null when capBasis is null
            - clauseSummary: a short, plain-language summary of what the clause says about this item

            [Policy Clause]
            ---------------------
            %s
            ---------------------

            Claimed item: %s
            """;

    private static final String NO_CLAUSE_FOUND_RATIONALE =
            "No relevant policy clause was found for this item.";

    private final MedRetrievalService retrievalService;

    private final ObjectProvider<ChatModel> chatModelProvider;

    /**
     * Creates the service.
     *
     * @param retrievalService  tag-scoped policy retrieval, must not be {@code null}
     * @param chatModelProvider provider for the raw chat model; empty when {@code spring.ai.model.chat}
     *                          is not enabled
     * @throws NullPointerException if {@code retrievalService} is {@code null}
     */
    public ClaimEligibilityService(
            MedRetrievalService retrievalService, ObjectProvider<ChatModel> chatModelProvider) {
        this.retrievalService = Objects.requireNonNull(retrievalService, "retrievalService must not be null");
        this.chatModelProvider = chatModelProvider;
    }

    /**
     * Assesses every line item of a claim against the ingested policy corpus.
     *
     * @param request the claim scope, sum insured, and line items, must already be validated
     * @return one assessment per line item, in the same order as the request, plus the fixed
     *         advisory disclaimer
     * @throws BizException {@link ErrorCode#LLM_SERVICE_ERROR} if the chat model is not configured or
     *                       a model call fails
     */
    public ClaimEligibilityResponse assess(ClaimEligibilityRequest request) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new BizException(ErrorCode.LLM_SERVICE_ERROR,
                    "chat model not configured; enable spring.ai.model.chat and provide an API key");
        }
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        MedDocumentScope scope = (request.patientId() == null || request.patientId().isBlank())
                ? MedDocumentScope.ofDepartment(request.tenantId(), request.deptId())
                : MedDocumentScope.ofPatient(request.tenantId(), request.deptId(), request.patientId());

        List<ClaimItemAssessment> assessments = new ArrayList<>(request.items().size());
        for (ClaimLineItem item : request.items()) {
            assessments.add(assessOne(chatClient, scope, item, request.sumInsured()));
        }
        return new ClaimEligibilityResponse(request.claimId(), assessments,
                ClaimEligibilityResponse.ADVISORY_DISCLAIMER);
    }

    private ClaimItemAssessment assessOne(
            ChatClient chatClient, MedDocumentScope scope, ClaimLineItem item, BigDecimal sumInsured) {
        List<Document> matches;
        try {
            matches = retrievalService.search(item.description(), scope);
        } catch (RuntimeException ex) {
            log.error("policy clause retrieval failed for item: {}", item.description(), ex);
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to search policy documents for claimed item: " + item.description(), ex);
        }

        if (matches.isEmpty()) {
            log.debug("no policy clause matched for claimed item: {}", item.description());
            return new ClaimItemAssessment(item.description(), item.amount(),
                    "NEEDS_MANUAL_REVIEW", NO_CLAUSE_FOUND_RATIONALE, null);
        }

        Document best = matches.get(0);
        String clauseText = best.getText() == null || best.getText().isBlank()
                ? NO_CLAUSE_FOUND_RATIONALE : best.getText();

        ClauseAnalysis analysis = extractClauseFacts(chatClient, clauseText, item);

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, sumInsured, item.amount(), item.days());

        String rationale = analysis.clauseSummary() + " " + outcome.calculationNote();
        return new ClaimItemAssessment(
                item.description(), item.amount(), outcome.verdict(), rationale, clauseText);
    }

    private ClauseAnalysis extractClauseFacts(ChatClient chatClient, String clauseText, ClaimLineItem item) {
        String prompt = PROMPT_TEMPLATE.formatted(clauseText, item.description());
        try {
            ClauseAnalysis analysis = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(ClauseAnalysis.class);
            if (analysis == null) {
                log.warn("model returned no usable clause analysis for item: {}", item.description());
                return new ClauseAnalysis("UNCLEAR", null, null,
                        "The model did not return a usable analysis of the matched clause.");
            }
            return analysis;
        } catch (RuntimeException ex) {
            log.error("clause analysis failed for item: {}", item.description(), ex);
            throw new BizException(ErrorCode.LLM_SERVICE_ERROR,
                    "failed to analyze policy clause for claimed item: " + item.description(), ex);
        }
    }
}
