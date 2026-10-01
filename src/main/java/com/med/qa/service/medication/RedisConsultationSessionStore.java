package com.med.qa.service.medication;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.RedisConfig;
import com.med.qa.controller.dto.ClinicalReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Stores consultation reports in Redis under a key that expires with the session.
 *
 * <h2>Key namespace</h2>
 * <p>{@code med:consult:{sessionId}} — deliberately distinct from the {@code med:chat:} namespace
 * used by conversation memory and from {@code med:doc:} used by the RAG vector store, so a
 * consultation's private clinical reports can never be reached by a chat-memory or document lookup.</p>
 *
 * <h2>The TTL is the privacy control</h2>
 * <p>Expiry is not a cache-eviction detail here, it is how blueprint rule BR-5 is enforced in
 * practice: reports simply cease to exist once the consultation is over, so there is nothing to leak
 * into a later session and nothing to retain indefinitely. The default is deliberately short — a
 * consultation is a single sitting, not an open-ended record.</p>
 */
@Component
public class RedisConsultationSessionStore implements ConsultationSessionStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConsultationSessionStore.class);

    private static final String KEY_PREFIX = "med:consult:";

    private static final TypeReference<List<ClinicalReport>> REPORT_LIST_TYPE =
            new TypeReference<>() {
            };

    private final RedisTemplate<String, byte[]> redisTemplate;

    private final ObjectMapper objectMapper;

    private final Duration ttl;

    /**
     * Creates the store.
     *
     * @param redisTemplate the byte-array Redis template shared with the message cache
     * @param objectMapper  JSON serialization, reusing the application's configured mapper
     * @param ttl           how long a consultation's reports survive, defaulting to two hours
     */
    public RedisConsultationSessionStore(
            @Qualifier(RedisConfig.MESSAGE_REDIS_TEMPLATE) RedisTemplate<String, byte[]> redisTemplate,
            ObjectMapper objectMapper,
            @Value("${med.consult.report-ttl:PT2H}") Duration ttl) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    }

    @Override
    public void saveReports(String sessionId, List<ClinicalReport> reports) {
        requireSessionId(sessionId);
        Objects.requireNonNull(reports, "reports must not be null");
        try {
            byte[] payload = objectMapper.writeValueAsBytes(reports);
            redisTemplate.opsForValue().set(key(sessionId), payload, ttl);
        } catch (Exception ex) {
            log.error("failed to store consultation reports for session {}", sessionId, ex);
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to store the submitted reports for this consultation", ex);
        }
    }

    @Override
    public List<ClinicalReport> findReports(String sessionId) {
        requireSessionId(sessionId);
        try {
            byte[] payload = redisTemplate.opsForValue().get(key(sessionId));
            if (payload == null || payload.length == 0) {
                return List.of();
            }
            return objectMapper.readValue(new String(payload, StandardCharsets.UTF_8), REPORT_LIST_TYPE);
        } catch (Exception ex) {
            log.error("failed to read consultation reports for session {}", sessionId, ex);
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to read the reports held for this consultation", ex);
        }
    }

    @Override
    public boolean hasReports(String sessionId) {
        requireSessionId(sessionId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(sessionId)));
    }

    @Override
    public void evict(String sessionId) {
        requireSessionId(sessionId);
        redisTemplate.delete(key(sessionId));
    }

    private static String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }
}
