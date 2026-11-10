package com.ripsim.sift.persistence;

import com.ripsim.sift.models.ExtractionResult;
import com.ripsim.sift.models.JsonDocument;
import com.ripsim.sift.models.JsonKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The payload table used as a work queue (see V2__extraction.sql for the state machine). */
@Repository
public class ExtractionQueueRepository {

    public record ClaimedPayload(long id, String batchId, int idx, int attempt, byte[] content) {
    }

    private static final int MAX_ERROR_MESSAGE = 2000;

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;

    public ExtractionQueueRepository(JdbcClient jdbc, JdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    /**
     * Atomically takes the oldest available payload: pending and due, or processing with an expired lease (its
     * worker died). SKIP LOCKED lets several workers or app instances claim in parallel without blocking.
     */
    public Optional<ClaimedPayload> claimNext(Duration lease) {
        return jdbc.sql("""
                        UPDATE payload
                           SET status = 'processing',
                               attempts = attempts + 1,
                               locked_until = now() + make_interval(secs => :lease)
                         WHERE id = (SELECT id FROM payload
                                      WHERE (status = 'pending' AND available_at <= now())
                                         OR (status = 'processing' AND locked_until < now())
                                      ORDER BY id
                                      LIMIT 1
                                      FOR UPDATE SKIP LOCKED)
                        RETURNING id, batch_id, idx, attempts, content
                        """)
                .param("lease", (double) lease.toSeconds())
                .query((rs, n) -> new ClaimedPayload(rs.getLong("id"), rs.getString("batch_id"), rs.getInt("idx"),
                        rs.getInt("attempts"), rs.getBytes("content")))
                .optional();
    }

    /**
     * Stores the result and marks the payload done, in one transaction.
     *
     * @return false if this worker no longer owns the payload (its lease expired and someone else claimed it)
     */
    @Transactional
    public boolean complete(long payloadId, int attempt, ExtractionResult result) {
        int owned = jdbc.sql("""
                        UPDATE payload
                           SET status = 'done', locked_until = NULL, processed_at = now(),
                               warnings = :warnings, error_code = NULL, error_message = NULL
                         WHERE id = :id AND status = 'processing' AND attempts = :attempt
                        """)
                .param("id", payloadId)
                .param("attempt", attempt)
                .param("warnings", result.warnings().isEmpty() ? null : String.join(",", result.warnings()))
                .update();
        if (owned == 0) {
            return false;
        }
        jdbc.sql("DELETE FROM json_document WHERE payload_id = :id").param("id", payloadId).update();

        List<Object[]> docs = new ArrayList<>();
        List<Object[]> keys = new ArrayList<>();
        for (int d = 0; d < result.documents().size(); d++) {
            JsonDocument doc = result.documents().get(d);
            docs.add(new Object[]{payloadId, d, doc.start(), doc.end(), doc.partial()});
            for (int k = 0; k < doc.keys().size(); k++) {
                JsonKey key = doc.keys().get(k);
                keys.add(new Object[]{payloadId, d, k, key.path(), key.name(), key.depth()});
            }
        }
        batch.batchUpdate("INSERT INTO json_document (payload_id, doc_idx, start_offset, end_offset, partial) "
                + "VALUES (?, ?, ?, ?, ?)", docs);
        batch.batchUpdate("INSERT INTO json_key (payload_id, doc_idx, ord, path, name, depth) "
                + "VALUES (?, ?, ?, ?, ?, ?)", keys);
        return true;
    }

    /**
     * Records a failed attempt: back to pending (retried after a backoff) or, when out of attempts, failed.
     *
     * @return the payload's new status ("pending" or "failed"), or empty if this worker no longer owned it
     */
    public Optional<String> fail(long payloadId, int attempt, int maxAttempts, Duration backoff, String code,
                                 String message) {
        return jdbc.sql("""
                        UPDATE payload
                           SET status = CASE WHEN attempts >= :max THEN 'failed' ELSE 'pending' END,
                               processed_at = CASE WHEN attempts >= :max THEN now() END,
                               available_at = now() + make_interval(secs => :backoff * attempts),
                               locked_until = NULL,
                               error_code = :code,
                               error_message = :message
                         WHERE id = :id AND status = 'processing' AND attempts = :attempt
                        RETURNING status
                        """)
                .param("id", payloadId)
                .param("attempt", attempt)
                .param("max", maxAttempts)
                .param("backoff", (double) backoff.toSeconds())
                .param("code", code)
                .param("message", truncate(message))
                .query(String.class)
                .optional();
    }


    /** Fails the payload for good, e.g. after it exhausted its attempts without ever reporting back. */
    public void giveUp(long payloadId, int attempt, String code, String message) {
        jdbc.sql("""
                        UPDATE payload
                           SET status = 'failed', processed_at = now(), locked_until = NULL,
                               error_code = :code, error_message = :message
                         WHERE id = :id AND status = 'processing' AND attempts = :attempt
                        """)
                .param("id", payloadId)
                .param("attempt", attempt)
                .param("code", code)
                .param("message", truncate(message))
                .update();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_ERROR_MESSAGE ? s : s.substring(0, MAX_ERROR_MESSAGE);
    }
}
