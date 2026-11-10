package com.ripsim.sift.api;

import com.ripsim.sift.exception.ApiException;
import com.ripsim.sift.models.ApiModels.BatchList;
import com.ripsim.sift.models.ApiModels.BatchResult;
import com.ripsim.sift.models.ApiModels.BatchSummary;
import com.ripsim.sift.models.ApiModels.DocumentExtract;
import com.ripsim.sift.models.ApiModels.ExtractError;
import com.ripsim.sift.models.ApiModels.KeyPath;
import com.ripsim.sift.models.ApiModels.PayloadExtract;
import com.ripsim.sift.persistence.BatchQueryRepository.KeyRow;
import com.ripsim.sift.persistence.BatchQueryRepository.PayloadRow;
import com.ripsim.sift.persistence.BatchQueryRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the GET /v1/batches and GET /v1/batches/{id} responses from the stored extraction results. */
@Service
public class BatchQueryService {

    private final BatchQueryRepository repo;

    public BatchQueryService(BatchQueryRepository repo) {
        this.repo = repo;
    }

    static final int DEFAULT_LIST_LIMIT = 50;
    static final int MAX_LIST_LIMIT = 200;

    /** Most recent batches first, with per-status payload counts (no keys). */
    public BatchList list(Integer limit) {
        int n = limit == null ? DEFAULT_LIST_LIMIT : limit;
        if (n < 1 || n > MAX_LIST_LIMIT) {
            throw new ApiException(400, "INVALID_PARAMETER", "limit must be between 1 and " + MAX_LIST_LIMIT);
        }
        List<BatchSummary> items = repo.recentBatches(n).stream()
                .map(r -> new BatchSummary(r.batchId(), r.inProgress() == 0 ? "completed" : "busy", r.createdAt(),
                        r.payloadCount(), r.done(), r.failed(), r.inProgress()))
                .toList();
        return new BatchList(items.size(), items);
    }

    @Transactional(readOnly = true)
    public BatchResult get(String batchId) {
        if (!repo.exists(batchId)) {
            throw new ApiException(404, "BATCH_NOT_FOUND", "No batch with id " + batchId);
        }
        List<PayloadRow> payloads = repo.payloads(batchId);
        @SuppressWarnings("null")
        Map<Integer, List<KeyRow>> keysByPayload = repo.keys(batchId).stream()
                .collect(Collectors.groupingBy(KeyRow::payloadIdx));

        List<PayloadExtract> extracts = new ArrayList<>(payloads.size());
        boolean finished = true;
        for (PayloadRow p : payloads) {
            finished &= p.status().equals("done") || p.status().equals("failed");
            extracts.add(toExtract(p, keysByPayload.getOrDefault(p.idx(), List.of())));
        }
        return new BatchResult(batchId, finished ? "completed" : "busy", payloads.size(), extracts);
    }

    @SuppressWarnings("null")
    private static PayloadExtract toExtract(PayloadRow p, List<KeyRow> rows) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        List<DocumentExtract> docs = new ArrayList<>();
        int currentDoc = -1;
        List<KeyPath> docKeys = null;
        for (KeyRow r : rows) {
            if (r.docIdx() != currentDoc) {
                currentDoc = r.docIdx();
                docKeys = new ArrayList<>();
                docs.add(new DocumentExtract(r.start(), r.end(), r.partial(), docKeys));
            }
            docKeys.add(new KeyPath(r.path(), r.name(), r.depth()));
            names.add(r.name());
        }
        List<String> warnings = p.warnings() == null ? List.of() : Arrays.asList(p.warnings().split(","));
        ExtractError error = p.errorCode() == null ? null : new ExtractError(p.errorCode(), p.errorMessage());
        return new PayloadExtract(p.idx(), p.status(), List.copyOf(names), docs, warnings, error);
    }
}
