package com.med.qa.config;

import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Gives Flyway its own direct MySQL connection, bypassing ShardingSphere-JDBC entirely.
 *
 * <p>ShardingSphere validates every SQL statement (including Flyway's own bookkeeping
 * queries against flyway_schema_history) against its cached table metadata, and rejects
 * anything it doesn't recognize with its own exception type instead of a plain "table not
 * found" that Flyway knows how to handle. Schema migrations therefore run against the real
 * MySQL connection here; the application's runtime queries continue to use the
 * ShardingSphere-wrapped DataSource as configured in med-sharding.yaml.</p>
 */

/**
 * Declares BOTH of the application's datasources explicitly.
 *
 * <p>Defining any custom {@code DataSource} bean (like flywayDataSource() below) suppresses Spring
 * Boot's own {@code DataSourceAutoConfiguration}, which is what would normally build the real,
 * ShardingSphere-routed datasource from the spring.datasource.* properties in application.yml. So
 * once we introduced a Flyway-specific datasource, we became responsible for re-declaring the app's
 * real datasource ourselves too — marked {@code @Primary} so every mapper/service in the app keeps
 * using the sharded connection, while only Flyway's migrations use the plain one below.</p>
 */

@Configuration
public class FlywayConfig {

    /**
     * The app's real datasource — identical to what application.yml's spring.datasource.* properties
     * describe, just built explicitly now instead of by Spring Boot's own autoconfiguration.
     */

    @Primary
    @Bean
    public DataSource dataSource() {
        return DataSourceBuilder.create()
                .driverClassName("org.apache.shardingsphere.driver.ShardingSphereDriver")
                .url("jdbc:shardingsphere:classpath:sharding/med-sharding.yaml?placeholder-type=environment")
                .build();
    }

    /**
     * Flyway's own direct MySQL connection, bypassing ShardingSphere entirely (see the class-level
     * rationale from when this was first added).
     */

    @FlywayDataSource
    @Bean
    public DataSource flywayDataSource() {
        String host = env("MED_MYSQL_HOST", "127.0.0.1");
        String port = env("MED_MYSQL_PORT", "3306");
        String database = env("MED_MYSQL_DATABASE", "med_qa");
        String username = env("MED_MYSQL_USERNAME", "med_qa");
        String password = env("MED_MYSQL_PASSWORD", "med_qa");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true";

        return DataSourceBuilder.create()
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .url(url)
                .username(username)
                .password(password)
                .build();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
