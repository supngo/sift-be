-- One row per upload request.
CREATE TABLE batch (
    id         VARCHAR(32) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per payload inside a batch. The raw bytes live right here in Postgres (BYTEA).
CREATE TABLE payload (
    id         BIGSERIAL   PRIMARY KEY,
    batch_id   VARCHAR(32) NOT NULL REFERENCES batch (id) ON DELETE CASCADE,
    idx        INT         NOT NULL,          -- position inside the batch, starting at 0
    size_bytes INT         NOT NULL,          -- handy for inspecting rows without dumping the bytes
    content    BYTEA       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (batch_id, idx)
);
