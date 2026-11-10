-- GET /v1/batches lists the newest batches first.
CREATE INDEX batch_created_at_idx ON batch (created_at DESC, id DESC);
