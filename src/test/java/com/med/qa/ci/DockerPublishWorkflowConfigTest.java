package com.med.qa.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard tests for the D29 GitHub Actions docker-publish workflow definition.
 *
 * <p>These tests parse {@code .github/workflows/docker-publish.yml} and assert
 * the contract of the iteration: triggered on semantic-version tags, pushes the
 * image built by the D27 multi-stage Dockerfile to GitHub Container Registry
 * (ghcr.io) with least-privilege permissions. Any breaking edit to the pipeline
 * fails the build locally before it ever reaches GitHub. No Docker daemon or
 * network access is required.</p>
 */
class DockerPublishWorkflowConfigTest {

    private static Map<String, Object> workflow;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadWorkflow() throws IOException {
        Path file = workflowPath();
        try (InputStream in = Files.newInputStream(file)) {
            workflow = new Yaml().load(in);
        }
    }

    private static Path workflowPath() {
        // Maven surefire runs with working directory = project basedir.
        return Path.of(System.getProperty("user.dir"), ".github", "workflows", "docker-publish.yml");
    }

    @Test
    @DisplayName("workflow file exists and parses as a non-empty YAML mapping")
    void workflowFileParses() {
        assertThat(Files.exists(workflowPath())).isTrue();
        assertThat(workflow).isNotNull().isNotEmpty();
        assertThat(workflow.get("name")).isEqualTo("docker-publish");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("triggers on semantic version tags and allows manual dispatch")
    void triggersOnVersionTagAndDispatch() {
        // SnakeYAML parses the bare key `on:` as boolean key TRUE (YAML 1.1).
        Object onSection = workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE);
        assertThat(onSection).as("on: trigger section").isInstanceOf(Map.class);
        Map<String, Object> on = (Map<String, Object>) onSection;

        assertThat(on).containsKey("workflow_dispatch");

        Map<String, Object> push = (Map<String, Object>) on.get("push");
        assertThat(push).as("push trigger").isNotNull();
        List<String> pushTags = (List<String>) push.get("tags");
        assertThat(pushTags).contains("v*");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("uses least-privilege permissions (read contents, write packages)")
    void leastPrivilegePermissions() {
        Map<String, Object> permissions = (Map<String, Object>) workflow.get("permissions");
        assertThat(permissions).as("permissions block").isNotNull();
        assertThat(permissions.get("contents")).isEqualTo("read");
        assertThat(permissions.get("packages")).isEqualTo("write");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("declares ghcr.io registry and github.repository image name")
    void registryAndImageName() {
        Map<String, Object> env = (Map<String, Object>) workflow.get("env");
        assertThat(env).as("env block").isNotNull();
        assertThat(env.get("REGISTRY")).isEqualTo("ghcr.io");
        assertThat(env.get("IMAGE_NAME")).isEqualTo("${{ github.repository }}");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("build-and-push job uses ubuntu-latest and the official docker actions")
    void buildAndPushJobContract() {
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        assertThat(jobs).containsKey("build-and-push");
        Map<String, Object> job = (Map<String, Object>) jobs.get("build-and-push");
        assertThat(job.get("runs-on")).isEqualTo("ubuntu-latest");

        List<Map<String, Object>> steps = (List<Map<String, Object>>) job.get("steps");
        assertThat(steps).isNotEmpty();

        // Collect the `uses:` references from every step for action assertions.
        List<String> uses = steps.stream()
                .map(s -> (String) s.get("uses"))
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(uses).anyMatch(u -> u.startsWith("docker/login-action@"));
        assertThat(uses).anyMatch(u -> u.startsWith("docker/metadata-action@"));
        assertThat(uses).anyMatch(u -> u.startsWith("docker/setup-buildx-action@"));
        assertThat(uses).anyMatch(u -> u.startsWith("docker/build-push-action@"));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("build-push step pushes the D27 Dockerfile to ghcr with gha cache")
    void buildPushStepPushesDockerfile() {
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        Map<String, Object> job = (Map<String, Object>) jobs.get("build-and-push");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) job.get("steps");

        Map<String, Object> buildPush = steps.stream()
                .filter(s -> {
                    String u = (String) s.get("uses");
                    return u != null && u.startsWith("docker/build-push-action@");
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("docker/build-push-action step missing"));

        Map<String, Object> with = (Map<String, Object>) buildPush.get("with");
        assertThat(with).as("build-push with: block").isNotNull();
        assertThat(with.get("push")).isEqualTo(true);
        assertThat(with.get("file")).isEqualTo("./Dockerfile");
        assertThat(with.get("context")).isEqualTo(".");
        assertThat(with.get("cache-from")).isEqualTo("type=gha");
        assertThat(with.get("cache-to")).isEqualTo("type=gha,mode=max");
    }
}
