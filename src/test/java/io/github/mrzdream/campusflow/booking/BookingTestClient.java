package io.github.mrzdream.campusflow.booking;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.mrzdream.campusflow.account.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

abstract class BookingTestClient {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TokenService tokens;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final List<Long> resources = new ArrayList<>();
    private final List<String> users = new ArrayList<>();

    Reply request(String method, String path, String token, Object body) throws Exception {
        return request(method, path, token, body, null);
    }

    Reply request(String method, String path, String token, Object body, UUID key) throws Exception {
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(12)).method(method, publisher);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key.toString());
        }
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isBlank() ? MissingNode.getInstance() : mapper.readTree(response.body());
        return new Reply(response.statusCode(), json);
    }

    long resource() throws Exception {
        var reply = request("POST", "/api/v1/resources", admin(), java.util.Map.of("name", "booking-it-" + UUID.randomUUID()));
        assertThat(reply.status()).isEqualTo(201);
        long id = reply.body().path("id").asLong();
        resources.add(id);
        return id;
    }

    long slot(long resource, int capacity) throws Exception {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        var result = createSlot(resource, start, capacity);
        assertThat(result.status()).isEqualTo(201);
        return result.body().path("id").asLong();
    }

    Reply createSlot(long resource, Instant start, int capacity) throws Exception {
        return request("POST", "/api/v1/resources/" + resource + "/slots", admin(),
                java.util.Map.of("startsAt", start.toString(), "endsAt", start.plusSeconds(3600).toString(), "capacity", capacity));
    }

    String username() {
        String name = "it-" + UUID.randomUUID().toString().replace("-", "");
        users.add(name);
        return name;
    }

    User fixtureUser() {
        String username = username();
        jdbc.update("INSERT INTO campus_users (username,password_hash) VALUES (?,?)", username, "unused-in-this-test");
        long id = jdbc.queryForObject("SELECT id FROM campus_users WHERE username=?", Long.class, username);
        return new User(id, tokens.issue(id, "USER").accessToken());
    }

    String admin() {
        return tokens.issue(1L, "ADMIN").accessToken();
    }

    Reply reserve(User user, long slot) throws Exception {
        return request("POST", "/api/v1/reservations", user.token(), java.util.Map.of("slotId", slot));
    }

    Reply join(User user, long slot) throws Exception {
        return request("POST", "/api/v1/waitlist", user.token(), java.util.Map.of("slotId", slot));
    }

    void occupancy(long slot, int expectedReserved, int expectedOrders, int expectedOffers) {
        assertThat(jdbc.queryForObject("SELECT reserved FROM resource_slots WHERE id=?", Integer.class, slot))
                .isEqualTo(expectedReserved);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_orders WHERE slot_id=? AND status='CONFIRMED'",
                Integer.class, slot)).isEqualTo(expectedOrders);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entries WHERE slot_id=? AND status='OFFERED'",
                Integer.class, slot)).isEqualTo(expectedOffers);
        assertThat(expectedReserved).isEqualTo(expectedOrders + expectedOffers);
    }

    @AfterEach
    void clearOnlyFixtures() {
        try {
            for (long id : resources) {
                jdbc.update("""
                        DELETE o FROM booking_outbox o JOIN booking_requests r ON r.id=o.request_id
                        JOIN resource_slots s ON s.id=r.slot_id WHERE s.resource_id=?
                        """, id);
                for (String table : List.of("booking_requests", "waitlist_entries", "reservation_orders")) {
                    jdbc.update("DELETE t FROM " + table + " t JOIN resource_slots s ON s.id=t.slot_id WHERE s.resource_id=?", id);
                }
                jdbc.update("DELETE FROM resource_slots WHERE resource_id=?", id);
                jdbc.update("DELETE FROM resources WHERE id=?", id);
            }
            for (String username : users) {
                jdbc.update("DELETE FROM campus_users WHERE username=?", username);
            }
        } finally {
            http.close();
        }
    }

    record User(long id, String token) { }
    record Reply(int status, JsonNode body) {
        long id() { return body.path("id").asLong(); }
        String code() { return body.path("code").asText(); }
        String state() { return body.path("status").asText(); }
    }
}
