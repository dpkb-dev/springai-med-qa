package com.med.qa.integration;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit 5 execution condition that disables a test container-based integration test when no Docker
 * daemon is reachable.
 *
 * <p>The D30 integration suite (real MySQL + Redis Stack via Testcontainers) must never break the
 * offline unit-test run. When Docker is absent the whole class is reported as disabled (skipped), so
 * {@code mvn test} stays green on developer machines and CI runners without a container runtime. On
 * a host that does have Docker the condition is satisfied and the integration test executes its
 * real-middleware assertions.</p>
 *
 * <p>The condition is evaluated before any {@code @BeforeAll} lifecycle method, so the Testcontainers
 * images are never pulled and the middleware is never started when Docker is unavailable.</p>
 */
public class DockerAvailableCondition implements ExecutionCondition {

    /** Reason reported when the class is disabled for lack of a Docker environment. */
    private static final String DISABLED_REASON =
            "Docker daemon not available - skipping Testcontainers integration test";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            return ConditionEvaluationResult.enabled("Docker available - integration test enabled");
        }
        return ConditionEvaluationResult.disabled(DISABLED_REASON);
    }
}
