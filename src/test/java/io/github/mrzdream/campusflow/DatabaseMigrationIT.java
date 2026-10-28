package io.github.mrzdream.campusflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.FlywayException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "DB_NAME=campus_flow_it")
class DatabaseMigrationIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @LocalServerPort
    private int port;

    @Test
    void migrationsAreRecordedAndRunningAgainDoesNotReapplyThem() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("campus_flow_it");
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("2");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1', '2') AND success = 1",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void changedMigrationChecksumIsRejected() {
        Flyway changed = Flyway.configure()
                .dataSource(flyway.getConfiguration().getDataSource())
                .locations("classpath:checksum-mismatch")
                .cleanDisabled(true)
                .load();

        assertThatThrownBy(changed::validate).isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("Migration checksum mismatch");
    }

    @Test
    void destructiveFlywayCleanIsDisabled() {
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class)
                .hasMessageContaining("cleanDisabled");
    }

    @Test
    void tablesUseInnoDbAndUtf8mb4AndConnectionUsesUtc() {
        assertThat(jdbc.queryForList(
                "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME IN ('resources', 'resource_slots')", String.class))
                .hasSize(2).containsOnly("InnoDB");
        assertThat(jdbc.queryForList(
                "SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME IN ('resources', 'resource_slots')", String.class))
                .hasSize(2).allMatch(collation -> collation.startsWith("utf8mb4_"));
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
    }

    @Test
    @Transactional
    void resourceAndSlotPersistWithExpectedDefaults() {
        long resourceId = createResource();
        createSlot(resourceId, 3, 1, "2026-11-01 09:00:00", "2026-11-01 10:00:00");

        assertThat(jdbc.queryForObject("SELECT status FROM resources WHERE id = ?",
                String.class, resourceId)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT capacity - reserved FROM resource_slots WHERE resource_id = ?",
                Integer.class, resourceId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version FROM resource_slots WHERE resource_id = ?",
                Long.class, resourceId)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"0,0", "-1,0", "3,-1", "3,4"})
    @Transactional
    void invalidCapacityIsRejectedByTheDatabase(int capacity, int reserved) {
        long resourceId = createResource();

        assertCheckViolation(() -> createSlot(resourceId, capacity, reserved,
                "2026-11-01 09:00:00", "2026-11-01 10:00:00"));
    }

    @ParameterizedTest
    @CsvSource({"2026-11-01 09:00:00", "2026-11-01 08:00:00"})
    @Transactional
    void emptyOrReversedWindowIsRejectedByTheDatabase(String end) {
        long resourceId = createResource();

        assertCheckViolation(() -> createSlot(resourceId, 3, 0, "2026-11-01 09:00:00", end));
    }

    @Test
    @Transactional
    void slotCannotReferToAMissingResource() {
        assertThatThrownBy(() -> createSlot(Long.MAX_VALUE, 3, 0,
                "2026-11-01 09:00:00", "2026-11-01 10:00:00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void resourceWithSlotsCannotBeDeleted() {
        long resourceId = createResource();
        createSlot(resourceId, 3, 0, "2026-11-01 09:00:00", "2026-11-01 10:00:00");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM resources WHERE id = ?", resourceId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void blankResourceNameIsRejected() {
        assertCheckViolation(() -> jdbc.update("INSERT INTO resources (name) VALUES (?)", "   "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "open"})
    @Transactional
    void unknownOrLowercaseStatusIsRejected(String status) {
        assertCheckViolation(() -> jdbc.update("INSERT INTO resources (name, status) VALUES (?, ?)",
                "invalid-status", status));
        long resourceId = createResource();
        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO resource_slots (resource_id, starts_at, ends_at, capacity, status) "
                        + "VALUES (?, ?, ?, ?, ?)", resourceId,
                "2026-11-01 09:00:00", "2026-11-01 10:00:00", 3, status));
    }

    @Test
    void readinessChecksTheRealDatabase() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/actuator/health/readiness"))
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components", "details");
        }
    }

    private long createResource() {
        String name = "integration-" + UUID.randomUUID();
        jdbc.update("INSERT INTO resources (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM resources WHERE name = ?", Long.class, name);
    }

    private void assertCheckViolation(ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(DataAccessException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getErrorCode()).isEqualTo(3819));
    }

    private void createSlot(long resourceId, int capacity, int reserved, String start, String end) {
        jdbc.update("INSERT INTO resource_slots (resource_id, starts_at, ends_at, capacity, reserved) "
                + "VALUES (?, ?, ?, ?, ?)", resourceId, start, end, capacity, reserved);
    }
}
