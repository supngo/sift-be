package com.ripsim.sift.api;

import com.ripsim.sift.ingest.BatchFrameReader;
import com.ripsim.sift.models.ApiModels.AcceptedBatch;
import com.ripsim.sift.models.ApiModels.BatchList;
import com.ripsim.sift.models.ApiModels.BatchResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints. Upload bodies are read straight from the request stream (so large uploads are never buffered by the
 * framework), which means springdoc cannot infer them: the annotations below describe them.
 *
 * <p>OpenAPI allows one operation per path + method, so the two upload handlers are documented as ONE operation with
 * two request content types (on {@link #uploadSingle}); {@link #uploadFramed} is hidden from the spec.
 */
@RestController
@RequestMapping("/v1/batches")
@Tag(name = "Batches", description = "Upload binary payloads and read the keys extracted from them")
public class BatchController {

    private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final UploadService uploads;
    private final BatchQueryService queries;

    public BatchController(UploadService uploads, BatchQueryService queries) {
        this.uploads = uploads;
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "Recent batches",
            description = "Newest first, with per-status payload counts. Keys are not included; fetch "
                    + "`GET /v1/batches/{batchId}` for those.")
    @ApiResponse(responseCode = "200", description = "The batches")
    @ApiResponse(responseCode = "400", description = "`INVALID_PARAMETER`",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    public BatchList list(@Parameter(description = "How many batches to return", schema = @Schema(
                                  type = "integer", format = "int32", minimum = "1", maximum = "200", defaultValue = "50"))
                          @RequestParam(required = false) Integer limit) {
        return queries.list(limit);
    }

    @GetMapping("/{batchId}")
    @Operation(summary = "Batch status and extracted keys",
            description = "Poll until `status` is `completed`. Each payload's `keys` are the distinct key names across "
                    + "all JSON documents found in it; `documents` has every key's full path (`/warehouse/zone`, "
                    + "array elements as `*`).")
    @ApiResponse(responseCode = "200", description = "The batch")
    @ApiResponse(responseCode = "404", description = "`BATCH_NOT_FOUND`",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    public BatchResult get(@Parameter(description = "As returned by the upload", example = "b_1a2b3c4d5e6f")
                           @PathVariable String batchId) {
        return queries.get(batchId);
    }

    /** Body = ONE raw binary payload. Creates a batch of one. */
    @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "Upload payloads",
            description = """
                    The `Content-Type` header says how the body is laid out:

                    * `application/octet-stream`: the whole body is **one** payload.
                    * `application/x-sift-batch`: **several** payloads in the batch envelope. All integers are \
                    big-endian unsigned: `version` (1 byte, = 1), `count` (4 bytes), then `count` x \
                    (`length` (4 bytes) + `length` bytes of payload). `java scripts/MakeFrame.java` builds one.

                    Returns `202` immediately; extraction runs in the background. Poll `GET /v1/batches/{batchId}`.""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = {
            @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary", description = "One raw payload")),
            @Content(mediaType = BatchFrameReader.MEDIA_TYPE,
                    schema = @Schema(type = "string", format = "binary", description = "Batch envelope"))})
    @ApiResponse(responseCode = "202", description = "Saved; extraction will run in the background",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AcceptedBatch.class)))
    @ApiResponse(responseCode = "400", description = "Malformed upload: `TRUNCATED_FRAME`, `TRAILING_BYTES`, "
            + "`UNSUPPORTED_FRAME_VERSION`, `EMPTY_PAYLOAD`, `EMPTY_UPLOAD`",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "Over a limit: `PAYLOAD_TOO_LARGE`, `BATCH_TOO_LARGE`, "
            + "`TOO_MANY_PAYLOADS`",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "415", description = "Content-Type is neither of the two upload formats",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<AcceptedBatch> uploadSingle(HttpServletRequest request) throws IOException {
        return ResponseEntity.accepted().body(uploads.saveSingle(request.getInputStream()));
    }

    /** Body = many payloads in the length-prefixed batch envelope (see BatchFrameReader). Documented above. */
    @PostMapping(consumes = BatchFrameReader.MEDIA_TYPE)
    @Operation(hidden = true)
    public ResponseEntity<AcceptedBatch> uploadFramed(HttpServletRequest request) throws IOException {
        return ResponseEntity.accepted().body(uploads.saveFramed(request.getInputStream()));
    }
}
