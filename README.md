# Project Sift: ETL Byte Payload and Key Retrieval

Upload binary payloads, let a background worker find the JSON inside each one and record its keys, then read the
results.

```
client ──POST /v1/batches──▶ UploadService ──▶ batch + payload (status = pending)
                                                        │
                       ExtractionWorker ◀── claim (SKIP LOCKED) ──┘
                              │ KeyExtractor
                              ▼
                       json_document + json_key, payload.status = done | failed
                                                        │
client ──GET /v1/batches/{id}──▶ BatchQueryService ◀────┘
```

The React dashboard is a separate project (`C:\Users\supng\Work\sift-ui`). Start this backend first, then run `npm run dev` there.

## Run it

```bash
docker compose up --build
```

If you ran the earlier full project, reset its old database first (the migration changed): `docker compose down -v`.

### Try it

Build upload bodies from the samples with the single-file Java program in `scripts/` (no compile step). Inputs are in `scripts/samples/`; output goes to `scripts/payload/`:

```bash
java scripts/MakeFrame.java batch.bin scripts/samples/payload_a.hex scripts/samples/payload_b.hex scripts/samples/payload_c.hex
java scripts/MakeFrame.java --raw a.bin scripts/samples/payload_a.hex
```

Upload them (on Windows PowerShell use `curl.exe`, because plain `curl` is an alias for something else):

```bash
# three payloads in one batch
curl -i -X POST http://localhost:8080/v1/batches -H "Content-Type: application/x-sift-batch" --data-binary "@scripts/payload/batch.bin"

# one payload
curl -i -X POST http://localhost:8080/v1/batches -H "Content-Type: application/octet-stream" --data-binary "@scripts/payload/a.bin"
```

Response: `202 Accepted` with `{ "batchId": "b_1a2b3c4d5e6f", "payloadCount": 3 }`.

`GET /v1/batches` lists recent batches (newest first, `?limit=1..200`, default 50) with per-status payload counts.

Then poll for the results (`status` is `busy` until every payload is `done` or `failed`):

```bash
curl http://localhost:8080/v1/batches/b_1a2b3c4d5e6f
```

```json
{
  "batchId": "b_1a2b3c4d5e6f",
  "status": "completed",
  "payloadCount": 3,
  "extracts": [
    { "payloadIndex": 0, "status": "done", "keys": ["orderId"], "documents": [...], "warnings": [], "error": null },
    { "payloadIndex": 1, "status": "done", "keys": ["sku", "qty", "warehouse", "zone"],
      "documents": [{ "start": 10, "end": 59, "partial": false, "keys": [
        { "path": "/sku", "name": "sku", "depth": 1 }, { "path": "/qty", "name": "qty", "depth": 1 },
        { "path": "/warehouse", "name": "warehouse", "depth": 1 },
        { "path": "/warehouse/zone", "name": "zone", "depth": 2 } ] }],
      "warnings": [], "error": null },
    { "payloadIndex": 2, "status": "done", "keys": ["note", "status"], "documents": [...], "warnings": [], "error": null }
  ]
}
```

Look at what was saved:

```bash
docker compose exec postgres psql -U sift -d sift -c "SELECT batch_id, idx, size_bytes, left(encode(content,'hex'),24) AS first_bytes FROM payload ORDER BY id;"
```

## API docs and health checks

| URL | What |
|---|---|
| http://localhost:8080/swagger-ui.html | Swagger UI: browse the API and try uploads (pick a `.bin` file as the body) |
| http://localhost:8080/v3/api-docs | The OpenAPI 3 spec as JSON, for generating clients |
| http://localhost:8080/actuator/health | `UP`/`DOWN`, including the database and disk space |
| http://localhost:8080/actuator/health/liveness, `/readiness` | Probes for Docker / Kubernetes |
| http://localhost:8080/actuator/info | Version, build time, Java runtime |

No other actuator endpoints are exposed.

## Knowing when extraction fails, and why

**Per payload:** `GET /v1/batches/{batchId}` shows each payload's `status` (`done` / `failed`), `error.code` and
`error.message` when it failed, and `warnings` when the extractor hit a limit (keys may be incomplete). A payload with
`keys: []` finished but found no JSON.

**Logs:** every worker line names the batch and payload. Under Docker Compose the logs are JSON (Elastic Common
Schema) with `batchId`, `payloadIdx` and `attempt` as fields, ready for Loki / ELK; set `LOG_FORMAT=logstash` for
that format instead. Running on the host they're plain text; add `--logging.structured.format.console=ecs` for JSON. Health details are public because there is no authentication yet.

**CORS (calling from the React dashboard):** browsers on `http://localhost:5173` (Vite) and `http://localhost:3000`
(Create React App) may call `/v1/**` and `/actuator/health/**`. For other environments set the allowed origins:

```bash
SIFT_CORS_ALLOWED_ORIGINS=https://sift-dashboard.example.com,http://localhost:5173
```

From React, send the file bytes as the body with the right `Content-Type`:

```js
const res = await fetch("http://localhost:8080/v1/batches", {
  method: "POST",
  headers: { "Content-Type": "application/octet-stream" },
  body: file,                       // a File from <input type="file">
});
const { batchId } = await res.json();
```

Tests: `mvn test` (unit tests, nothing external) · `docker compose up -d postgres` then `mvn verify` (adds
`BatchUploadIT`, `ExtractionIT`, `OpsEndpointsIT` and `CorsIT`).

## What counts as a "key"

