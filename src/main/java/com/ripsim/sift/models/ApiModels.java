package com.ripsim.sift.models;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** JSON shapes returned to clients. Grouped in one file; add more records here as endpoints are added. */
public final class ApiModels {
    private ApiModels() {
    }

    public record AcceptedBatch(@Schema(example = "b_1a2b3c4d5e6f") String batchId,
                                @Schema(description = "Payloads saved in this batch") int payloadCount) {
    }

    /** GET /v1/batches. */
    public record BatchList(@Schema(description = "Number of items returned (at most `limit`)") int count,
                            List<BatchSummary> items) {
    }

    /**
     * One batch in the list, without keys (fetch GET /v1/batches/{id} for those).
     *
     * @param inProgress payloads still pending or processing
     */
    public record BatchSummary(@Schema(example = "b_1a2b3c4d5e6f") String batchId,
                               @Schema(allowableValues = {"busy", "completed"}) String status,
                               OffsetDateTime createdAt,
                               int payloadCount,
                               @Schema(description = "Payloads extracted successfully") int done,
                               @Schema(description = "Payloads that failed for good") int failed,
                               @Schema(description = "Payloads still pending or processing") int inProgress) {
    }

    /**
     * GET /v1/batches/{id}.
     *
     * @param status "busy" while any payload is pending or processing, "completed" once all are done or failed
     */
    public record BatchResult(String batchId,
                              @Schema(allowableValues = {"busy", "completed"}) String status,
                              int payloadCount, List<PayloadExtract> extracts) {
    }

    /**
     * @param status    pending | processing | done | failed
     * @param keys      distinct key names across all documents in the payload, first-seen order
     * @param documents each JSON object found, with the full path of every key
     * @param warnings  limits that were hit while extracting (the result may be incomplete), or why nothing was found
     * @param error     why the last attempt failed; null unless status is failed (or pending a retry)
     */
    public record PayloadExtract(int payloadIndex,
                                 @Schema(allowableValues = {"pending", "processing", "done", "failed"}) String status,
                                 @Schema(example = "[\"sku\", \"qty\", \"warehouse\", \"zone\"]") List<String> keys, List<DocumentExtract> documents,
                                 @Schema(description = "Limits hit (keys may be incomplete): SCAN_BUDGET_EXCEEDED, "
                                         + "DEPTH_LIMIT_EXCEEDED, KEY_TRUNCATED, PATH_TRUNCATED, TOO_MANY_KEYS. "
                                         + "Informational: NO_JSON_FOUND, NO_KEYS_FOUND (only empty objects), "
                                         + "INVALID_UTF8 (a candidate object was rejected on a non-UTF-8 byte)")
                                 List<String> warnings,
                                 @Schema(nullable = true) ExtractError error) {
    }

    public record DocumentExtract(@Schema(description = "Byte offset of the opening brace") int start,
                                  @Schema(description = "Byte offset just past the closing brace") int end,
                                  @Schema(description = "The payload ended before the object closed") boolean partial,
                                  List<KeyPath> keys) {
    }

    public record KeyPath(@Schema(example = "/warehouse/zone") String path,
                          @Schema(example = "zone") String name,
                          @Schema(description = "1 = member of the outermost object", example = "2") int depth) {
    }

    public record ExtractError(@Schema(example = "EXTRACTION_ERROR") String code, String message) {
    }
}
