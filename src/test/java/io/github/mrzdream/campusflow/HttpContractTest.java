package io.github.mrzdream.campusflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpContractTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    })
    void healthEndpointsReturnStatusWithoutInternalDetails(String path) throws Exception {
        HttpResponse<String> response = request("GET", path);
        JsonNode body = objectMapper.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.has("components")).isFalse();
        assertThat(body.has("details")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/configprops", "/actuator/metrics"})
    void operationalDetailsAreNotExposed(String path) throws Exception {
        assertThat(request("GET", path).statusCode()).isEqualTo(404);
    }

    @Test
    void unknownApiRouteReturnsProblemDetails() throws Exception {
        HttpResponse<String> response = request("GET", "/api/v1/missing");
        JsonNode body = objectMapper.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("content-type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(body.path("status").asInt()).isEqualTo(404);
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/missing");
        assertThat(body.has("trace")).isFalse();
        assertThat(body.has("exception")).isFalse();
    }

    @Test
    void unsupportedHealthMethodReturnsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = request("POST", "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("allow").orElseThrow()).contains("GET");
        assertThat(response.headers().firstValue("content-type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(objectMapper.readTree(response.body()).path("status").asInt()).isEqualTo(405);
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json, application/problem+json")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
