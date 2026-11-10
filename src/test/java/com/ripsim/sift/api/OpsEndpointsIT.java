package com.ripsim.sift.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** Actuator health/info and the OpenAPI spec. Needs the dev Postgres (the health check includes the database). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsEndpointsIT {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void health_isUp_includingTheDatabase() throws Exception {
        HttpResponse<String> r = get("/actuator/health");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(r.body(), "$.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(r.body(), "$.components.db.status")).isEqualTo("UP");
    }

    @Test
    void livenessAndReadinessProbes_areUp() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void otherActuatorEndpoints_areNotExposed() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get("/actuator/beans").statusCode()).isEqualTo(404);
    }

    @SuppressWarnings("unchecked")
    @Test
    void openApiSpec_describesBothUploadFormatsAndTheGet() throws Exception {
        HttpResponse<String> r = get("/v3/api-docs");
        assertThat(r.statusCode()).isEqualTo(200);
        Map<String, Object> content = JsonPath.read(r.body(), "$.paths['/v1/batches'].post.requestBody.content");
        assertThat(content).containsKeys("application/octet-stream", "application/x-sift-batch");
        List<String> postCodes = List.copyOf(
                ((Map<String, Object>) JsonPath.read(r.body(), "$.paths['/v1/batches'].post.responses")).keySet());
        assertThat(postCodes).contains("202", "400", "413", "415");
        assertThat((Object) JsonPath.read(r.body(), "$.paths['/v1/batches/{batchId}'].get")).isNotNull();
    }

    @Test
    void swaggerUi_isServed() throws Exception {
        HttpResponse<String> r = get("/swagger-ui/index.html");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("swagger-ui");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
