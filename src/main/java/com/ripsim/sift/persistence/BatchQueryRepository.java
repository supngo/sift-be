package com.ripsim.sift.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only queries behind GET /v1/batches and GET /v1/batches/{id}. */
@Repository
public class BatchQueryRepository {

    public record PayloadRow(int idx, String status, String warnings, String errorCode, String errorMessage) {
    }

    public record KeyRow(int payloadIdx, int docIdx, int start, int end, boolean partial,
                         String path, String name, int depth) {
    }

    private final JdbcClient jdbc;

    public BatchQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row of GET /v1/batches. {@code inProgress} = pending + processing. */
    public record BatchSummaryRow(String batchId, OffsetDateTime createdAt, int payloadCount, int done, int failed,
                                  int inProgress) {
    }

    /** The most recent batches with per-status payload counts, newest first. */
    public List<BatchSummaryRow> recentBatches(int limit) {
        return jdbc.sql("""
                        SELECT b.id, b.created_at,
                               count(p.id)                                                    AS payload_count,
                               count(p.id) FILTER (WHERE p.status = 'done')                   AS done,
                               count(p.id) FILTER (WHERE p.status = 'failed')                 AS failed,
                               count(p.id) FILTER (WHERE p.status IN ('pending', 'processing')) AS in_progress
                          FROM (SELECT id, created_at FROM batch ORDER BY created_at DESC, id DESC LIMIT :limit) b
                          LEFT JOIN payload p ON p.batch_id = b.id
                         GROUP BY b.id, b.created_at
                         ORDER BY b.created_at DESC, b.id DESC
                        """)
                .param("limit", limit)
                .query((rs, n) -> new BatchSummaryRow(rs.getString("id"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getInt("payload_count"),
                        rs.getInt("done"), rs.getInt("failed"), rs.getInt("in_progress")))
                .list();
    }

    public boolean exists(String batchId) {
        return jdbc.sql("SELECT count(*) FROM batch WHERE id = :id").param("id", batchId).query(Long.class).single() > 0;
    }

    public List<PayloadRow> payloads(String batchId) {
        return jdbc.sql("""
                        SELECT idx, status, warnings, error_code, error_message
                          FROM payload WHERE batch_id = :b ORDER BY idx
                        """)
                .param("b", batchId)
                .query((rs, n) -> new PayloadRow(rs.getInt("idx"), rs.getString("status"), rs.getString("warnings"),
                        rs.getString("error_code"), rs.getString("error_message")))
                .list();
    }

    /** Every key of every document of every payload in the batch, in payload / document / first-seen order. */
    public List<KeyRow> keys(String batchId) {
        return jdbc.sql("""
                        SELECT p.idx, d.doc_idx, d.start_offset, d.end_offset, d.partial, k.path, k.name, k.depth
                          FROM payload p
                          JOIN json_document d ON d.payload_id = p.id
                          JOIN json_key k ON k.payload_id = d.payload_id AND k.doc_idx = d.doc_idx
                         WHERE p.batch_id = :b AND p.status = 'done'
                         ORDER BY p.idx, d.doc_idx, k.ord
                        """)
                .param("b", batchId)
                .query((rs, n) -> new KeyRow(rs.getInt("idx"), rs.getInt("doc_idx"), rs.getInt("start_offset"),
                        rs.getInt("end_offset"), rs.getBoolean("partial"), rs.getString("path"), rs.getString("name"),
                        rs.getInt("depth")))
                .list();
    }
}
