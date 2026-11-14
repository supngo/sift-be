# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Project Sift, **step 2**: a Spring Boot 4 / Java 21 service that accepts binary uploads on `POST /v1/batches`, saves them to Postgres, finds JSON embedded in each payload with a background worker, stores the keys, and serves them on `GET /v1/batches` (recent batches) and `GET /v1/batches/{id}`. The React dashboard is a separate project at `C:\Users\supng\Work\sift-ui`, typed against this API's OpenAPI spec. The product brief is the "Epic Proposal: Project Sift" PDF (not in the repo). Actuator (`/actuator/health`, `/actuator/info`) and springdoc OpenAPI (`/v3/api-docs`, `/swagger-ui.html`) are in. CORS is open to the React dashboard's origins. Data access is plain SQL through `JdbcClient` by choice, so don't introduce JPA. There is no multipart upload. The README lists these as deliberately deferred, so don't add them unless asked.

## Commands

```bash
docker compose up --build              # Postgres + app on :8080 (app image runs unit tests during build)
docker compose down -v                 # reset the DB volume (needed if an old schema is present)
docker compose up -d postgres          # Postgres only, for running the app or ITs from the host

mvn test                               # unit tests (*Test), no external deps
mvn verify                             # + integration tests (*IT, via failsafe), needs Postgres on localhost:5432
mvn test -Dtest=BatchFrameReaderTest   # single unit test class (append #method for one method)
mvn verify -Dit.test=BatchUploadIT     # single IT (unit tests still run first)
mvn spring-boot:run                    # run the app on the host (needs Postgres)
```

There's no linter or formatter configured.

Build sample upload bodies and send them. On Windows PowerShell, use `curl.exe`, not `curl`:

```bash
java scripts/MakeFrame.java batch.bin scripts/samples/payload_a.hex scripts/samples/payload_b.hex scripts/samples/payload_c.hex
java scripts/MakeFrame.java --raw a.bin scripts/samples/payload_a.hex
java scripts/MakeFrame.java --repeat 1000 slow.bin scripts/samples/payload_a.hex scripts/samples/payload_b.hex scripts/samples/payload_c.hex   # 3,000 payloads, ~15-20 s to process: shows "pending" in the UI
curl -i -X POST http://localhost:8080/v1/batches -H "Content-Type: application/x-sift-batch" --data-binary "@scripts/payload/batch.bin"
curl -i -X POST http://localhost:8080/v1/batches -H "Content-Type: application/octet-stream" --data-binary "@scripts/payload/a.bin"
```

DB config comes from `DB_URL` / `DB_USER` / `DB_PASSWORD`, which default to `localhost:5432/sift`, `sift`/`sift`. Compose reads `POSTGRES_USER` and `POSTGRES_PASSWORD` from `.env` (see `.env.example`).

## Architecture

Upload: `BatchController` → `UploadService` → `BatchRepository` / `PayloadRepository` (plain SQL through `JdbcClient`) → tables `batch` and `payload` (new rows are `status = 'pending'`).
Extraction: `ExtractionWorker` (`@Scheduled`) → `ExtractionQueueRepository.claimNext` → `KeyExtractor.extract` → `ExtractionQueueRepository.complete` → tables `json_document` and `json_key`.
Read: `BatchController` → `BatchQueryService` → `BatchQueryRepository` (`recentBatches` for the list, `payloads` + `keys` for one batch).

