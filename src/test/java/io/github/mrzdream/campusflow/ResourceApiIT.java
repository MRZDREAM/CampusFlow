package io.github.mrzdream.campusflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "DB_NAME=campus_flow_it")
class ResourceApiIT {

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    private final List<String> fixtureNames = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void removeOnlyThisTestsResources() {
        try {
            for (String name : fixtureNames) {
                jdbc.update("DELETE FROM resources WHERE name = ?", name);
            }
        } finally {
            client.close();
        }
    }

    @Test
    void creationCommitsToMySqlAndCanBeReadByAnotherRequest() throws Exception {
        String name = fixtureName();
        ObjectNode input = mapper.createObjectNode().put("name", "  " + name + "  ").put("description", "共享工作站");
        HttpResponse<String> created = request("POST", "/api/v1/resources", input.toString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = mapper.readTree(created.body());
        long id = body.path("id").asLong();
        assertThat(id).isPositive();
        assertThat(body.path("name").asText()).isEqualTo(name);
        assertThat(body.path("status").asText()).isEqualTo("OPEN");
        String location = created.headers().firstValue("location").orElseThrow();
        assertThat(location).isEqualTo("/api/v1/resources/" + id);

        HttpResponse<String> detail = request("GET", location, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(detail.body())).isEqualTo(body);
        assertThat(jdbc.queryForObject("SELECT name FROM resources WHERE id = ?", String.class, id))
                .isEqualTo(name);
        assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL AND updated_at IS NOT NULL "
                + "FROM resources WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void omittedDescriptionAndClientSuppliedIdentityUseServerDefaults() throws Exception {
        ObjectNode input = mapper.createObjectNode().put("name", fixtureName())
                .put("id", -1).put("status", "CLOSED");
        HttpResponse<String> response = request("POST", "/api/v1/resources", input.toString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("id").asLong()).isPositive();
        assertThat(body.path("description").asText()).isEmpty();
        assertThat(body.path("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void paginationUsesStableIdOrderingAndReportsTotals() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM resources", Long.class);
        long firstId = createResource();
        long secondId = createResource();

        HttpResponse<String> response = request("GET", "/api/v1/resources?page=" + before + "&size=1", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode firstPage = mapper.readTree(response.body());
        assertThat(firstPage.path("content").size()).isEqualTo(1);
        assertThat(firstPage.path("content").get(0).path("id").asLong()).isEqualTo(firstId);
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(before + 2);
        assertThat(firstPage.path("totalPages").asLong()).isEqualTo(before + 2);

        JsonNode secondPage = mapper.readTree(request("GET",
                "/api/v1/resources?page=" + (before + 1) + "&size=1", null).body());
        assertThat(secondPage.path("content").get(0).path("id").asLong()).isEqualTo(secondId);
    }

    @Test
    void varcharStatusMapsBackToTheJavaEnum() throws Exception {
        long id = createResource();
        jdbc.update("UPDATE resources SET status = 'CLOSED' WHERE id = ?", id);

        HttpResponse<String> response = request("GET", "/api/v1/resources/" + id, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    void missingResourceReturns404WithoutImplementationDetails() throws Exception {
        HttpResponse<String> response = request("GET", "/api/v1/resources/" + Long.MAX_VALUE, null);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("content-type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body.has("trace")).isFalse();
        assertThat(body.has("exception")).isFalse();
    }

    @Test
    void invalidCreationDoesNotWriteAnyResource() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM resources", Long.class);
        HttpResponse<String> response = request("POST", "/api/v1/resources", "{\"name\":\"   \"}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resources", Long.class)).isEqualTo(before);
    }

    private String fixtureName() {
        String name = "resource-it-" + UUID.randomUUID();
        fixtureNames.add(name);
        return name;
    }

    private long createResource() throws Exception {
        String input = mapper.createObjectNode().put("name", fixtureName()).toString();
        HttpResponse<String> response = request("POST", "/api/v1/resources", input);
        assertThat(response.statusCode()).isEqualTo(201);
        return mapper.readTree(response.body()).path("id").asLong();
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, application/problem+json")
                .method(method, publisher).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
