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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guard tests for the local / single-host deployment stack defined in {@code docker-compose.yml}.
 *
 * <p>The vector store requires RediSearch and RedisJSON, which plain {@code redis:7} does not ship;
 * these tests pin that the declared cache node is a Redis Stack image. D28 added the MySQL and
 * application services, so this class now also asserts the database is health-checked and seeded by
 * an init script, and that the app only starts once both dependencies report healthy.</p>
 */
class DockerComposeConfigTest {

    private static Map<String, Object> compose;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadCompose() throws IOException {
        try (InputStream in = Files.newInputStream(composePath())) {
            compose = (Map<String, Object>) new Yaml().load(in);
        }
    }

    private static Path composePath() {
        // Maven surefire runs with working directory = project basedir.
        return Path.of(System.getProperty("user.dir"), "docker-compose.yml");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() {
        return (Map<String, Object>) compose.get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        return (Map<String, Object>) services().get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> healthcheckOf(String name) {
        return (Map<String, Object>) service(name).get("healthcheck");
    }

    @Test
    @DisplayName("the compose file exists at the project root and parses as YAML")
    void composeFileParses() {
        assertThat(Files.exists(composePath())).isTrue();
        assertThat(compose).isNotNull().containsKey("services");
    }

    @Test
    @DisplayName("the full stack declares redis-stack, mysql and app services")
    void allStackServicesDeclared() {
        assertThat(services()).containsKeys("redis-stack", "mysql", "app");
    }

    // ---- Redis Stack (introduced in D13) ------------------------------------------------

    @Test
    @DisplayName("a redis-stack service is declared with a RediSearch capable image")
    void redisStackServiceUsesStackImage() {
        Map<String, Object> service = service("redis-stack");

        assertThat(service).as("services.redis-stack must be declared").isNotNull();
        assertThat((String) service.get("image"))
                .as("plain redis images have no RediSearch module")
                .startsWith("redis/redis-stack:");
    }

    @Test
    @DisplayName("the redis port is published so the app, Redisson and the index share one node")
    @SuppressWarnings("unchecked")
    void redisPortIsPublished() {
        List<String> ports = (List<String>) service("redis-stack").get("ports");

        assertThat(ports).isNotEmpty();
        assertThat(ports).anyMatch(mapping -> mapping.endsWith(":6379"));
    }

    @Test
    @DisplayName("a health check is declared so dependent services can wait for readiness")
    @SuppressWarnings("unchecked")
    void redisHealthCheckIsDeclared() {
        Map<String, Object> healthcheck = healthcheckOf("redis-stack");

        assertThat(healthcheck).isNotNull();
        assertThat((List<String>) healthcheck.get("test")).contains("redis-cli");
        assertThat(healthcheck).containsKeys("interval", "retries");
    }

    @Test
    @DisplayName("data is kept in a named volume so the index survives a container restart")
    @SuppressWarnings("unchecked")
    void redisDataVolumeIsDeclared() {
        List<String> volumes = (List<String>) service("redis-stack").get("volumes");
        Map<String, Object> declared = (Map<String, Object>) compose.get("volumes");

        assertThat(volumes).anyMatch(mapping -> mapping.endsWith(":/data"));
        assertThat(declared).containsKey("redis-stack-data");
    }

    // ---- MySQL (introduced in D28) -----------------------------------------------------

    @Test
    @DisplayName("mysql uses a MySQL 8 image and pins a stable GA tag")
    void mysqlUsesMysql8Image() {
        assertThat((String) service("mysql").get("image"))
                .startsWith("mysql:8.0");
    }

    @Test
    @DisplayName("mysql exposes 3306 and persists data in a named volume")
    @SuppressWarnings("unchecked")
    void mysqlPortAndVolume() {
        List<String> ports = (List<String>) service("mysql").get("ports");
        assertThat(ports).anyMatch(mapping -> mapping.endsWith(":3306"));

        List<String> volumes = (List<String>) service("mysql").get("volumes");
        assertThat(volumes).anyMatch(mapping -> mapping.endsWith(":/var/lib/mysql"));
        assertThat((Map<String, Object>) compose.get("volumes")).containsKey("mysql-data");
    }

    @Test
    @DisplayName("mysql is health-checked via mysqladmin ping before dependents start")
    @SuppressWarnings("unchecked")
    void mysqlHealthCheckUsesMysqladmin() {
        Map<String, Object> healthcheck = healthcheckOf("mysql");
        assertThat(healthcheck).isNotNull();

        List<String> test = (List<String>) healthcheck.get("test");
        // CMD-SHELL form: ["CMD-SHELL", "mysqladmin ping ... --silent"]
        assertThat(test).anyMatch(token -> token.toString().contains("mysqladmin"));
        assertThat(test).anyMatch(token -> token.toString().contains("ping"));
        assertThat(healthcheck).containsKeys("interval", "timeout", "retries", "start_period");
    }

    @Test
    @DisplayName("the init script is mounted into the MySQL entrypoint init directory")
    @SuppressWarnings("unchecked")
    void mysqlInitScriptMounted() {
        List<String> volumes = (List<String>) service("mysql").get("volumes");
        assertThat(volumes).anyMatch(v -> v.contains("/docker-entrypoint-initdb.d"));
        assertThat(volumes).anyMatch(v -> v.contains("docker/mysql/init"));
    }

    // ---- Application (introduced in D28) ------------------------------------------------

    @Test
    @DisplayName("app is built from the Dockerfile at the repository root")
    @SuppressWarnings("unchecked")
    void appBuildsFromDockerfile() {
        Map<String, Object> app = service("app");
        Map<String, Object> build = (Map<String, Object>) app.get("build");

        assertThat(build).isNotNull();
        assertThat((String) build.get("context")).isEqualTo(".");
        assertThat((String) build.get("dockerfile")).isEqualTo("Dockerfile");
    }

    @Test
    @DisplayName("app waits for mysql and redis-stack to be healthy before starting")
    @SuppressWarnings("unchecked")
    void appWaitsForHealthyDependencies() {
        Map<String, Object> dependsOn = (Map<String, Object>) service("app").get("depends_on");

        assertThat(dependsOn).containsKeys("mysql", "redis-stack");

        Map<String, Object> mysqlDep = (Map<String, Object>) dependsOn.get("mysql");
        Map<String, Object> redisDep = (Map<String, Object>) dependsOn.get("redis-stack");

        assertThat((String) mysqlDep.get("condition")).isEqualTo("service_healthy");
        assertThat((String) redisDep.get("condition")).isEqualTo("service_healthy");
    }

    @Test
    @DisplayName("app injects redis and mysql connection env matching the sharding config")
    @SuppressWarnings("unchecked")
    void appConnectionEnvMatchesShardingConfig() {
        Map<String, Object> env = (Map<String, Object>) service("app").get("environment");

        assertThat(env.get("MED_MYSQL_HOST")).isEqualTo("mysql");
        assertThat(env.get("MED_MYSQL_DATABASE")).isEqualTo("med_qa");
        assertThat(env.get("MED_MYSQL_USERNAME")).isEqualTo("med_qa");
        assertThat(env.get("MED_MYSQL_PASSWORD")).isEqualTo("med_qa");
        assertThat(env.get("REDIS_HOST")).isEqualTo("redis-stack");
        assertThat(env.get("REDIS_PORT")).isEqualTo("6379");
        assertThat(env.get("SPRING_PROFILES_ACTIVE")).isEqualTo("prod");
    }

    @Test
    @DisplayName("app publishes 8080 and is health-checked through the actuator endpoint")
    @SuppressWarnings("unchecked")
    void appPortAndHealthCheck() {
        List<String> ports = (List<String>) service("app").get("ports");
        assertThat(ports).anyMatch(mapping -> mapping.endsWith(":8080"));

        Map<String, Object> healthcheck = healthcheckOf("app");
        assertThat(healthcheck).isNotNull();
        List<String> test = (List<String>) healthcheck.get("test");
        assertThat(test).anyMatch(token -> token.toString().contains("/actuator/health"));
        assertThat(healthcheck).containsKeys("interval", "timeout", "retries", "start_period");
    }

    @Test
    @DisplayName("an unknown service is absent, guarding against accidental stack edits")
    void unknownServiceIsAbsent() {
        assertThat(services()).doesNotContainKey("redis");
        assertThatThrownBy(() -> {
            Object missing = services().get("does-not-exist");
            missing.toString();
        }).isInstanceOf(NullPointerException.class);
    }
}
