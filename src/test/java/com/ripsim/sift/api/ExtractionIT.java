package com.ripsim.sift.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Upload -> worker -> GET, end to end against the dev Postgres (run `docker compose up -d postgres` first).
 * The worker runs inside the test app, so these tests poll until the batch is completed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sift.worker.poll-interval=100ms")
class ExtractionIT {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @SuppressWarnings("unchecked")
    @Test
    void samplePayloads_areExtracted() throws Exception {
        String batchId = upload("application/x-sift-batch", frame(BatchUploadIT.A, BatchUploadIT.B, BatchUploadIT.C));
        String body = awaitCompleted(batchId);

        assertThat((Integer) JsonPath.read(body, "$.payloadCount")).isEqualTo(3);
        assertThat((List<String>) JsonPath.read(body, "$.extracts[0].keys")).containsExactly("orderId");
        assertThat((List<String>) JsonPath.read(body, "$.extracts[1].keys")).containsExactly("sku", "qty", "warehouse", "zone");
        assertThat((List<String>) JsonPath.read(body, "$.extracts[2].keys")).containsExactly("note", "status");
        assertThat((List<String>) JsonPath.read(body, "$.extracts[1].documents[0].keys[*].path"))
                .containsExactly("/sku", "/qty", "/warehouse", "/warehouse/zone");
        assertThat((List<String>) JsonPath.read(body, "$.extracts[*].status")).containsOnly("done");
    }

    @SuppressWarnings("unchecked")
    @Test
    void payloadWithoutJson_completesWithNoKeys() throws Exception {
        String body = awaitCompleted(upload("application/octet-stream", new byte[]{0, 1, 2, '{', 3}));
        assertThat((String) JsonPath.read(body, "$.extracts[0].status")).isEqualTo("done");
        assertThat((List<String>) JsonPath.read(body, "$.extracts[0].keys")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void batchList_showsTheNewBatch_withCounts() throws Exception {
        String batchId = upload("application/x-sift-batch", frame(BatchUploadIT.A, BatchUploadIT.B));
        awaitCompleted(batchId);

        HttpResponse<String> r = list("?limit=200");
        assertThat(r.statusCode()).isEqualTo(200);
        String item = "$.items[?(@.batchId == '" + batchId + "')]";
        assertThat((List<String>) JsonPath.read(r.body(), item + ".status")).containsExactly("completed");
        assertThat((List<Integer>) JsonPath.read(r.body(), item + ".payloadCount")).containsExactly(2);
        assertThat((List<Integer>) JsonPath.read(r.body(), item + ".done")).containsExactly(2);
        assertThat((List<Integer>) JsonPath.read(r.body(), item + ".inProgress")).containsExactly(0);
        // newest first: a batch created just now is near the top
        assertThat((List<String>) JsonPath.read(list("?limit=5").body(), "$.items[*].batchId")).contains(batchId);
    }

    @Test
    void batchList_rejectsBadLimit() throws Exception {
        for (String q : List.of("?limit=0", "?limit=201")) {
            HttpResponse<String> r = list(q);
            assertThat(r.statusCode()).as(q).isEqualTo(400);
            assertThat((String) JsonPath.read(r.body(), "$.code")).isEqualTo("INVALID_PARAMETER");
        }
    }

    @Test
    void unknownBatch_is404WithCode() throws Exception {
        HttpResponse<String> r = get("b_doesnotexist");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat((String) JsonPath.read(r.body(), "$.code")).isEqualTo("BATCH_NOT_FOUND");
    }

    // ---- helpers ----

    private HttpResponse<String> list(String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/batches" + query)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String awaitCompleted(String batchId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            HttpResponse<String> r = get(batchId);
            assertThat(r.statusCode()).isEqualTo(200);
            if ("completed".equals(JsonPath.read(r.body(), "$.status"))) {
                return r.body();
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("batch " + batchId + " still busy: " + r.body());
            }
            Thread.sleep(100);
        }
    }

    private String upload(String contentType, byte[] body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/batches"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(202);
        return JsonPath.read(r.body(), "$.batchId");
    }

    private HttpResponse<String> get(String batchId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/batches/" + batchId)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] frame(byte[]... payloads) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(1);
        out.writeInt(payloads.length);
        for (byte[] p : payloads) {
            out.writeInt(p.length);
            out.write(p);
        }
        return bos.toByteArray();
    }
}
