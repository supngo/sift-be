package com.ripsim.sift.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BatchRepository {

    private final JdbcClient jdbc;

    public BatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String id) {
        jdbc.sql("INSERT INTO batch (id) VALUES (:id)")
                .param("id", id)
                .update();
    }
}