**A key is any field name inside a JSON object, at any depth.**

Sample B contains `{"sku":"X-100","qty":5,"warehouse":{"zone":"C4"}}`, so its keys are `sku`, `qty`, `warehouse` and
`zone`. This matches the v0 API sketch in the brief.

* **Nested fields count** (`zone` inside `warehouse`). They're part of the data's shape, which is what downstream
  teams catalog and watch for drift.
* **Fields inside arrays count too.** `{"items":[{"sku":"a"},{"sku":"b"}]}` gives `items` and `sku`, each listed once.
* **Each key also has a path**, like `/warehouse/zone` (array items show as `*`, e.g. `/items/*/sku`). That tells a
  nested `zone` apart from a top-level one, and lets you filter to top-level keys only.

**How the JSON is found in the binary:** every `{` is a possible start. If valid JSON follows, it's used; if not,
it's skipped. That means:

* stray `{` bytes in the binary, and `{` inside text like `"use { with care"`, are ignored (sample C has both)
* if a payload holds several JSON objects, all of them are found
* broken JSON is skipped, and its keys aren't reported
* JSON cut off by the end of the payload keeps the keys seen so far, marked `partial`

Limits protect against hostile input (nesting deeper than 64, keys over 256 characters, more than 10,000 keys, very
slow scans). Hitting one adds a warning to the payload instead of failing it.

## How the worker works

* The `payload` table is the queue (`V2__extraction.sql`). A worker claims the oldest pending row with
  `FOR UPDATE SKIP LOCKED`, so several app instances can run workers without stepping on each other.
* A claim is a **lease** (`sift.worker.lease`, default 5 min). If a worker dies, the row is re-claimed after the lease.
* Errors are retried with backoff up to `sift.worker.max-attempts` (3), then the payload is `failed` with an
  `error.code` / `error.message` visible in the API and in the logs. A payload that keeps crashing the worker
  is failed with `ATTEMPTS_EXHAUSTED` instead of looping forever.
* Set `sift.worker.enabled=false` to run an upload-only instance.

## The files

| File | What it does |
|---|---|
| `api/BatchController` | Two `POST /v1/batches` methods, chosen by `Content-Type`, plus `GET /v1/batches` and `GET /v1/batches/{id}`. |
| `api/UploadService` | The logic: starts a batch, saves each payload, enforces size limits. **One transaction**, so a failed upload leaves nothing behind. |
| `models/ApiModels` | The JSON returned to the client (`AcceptedBatch`, `BatchResult`, ...). |
| `exception/ApiExceptionHandler` | Turns upload errors and `ApiException` (e.g. 404 `BATCH_NOT_FOUND`) into JSON with a stable `code`. |
| `ingest/BatchFrameReader` | Reads the batch envelope (version byte, payload count, then length-prefixed payloads) that carries many payloads in one body. Create it, then call `next()` while `hasNext()`; each call returns one payload as `byte[]`. |
| `api/BatchQueryService` | Builds the `GET /v1/batches/{id}` response; batch status is derived from payload statuses. |
| `extract/KeyExtractor` | Finds JSON in binary and lists its keys. Pure Java, no database: the core of the pipeline. |
| `models/ExtractionResult`, `JsonDocument`, `JsonKey` | What the extractor returns: the documents found in a payload, their keys and any warnings. |
| `worker/ExtractionWorker` | Scheduled loop: claim a payload, extract, store result or record failure. |
| `persistence/ExtractionQueueRepository` | Claim / complete / fail SQL for the queue. |
| `persistence/BatchQueryRepository` | Read queries for the GET endpoint. |
| `exception/BadUploadException` | The error thrown for a bad upload (carries the HTTP status and a stable `code`). |
| `persistence/BatchRepository`, `PayloadRepository` | The upload `INSERT` statements. |
| `config/CorsConfig`, `config/CorsProperties` | Which browser origins (the React dashboard) may call the API. |
| `config/OpenApiConfig` | Title and description of the OpenAPI spec, plus marking record fields required so generated clients get exact types. Endpoint docs are annotations on `BatchController`. |
| `config/SiftProperties`, `config/WorkerProperties` | The `sift:` and `sift.worker:` settings from `application.yml`. |
| `db/migration/V1__init.sql` | Creates the `batch` and `payload` tables. |
| `db/migration/V2__extraction.sql` | Queue columns on `payload`, plus `json_document` and `json_key`. |
| `db/migration/V3__batch_created_at_index.sql` | Index for `GET /v1/batches` (newest first). |

## Decisions made to keep this small (easy to change)

* **Raw bytes are stored in Postgres (`BYTEA`)**, not in files on disk. No spool folder, no volume, nothing to clean up on
  failure; the transaction does it. Trade-off: each payload is held in memory while it is saved (limit: 64 MB).
* **Two upload formats**: raw single payload, and the length-prefixed envelope. Multipart (for the dashboard) is left out.
* **Empty payloads are rejected** (400) instead of being stored and failed later.
* **202 Accepted**, then poll `GET`: extraction happens in the background.
* **Postgres is the queue.** No broker to run; the brief has no real-time requirement. A broker can replace the claim
  query later without changing the API.
* **Batch status is computed** from payload statuses on read, so it can never disagree with them.
* **Plain SQL through `JdbcClient`, no JPA.** The queries are specific (`SKIP LOCKED` claims, bulk key inserts, one
  join for the GET), the schema is small, and `BYTEA` payloads are easier to control without an ORM.