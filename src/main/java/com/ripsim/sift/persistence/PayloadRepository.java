package com.ripsim.sift.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PayloadRepository {

    private final JdbcClient jdbc;

    public PayloadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String batchId, int index, byte[] content) {
        jdbc.sql("INSERT INTO payload (batch_id, idx, size_bytes, content) VALUES (:batch, :idx, :size, :content)")
                .param("batch", batchId)
                .param("idx", index)
                .param("size", content.length)
                .param("content", content)
                .update();
    }
}
