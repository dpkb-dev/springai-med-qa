package com.med.qa.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard tests for the MySQL bootstrap script mounted into the D28 compose stack.
 *
 * <p>The script runs once on the MySQL container's first start. It must provision the {@code med_qa}
 * schema and the {@code med_qa} application account that ShardingSphere connects with, while staying
 * idempotent so an attached volume that already carries data never fails the boot.</p>
 */
class MysqlInitScriptTest {

    private static String raw;
    private static String lower;

    @BeforeAll
    static void loadScript() throws IOException {
        Path path = Path.of(System.getProperty("user.dir"), "docker", "mysql", "init", "01-create-db.sql");
        assertThat(Files.exists(path)).as("init script must exist at docker/mysql/init/01-create-db.sql").isTrue();
        raw = Files.readString(path);
        lower = raw.toLowerCase();
    }

    @Test
    @DisplayName("the med_qa database is created idempotently")
    void createsDatabase() {
        assertThat(lower).contains("create database if not exists med_qa");
    }

    @Test
    @DisplayName("the med_qa application account is created for the network and localhost")
    void createsApplicationUser() {
        assertThat(lower).contains("create user if not exists 'med_qa'@'%'");
        assertThat(lower).contains("create user if not exists 'med_qa'@'localhost'");
    }

    @Test
    @DisplayName("the application account is granted privileges on the med_qa schema")
    void grantsPrivileges() {
        assertThat(lower).contains("grant all privileges on med_qa.* to 'med_qa'@'%'");
        assertThat(lower).contains("grant all privileges on med_qa.* to 'med_qa'@'localhost'");
        assertThat(lower).contains("flush privileges");
    }

    @Test
    @DisplayName("every user-creation statement is idempotent (guards against boot failure on warm volumes)")
    void userCreationIsIdempotent() {
        // The prose comment also mentions "CREATE USER IF NOT EXISTS"; both counts still match.
        long createUserCount = countOccurrences(lower, "create user");
        long idempotentCount = countOccurrences(lower, "create user if not exists");
        assertThat(createUserCount).isGreaterThan(0);
        assertThat(idempotentCount).isEqualTo(createUserCount);
    }

    @Test
    @DisplayName("the script never drops the schema or account (no destructive statements)")
    void noDestructiveStatements() {
        assertThat(lower).doesNotContain("drop database");
        assertThat(lower).doesNotContain("drop user");
        assertThat(lower).doesNotContain("drop schema");
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