- **Two upload formats on one URL, chosen by `Content-Type`.** `application/octet-stream` is a single raw payload. `application/x-sift-batch` is the batch envelope (no magic header): a 1-byte version, a big-endian unsigned u32 count, then `count` × (u32 length + bytes). `BatchFrameReader` documents the format and parses it incrementally, returning one payload per `next()`. `scripts/MakeFrame.java` (a single-file program run with `java`, no build step) builds the same format, so keep the two in sync if the envelope changes. Sample inputs are in `scripts/samples/` (`*.hex` = hex text, anything else = raw bytes). A bare output filename is written to `scripts/payload/`.
- **Whole upload in one transaction.** `UploadService` methods use `@Transactional(rollbackFor = IOException.class)`, so a failure part-way through an upload rolls back the batch row and every payload row already inserted. `rollbackFor` is needed because reading the body throws the checked `IOException`. Any new upload path needs the same annotation.
- **Limits are enforced in two layers.** `BatchFrameReader` rejects bad headers or lengths before reading or allocating anything. `UploadService.BatchWriter` then checks every payload from either format for per-payload size, empty payloads, payload count and total batch size. Single uploads read at most `max+1` bytes to detect oversize. The limits are `sift.*` in `application.yml`, bound by the `SiftProperties` record.
- **Errors.** Throw `BadUploadException(httpStatus, CODE, message)`. `ApiExceptionHandler` turns it into an RFC 7807 `ProblemDetail` with a stable `code` property. Clients and tests depend on these codes (`TRUNCATED_FRAME`, `PAYLOAD_TOO_LARGE`, `EMPTY_PAYLOAD`, …), so don't rename them. Framework errors such as 415 are also returned as problem details (`spring.mvc.problemdetails.enabled`).
- **Storage.** Raw payload bytes go in `payload.content BYTEA` (no files on disk), with `(batch_id, idx)` unique and `idx` starting at 0. Batch IDs look like `b_` + 12 hex characters. The response is `202 Accepted` `{batchId, payloadCount}`, which is the contract for when a worker exists.
- **Key extraction (`extract/KeyExtractor`)** is pure Java with no Spring or DB, so test it with plain unit tests. Every `{` is a candidate start, and a strict RFC 8259 parser runs from it. A complete object becomes a document and the scan skips past it. An invalid byte drops the candidate and the scan resumes at the next `{`. Hitting end-of-payload keeps the keys seen so far as a `partial` document. A "key" is every member name at any depth, including objects inside arrays, stored as distinct JSON-Pointer paths per document (`/warehouse/zone`, arrays as `*`). The API's `keys` list is the distinct names in first-seen order. `KeyExtractor.Limits` caps depth, key/path length, paths per payload and total scan work. Hitting a limit adds a warning code (`SCAN_BUDGET_EXCEEDED`, `DEPTH_LIMIT_EXCEEDED`, …; the set is `KeyExtractor.LIMIT_WARNINGS`), which is stored and returned. Informational warnings explain empty results: `NO_JSON_FOUND`, `NO_KEYS_FOUND` (only empty objects) and `INVALID_UTF8` (a candidate was dropped on a non-UTF-8 byte). Don't rename those codes.
- **Queue = the `payload` table** (`V2__extraction.sql` has the state machine): `pending → processing → done`, or back to `pending` with backoff, then `failed` after `sift.worker.max-attempts`. Claims are one `UPDATE … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED) RETURNING`, with a lease (`locked_until`), so several instances can run workers. Expired leases are re-claimed. `attempts` is a fencing token: writes check `attempts = :attempt`, so a worker whose lease expired can't overwrite a newer result. A claim with `attempts > max` means earlier attempts crashed, so it's failed with `ATTEMPTS_EXHAUSTED`. Worker settings are `sift.worker.*` (`WorkerProperties`).
- **Batch status is derived**, not stored: `completed` when every payload is `done` or `failed`, otherwise `busy`. Unknown batch → 404 `BATCH_NOT_FOUND` (`ApiException`, same problem-detail shape as `BadUploadException`).
- **API docs are annotations on `BatchController`** (springdoc 3.x, the line for Spring Boot 4; title and description in `config/OpenApiConfig`). Upload bodies are read from the raw stream, so springdoc can't infer them. OpenAPI allows one operation per path + method, so both upload handlers are documented as one operation with two request content types on `uploadSingle`, and `uploadFramed` is `@Operation(hidden = true)`. Keep it that way when changing uploads, or one content type disappears from the spec. `OpsEndpointsIT` checks this. `OpenApiConfig.recordFieldsAreRequired` marks every property of our record schemas required, and turns nullable `$ref`s into `anyOf [ref, null]`, so the UI's generated TypeScript types aren't all optional. Query parameters that override `@Schema` must state `type = "integer"`, or they turn into strings. **Any API change breaks the UI's types until it runs `npm run api:update`.**
- **Actuator** exposes only `health` and `info` over HTTP (`management.endpoints.web.exposure.include`). Health includes the DB check and liveness/readiness probes, with `show-details: always` because there's no auth yet. `info` shows build info from the `build-info` goal of `spring-boot-maven-plugin`.
- **CORS** is a servlet `CorsFilter` (`config/CorsConfig`) on `/v1/**` and `/actuator/health/**`, not `WebMvcConfigurer.addCorsMappings`. The MVC version skips responses that never reached a controller (e.g. 415), so the browser would hide those errors. Origins come from `sift.cors.allowed-origins` (`CorsProperties`; env `SIFT_CORS_ALLOWED_ORIGINS`), defaulting to `http://localhost:5173` and `:3000`. Methods are GET/POST/OPTIONS, the only allowed header is `Content-Type`, and there are no credentials. Uploads always preflight because neither upload content type is a CORS-safelisted type. `CorsIT` covers it.
- **Observability is logs plus the API.** Failures are stored on the payload row (`error_code`, `error_message`, `warnings`) and returned by `GET /v1/batches/{id}`. The worker puts `batchId`, `payloadIdx` and `attempt` in the SLF4J MDC, and they appear as fields in JSON logs (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, set in docker-compose; plain text by default). There are no Micrometer metrics on purpose. `ExtractionQueueRepository.fail` returns the new status so the log line can say "will retry" or "giving up".
- **Schema changes.** Flyway runs migrations in `src/main/resources/db/migration` at startup. Add a new `V4__...` file rather than editing existing migrations. Once a migration has run against a database, never rename, edit or delete it: Flyway validates checksums and refuses to start. The only way out is a new migration, or wiping the DB (`docker compose down -v`).

## Tests

- `*Test` files are unit tests run by surefire. `*IT` files are run by failsafe during `mvn verify`.
- `BatchUploadIT`, `ExtractionIT`, `OpsEndpointsIT` and `CorsIT` boot the full app on a random port against the real dev Postgres. Rows they create are not cleaned up, so assert on the specific batch ID returned rather than on table-wide counts. The worker runs inside the test app too, so `ExtractionIT` polls `GET` until `completed`.
- Surefire's default excludes skip `@Nested` test classes, so keep test classes flat.
