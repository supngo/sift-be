-- Step 2: key extraction. The payload table doubles as the work queue.
--
--   pending ──claim──▶ processing ──▶ done
--      ▲                   │
--      └──── retry ────────┤ (error, attempts left; waits until available_at)
--                          └──▶ failed (out of attempts)
--
-- A claim sets locked_until (a lease). If the worker dies, the lease expires and another worker re-claims the row.
-- `attempts` is incremented by every claim and doubles as a fencing token: a worker only writes its result if the
-- row still carries the attempt number it claimed.
ALTER TABLE payload
    ADD COLUMN status        VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'processing', 'done', 'failed')),
    ADD COLUMN attempts      INT         NOT NULL DEFAULT 0,
    ADD COLUMN available_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN locked_until  TIMESTAMPTZ,
    ADD COLUMN processed_at  TIMESTAMPTZ,
    ADD COLUMN warnings      TEXT,          -- comma-separated codes, e.g. KEY_TRUNCATED,TOO_MANY_KEYS
    ADD COLUMN error_code    VARCHAR(64),
    ADD COLUMN error_message TEXT;

-- keeps the claim query cheap however many finished payloads accumulate
CREATE INDEX payload_queue_idx ON payload (id) WHERE status IN ('pending', 'processing');

-- One row per JSON object found in a payload. Offsets let values be extracted later from payload.content.
CREATE TABLE json_document (
    payload_id   BIGINT  NOT NULL REFERENCES payload (id) ON DELETE CASCADE,
    doc_idx      INT     NOT NULL,          -- order within the payload, starting at 0
    start_offset INT     NOT NULL,          -- byte offset of '{'
    end_offset   INT     NOT NULL,          -- byte offset just past '}' (or end of payload when partial)
    partial      BOOLEAN NOT NULL,          -- payload ended before the object was closed
    PRIMARY KEY (payload_id, doc_idx)
);

-- One row per distinct key path within a document.
CREATE TABLE json_key (
    payload_id BIGINT NOT NULL,
    doc_idx    INT    NOT NULL,
    ord        INT    NOT NULL,             -- first-seen order within the document
    path       TEXT   NOT NULL,             -- e.g. /warehouse/zone ; array elements are *
    name       TEXT   NOT NULL,             -- e.g. zone
    depth      INT    NOT NULL,             -- 1 = member of the outermost object
    PRIMARY KEY (payload_id, doc_idx, ord),
    FOREIGN KEY (payload_id, doc_idx) REFERENCES json_document (payload_id, doc_idx) ON DELETE CASCADE
);
