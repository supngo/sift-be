package com.ripsim.sift.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** CORS for the React dashboard, as a browser would exercise it. Needs the dev Postgres (boots the full app). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsIT {

    static final String DASHBOARD = "http://localhost:5173";

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void preflightForUpload_fromDashboard_isAllowed() throws Exception {
        HttpResponse<String> r = preflight(DASHBOARD, "POST", "content-type");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(header(r, "Access-Control-Allow-Origin")).isEqualTo(DASHBOARD);
        assertThat(header(r, "Access-Control-Allow-Methods")).contains("POST");
        assertThat(header(r, "Access-Control-Allow-Headers")).containsIgnoringCase("content-type");
        assertThat(header(r, "Access-Control-Max-Age")).isEqualTo("3600");
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    @Test
    void preflight_fromUnknownOrigin_isRejected() throws Exception {
        HttpResponse<String> r = preflight("https://evil.example", "POST", "content-type");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void actualUpload_fromDashboard_carriesTheHeader() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/v1/batches"))
                .header("Origin", DASHBOARD)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray("{\"a\":1}".getBytes())));
        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(header(r, "Access-Control-Allow-Origin")).isEqualTo(DASHBOARD);
    }

    @Test
    void errorResponses_carryTheHeaderToo_soTheBrowserCanReadThem() throws Exception {
        HttpResponse<String> unsupported = send(HttpRequest.newBuilder(uri("/v1/batches"))
                .header("Origin", DASHBOARD)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello")));
        assertThat(unsupported.statusCode()).isEqualTo(415);
        assertThat(header(unsupported, "Access-Control-Allow-Origin")).isEqualTo(DASHBOARD);

        HttpResponse<String> notFound = send(HttpRequest.newBuilder(uri("/v1/batches/b_nope")).header("Origin", DASHBOARD));
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(header(notFound, "Access-Control-Allow-Origin")).isEqualTo(DASHBOARD);
    }

    @Test
    void healthCheck_isReachableFromDashboard() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/actuator/health/readiness")).header("Origin", DASHBOARD));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(header(r, "Access-Control-Allow-Origin")).isEqualTo(DASHBOARD);
    }

    // ---- helpers ----

    private HttpResponse<String> preflight(String origin, String method, String headers) throws Exception {
        return send(HttpRequest.newBuilder(uri("/v1/batches"))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", headers)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
    }

    private HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }
}
