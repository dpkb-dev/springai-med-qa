# springai-med-qa

A production-grade hospital AI consultation backend built on **Spring Boot 3 + Spring AI**.

> A "front-line business system" for medical AI Q&A plus distributed conversation-memory storage. The storage conventions, field definitions, 
> and serialization protocol are strictly aligned with [`med-langchain-memory`](https://github.com/xxinjie21/med-langchain-memory)
> (the Python-based underlying middleware) — the two repos' data is interoperable, but the code is entirely independent.

---

## Core Positioning

| Dimension | Description |
|---|---|
| Role | AI consultation Q&A backend (a working system for patients / doctors) |
| Storage foundation | Reuses one unified medical-session storage spec (Redis keys, 16-way MySQL sharding, Protobuf) |
| Capabilities | Streaming consultation, RAG medical knowledge retrieval, permission checks, operation auditing, privacy masking |
| Technical approach | Built entirely on mature commercial / mainstream open-source components — no reinventing lower-level wheels |

---

## Tech Stack

| Capability | Component | Notes |
|---|---|---|
| Base framework | Spring Boot 3.4.x | Web / IoC / transactions |
| AI invocation & streaming output | Spring AI | ChatClient, SSE streaming, EmbeddingModel |
| Vector storage & RAG | Spring AI `RedisVectorStore` + `QuestionAnswerAdvisor` | Cosine-similarity search + metadata tag filtering |
| MySQL sharding | ShardingSphere-JDBC | Only a custom `crc32(session_id) % 16` sharding-algorithm plugin is hand-written; routing itself is delegated to the framework |
| Distributed lock / rate limiting | Redisson (`RLock` / `RRateLimiter`) | Session concurrency locking, API rate limiting |
| ORM | MyBatis + Flyway | Data access and versioned table creation |
| Data masking | Hutool `DesensitizedUtil` | Masks ID numbers / phone numbers / medical record numbers |
| API docs | springdoc-openapi | Swagger UI |
| Testing | JUnit 5 + Mockito + H2 | Unit tests don't depend on real middleware |
| Coverage | JaCoCo | Bound to the `verify` phase; report uploaded as a CI artifact |

---

## Module Structure

```
src/main/java/com/med/qa/
├── common/            # Shared: unified ApiResult response, BizException/ErrorCode, global exception handling
├── domain/            # Domain entities: ChatMessageDO, ChatSessionDO, RoleType/SessionStatus enums
├── config/            # Spring configuration classes
├── memory/            # Custom ChatMemory repository + ShardingSphere sharding algorithm
│   └── sharding/      # Crc32ShardingAlgorithm (crc32(session_id) % 16)
├── rag/                # Spring AI vector retrieval & RAG Advisor wiring
├── security/          # Patient session permission checks, Redisson distributed locking / rate limiting
├── privacy/            # Hutool masking annotations & aspect
├── audit/              # Medical operation audit logging
├── service/            # Business orchestration
├── controller/         # REST / SSE API layer
└── MedQaApplication.java

src/main/resources/
├── application.yml            # Shared config
├── application-dev.yml        # Dev environment
├── application-prod.yml       # Prod environment
├── sharding/med-sharding.yaml # ShardingSphere sharding rules
└── META-INF/services/...      # Sharding-algorithm SPI registration
```

---

## Unified Storage Interop Spec (interoperable with med-langchain-memory)

To keep data interoperable across heterogeneous systems, both repos strictly follow the same spec:

| Item | Rule |
|---|---|
| Redis key | `med:chat:{tenant}:{dept}:{session}` |
| MySQL sharded tables | `med_message_{crc32(session_id) % 16}` (16 tables total) |
| Message fields | `session_id` / `tenant` / `dept` / `patient_id` / `role` / `content` / `created_at` (epoch millis, UUIDv7 primary key) |
| Serialization | Protobuf (`med_session.proto`, a cross-language unified protocol) |

---

## Local Development

```bash
# Uses the Maven Wrapper (bundled — no need to pre-install Maven locally)
./mvnw clean verify        # compile + full unit test suite + JaCoCo coverage

# Run locally (requires configuring the Redis / MySQL / LLM connections in application-dev.yml)
./mvnw spring-boot:run
```

> Unit tests use an in-memory H2 database and Mockito test doubles, so the full suite passes green without starting any middleware.

---

## CI / CD

GitHub Actions (`.github/workflows/ci.yml`) runs automatically on every push / PR to `main`:

1. `actions/checkout@v4` — checks out the code
2. `actions/setup-java@v4` — sets up Temurin JDK 17 (with Maven caching enabled)
3. `chmod +x ./mvnw` — ensures the wrapper is executable
4. `./mvnw verify` — runs the full unit test suite + generates JaCoCo coverage
5. Uploads the `target/site/jacoco` coverage report and `target/surefire-reports` test reports as build artifacts

---

## Daily Iteration Cadence

The project follows the phased task table in `ROADMAP.md`; a daily automated workflow closes the loop of "code → unit test → per-module commit → push to GitHub," with each iteration point independently committable in 30–60 minutes.

---

## License

Apache 2.0 (see `LICENSE`)
