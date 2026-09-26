package com.med.qa.integration;

import com.med.qa.config.EmbeddingModelConfig;
import com.med.qa.config.VectorStoreConfig;
import com.med.qa.controller.dto.ClaimEligibilityRequest;
import com.med.qa.controller.dto.ClaimEligibilityResponse;
import com.med.qa.controller.dto.ClaimItemAssessment;
import com.med.qa.controller.dto.ClaimLineItem;
import com.med.qa.rag.MedDocumentIngestionProperties;
import com.med.qa.rag.MedDocumentRequest;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedDocumentService;
import com.med.qa.rag.MedEmbeddingProperties;
import com.med.qa.rag.MedRetrievalProperties;
import com.med.qa.rag.MedRetrievalService;
import com.med.qa.rag.MedVectorStoreProperties;
import com.med.qa.service.claims.ClaimEligibilityService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-infrastructure integration test for the reimbursement-eligibility feature: a real Redis Stack
 * container (Testcontainers), a real OpenAI embedding call, and the actual production
 * {@link MedDocumentService} / {@link MedRetrievalService} / {@link ClaimEligibilityService} classes
 * wired together exactly as {@link VectorStoreConfig} and {@link EmbeddingModelConfig} build them in
 * production — only {@link ChatModel} is mocked.
 *
 * <h2>Why the embedding call is real, but the chat model is not</h2>
 * <p>Every other test in this project's claims feature mocks retrieval entirely, so nothing so far
 * has actually proven that a real policy clause, real-embedded and real-indexed in Redis, is
 * genuinely found by a real semantic search for a related question — as opposed to a rigged, fake
 * vector match that would pass even if the metadata filter or the search request assembly were
 * subtly wrong. The embedding call costs a fraction of a cent and is fast; the chat model call would
 * be slower, non-deterministic, and unnecessary to prove the wiring is correct, so it stays mocked,
 * matching {@link com.med.qa.service.claims.ClaimEligibilityServiceTest}'s own approach.</p>
 *
 * <h2>Why this class does not boot the Spring context</h2>
 * <p>Same reasoning as {@link MedStorageAndLockIntegrationTest}: every production factory method used
 * here ({@link VectorStoreConfig#buildVectorStore}, {@link VectorStoreConfig#buildJedisPooled},
 * {@link EmbeddingModelConfig#buildOpenAiApi}, {@link EmbeddingModelConfig#buildEmbeddingModel}) is
 * exposed as a {@code static} method specifically so it can be exercised without a full
 * {@code @SpringBootTest}, entirely sidestepping the eager {@code openAiChatModel} bean-construction
 * issue this project's local dev setup hit repeatedly.</p>
 *
 * <h2>Requirements to actually run this class</h2>
 * <ul>
 *   <li>Docker, for the Redis Stack container — gated by {@link DockerAvailableCondition}, same as
 *       {@link MedStorageAndLockIntegrationTest}: skips the whole class gracefully when no daemon is
 *       reachable, so an offline {@code mvn test} run stays green;</li>
 *   <li>a real {@code OPENAI_API_KEY} environment variable — if absent, this class's tests are
 *       skipped individually (not the whole suite) via {@link org.junit.jupiter.api.Assumptions},
 *       since a missing key is a legitimate local-dev state, not a build failure.</li>
 * </ul>
 */
@ExtendWith(DockerAvailableCondition.class)
class MedClaimsIntegrationTest {

    private static GenericContainer<?> redis;

    private static VectorStore vectorStore;

    private static MedDocumentService documentService;

    private static MedRetrievalService retrievalService;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        redis = new GenericContainer<>(DockerImageName.parse("redis/redis-stack:7.4.0-v3"))
                .withExposedPorts(6379);
        redis.start();

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            // Leave the store/service fields null; each @Test method checks this itself via
            // assumeTrue so the reason is reported per-test, not swallowed by a static-init failure.
            return;
        }

        RedisProperties redisProperties = new RedisProperties();
        redisProperties.setHost(redis.getHost());
        redisProperties.setPort(redis.getMappedPort(6379));
        JedisPooled jedis = VectorStoreConfig.buildJedisPooled(redisProperties);

        OpenAiConnectionProperties connectionProperties = new OpenAiConnectionProperties();
        connectionProperties.setApiKey(apiKey);
        connectionProperties.setBaseUrl("https://api.openai.com");

        OpenAiEmbeddingProperties embeddingProperties = new OpenAiEmbeddingProperties();
        embeddingProperties.getOptions().setModel("text-embedding-3-small");
        embeddingProperties.getOptions().setDimensions(1536);

        OpenAiApi openAiApi = EmbeddingModelConfig.buildOpenAiApi(
                connectionProperties, embeddingProperties, RestClient.builder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);

        EmbeddingModel embeddingModel = EmbeddingModelConfig.buildEmbeddingModel(
                openAiApi, embeddingProperties, new MedEmbeddingProperties(),
                RetryUtils.DEFAULT_RETRY_TEMPLATE, ObservationRegistry.NOOP);

        // A dedicated index/prefix, isolated from any real dev/prod data that might share this Redis.
        MedVectorStoreProperties vectorStoreProperties = new MedVectorStoreProperties();
        vectorStoreProperties.setIndexName("med-doc-index-it-claims");
        vectorStoreProperties.setPrefix("med:doc:it:claims:");

        vectorStore = VectorStoreConfig.buildVectorStore(jedis, embeddingModel, vectorStoreProperties, null);
        // No Spring container manages this bean here, so nobody calls afterPropertiesSet()
        // automatically the way a real @Bean lifecycle would -- and that method is exactly what
        // issues FT.CREATE against Redis (per VectorStoreConfig's own class javadoc). Without this
        // explicit call, the index genuinely never gets created, and every search fails with
        // "no such index" even though ingestion succeeds (a plain write needs no index at all).
        ((org.springframework.beans.factory.InitializingBean) vectorStore).afterPropertiesSet();

        documentService = new MedDocumentService(
                vectorStore, vectorStoreProperties, new MedDocumentIngestionProperties(), java.time.Clock.systemUTC());
        retrievalService = new MedRetrievalService(vectorStore, new MedRetrievalProperties());
    }

    @AfterAll
    static void stopInfrastructure() {
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    @DisplayName("a real-ingested policy clause is found by real semantic search and correctly drives a real eligibility verdict")
    void realIngestedClauseDrivesRealEligibilityAssessment() {
        assumeTrue(retrievalService != null,
                "OPENAI_API_KEY must be set to run this test (it makes a real, low-cost embedding call)");

        MedDocumentScope scope = MedDocumentScope.ofDepartment("ittenant", "itclaims");
        String documentId = documentService.ingest(MedDocumentRequest.of(
                "Room rent and boarding expenses are covered up to 1% of the sum insured per day. "
                        + "Over-the-counter medications not prescribed by a registered practitioner "
                        + "are excluded from reimbursement.",
                scope));
        assertThat(documentId).isNotBlank();

        // Confirms real semantic search independently of the claims service, before trusting the
        // full pipeline built on top of it.
        List<Document> directMatches = retrievalService.search(
                "Is ibuprofen bought without a prescription covered?", scope);
        assertThat(directMatches).isNotEmpty();
        assertThat(directMatches.get(0).getText()).contains("Over-the-counter medications");

        // Now the actual production service, with only the chat model mocked.
        ChatModel chatModel = mock(ChatModel.class);
        String clauseAnalysisJson = """
                {"applicability":"EXCLUDED","capBasis":null,"capValue":null,\
                "clauseSummary":"OTC medicine without a prescription is excluded."}""";
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(List.of(
                        new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage(clauseAnalysisJson)))));

        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ClaimEligibilityService service = new ClaimEligibilityService(retrievalService, chatModelProvider);

        ClaimEligibilityRequest request = new ClaimEligibilityRequest(
                "ittenant", "itclaims", null, "it-claim-1", new BigDecimal("300000"),
                List.of(new ClaimLineItem("Ibuprofen purchased over the counter, no prescription",
                        new BigDecimal("250"), null)));

        ClaimEligibilityResponse response = service.assess(request);

        ClaimItemAssessment assessment = response.assessments().get(0);
        assertThat(assessment.verdict()).isEqualTo("LIKELY_EXCLUDED");
        assertThat(assessment.matchedClause()).contains("Over-the-counter medications");
    }

    @Test
    @DisplayName("the tenant/department scope filter genuinely isolates documents in real Redis, not just in mocked logic")
    void scopeFilterIsolatesDocumentsInRealRedis() {
        assumeTrue(retrievalService != null,
                "OPENAI_API_KEY must be set to run this test (it makes a real, low-cost embedding call)");

        MedDocumentScope scopeA = MedDocumentScope.ofDepartment("ittenanta", "itdepta");
        MedDocumentScope scopeB = MedDocumentScope.ofDepartment("ittenantb", "itdeptb");

        documentService.ingest(MedDocumentRequest.of(
                "Dental treatment is excluded from this policy except for accidental injury.", scopeA));

        List<Document> matchesForB = retrievalService.search("Is dental treatment covered?", scopeB);

        assertThat(matchesForB).isEmpty();
    }
}
