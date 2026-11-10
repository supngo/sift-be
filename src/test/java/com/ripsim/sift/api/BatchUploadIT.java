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
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Upload -> Postgres. Needs a running Postgres with the dev defaults from application.yml
 * (run `docker compose up -d postgres` first). Rows created by these tests stay in that database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchUploadIT {

    static final byte[] A = hex("DE AD BE EF 01 00 00 12 9C 41 F3 07 7B 22 6F 72 64 65 72 49 64 22 3A 22 41 31 42 32 2D 39 46 22 7D 00 00 FF FF C8 33");
    static final byte[] B = hex("CA FE BA BE 02 00 00 3B 11 E0 7B 22 73 6B 75 22 3A 22 58 2D 31 30 30 22 2C 22 71 74 79 22 3A 35 2C 22 77 61 72 65 68 6F 75 73 65 22 3A 7B 22 7A 6F 6E 65 22 3A 22 43 34 22 7D 7D 0D 0A 00 00 7A 7A");
    static final byte[] C = hex("17 03 03 00 7B 42 42 00 E1 7B 22 6E 6F 74 65 22 3A 22 75 73 65 20 7B 20 77 69 74 68 20 63 61 72 65 22 2C 22 73 74 61 74 75 73 22 3A 22 6F 6B 22 7D 00 04 D2 00");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    record Stored(int idx, int sizeBytes, byte[] content) {
    }

    @Test
    void singlePayload_isSavedWithItsExactBytes() {
        HttpResponse<String> r = post("application/octet-stream", A);
        assertThat(r.statusCode()).isEqualTo(202);
        String batchId = JsonPath.read(r.body(), "$.batchId");
        assertThat(batchId).startsWith("b_");
        assertThat((Integer) JsonPath.read(r.body(), "$.payloadCount")).isEqualTo(1);

        List<Stored> rows = rowsOf(batchId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).idx()).isZero();
        assertThat(rows.get(0).sizeBytes()).isEqualTo(A.length);
        assertThat(rows.get(0).content()).isEqualTo(A);
    }

    @Test
    void framedBatch_savesEveryPayloadInOrder() throws IOException {
        HttpResponse<String> r = post("application/x-sift-batch", frame(A, B, C));
        assertThat(r.statusCode()).isEqualTo(202);
        String batchId = JsonPath.read(r.body(), "$.batchId");

        List<Stored> rows = rowsOf(batchId);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).content()).isEqualTo(A);
        assertThat(rows.get(1).content()).isEqualTo(B);
        assertThat(rows.get(2).content()).isEqualTo(C);
        assertThat(rows.get(2).idx()).isEqualTo(2);
    }

    @Test
    void brokenEnvelope_is400_andNothingIsLeftBehind() {
        long batchesBefore = count("batch");
        long payloadsBefore = count("payload");

        // declares 2 payloads: the first is complete (3 bytes), the second claims 9 bytes but only 1 follows
        byte[] body = {1, 0, 0, 0, 2, 0, 0, 0, 3, 1, 2, 3, 0, 0, 0, 9, 7};
        HttpResponse<String> r = post("application/x-sift-batch", body);

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(r.body(), "$.code")).isEqualTo("TRUNCATED_FRAME");
        // the first payload WAS inserted before the error; the transaction rollback must have removed it
        assertThat(count("batch")).isEqualTo(batchesBefore);
        assertThat(count("payload")).isEqualTo(payloadsBefore);
    }

    @Test
    void emptyBody_is400() {
        assertThat(post("application/octet-stream", new byte[0]).statusCode()).isEqualTo(400);
    }

    @Test
    void unsupportedContentType_is415() {
        assertThat(post("text/plain", "hello".getBytes()).statusCode()).isEqualTo(415);
    }

    // ---- helpers ----

    private List<Stored> rowsOf(String batchId) {
        return jdbc.sql("SELECT idx, size_bytes, content FROM payload WHERE batch_id = :b ORDER BY idx")
                .param("b", batchId)
                .query((rs, n) -> new Stored(rs.getInt("idx"), rs.getInt("size_bytes"), rs.getBytes("content")))
                .list();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private HttpResponse<String> post(String contentType, byte[] body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/batches"))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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

    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s.replace(" ", ""));
    }
}
