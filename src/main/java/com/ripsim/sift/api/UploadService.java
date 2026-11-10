package com.ripsim.sift.api;

import com.ripsim.sift.config.SiftProperties;
import com.ripsim.sift.exception.BadUploadException;
import com.ripsim.sift.ingest.BatchFrameReader;
import com.ripsim.sift.models.ApiModels.AcceptedBatch;
import com.ripsim.sift.persistence.BatchRepository;
import com.ripsim.sift.persistence.PayloadRepository;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final BatchRepository batcheRepo;
    private final PayloadRepository payloadRepo;
    private final SiftProperties props;

    public UploadService(BatchRepository batches, PayloadRepository payloads, SiftProperties props) {
        this.batcheRepo = batches;
        this.payloadRepo = payloads;
        this.props = props;
    }

    @Transactional(rollbackFor = IOException.class)
    public AcceptedBatch saveSingle(InputStream body) throws IOException {
        BatchWriter writer = startBatch();
        long max = props.maxPayloadSize().toBytes();
        // read at most one byte more than allowed, so an oversized payload is detected without reading all of it
        writer.add(body.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8)));
        return writer.finish();
    }

    @Transactional(rollbackFor = IOException.class)
    public AcceptedBatch saveFramed(InputStream body) throws IOException {
        BatchWriter writer = startBatch();
        BatchFrameReader frames = new BatchFrameReader(body, props.maxPayloadSize().toBytes(), props.maxPayloadsPerBatch());
        while (frames.hasNext()) {
            writer.add(frames.next());
        }
        return writer.finish();
    }

    private BatchWriter startBatch() {
        String batchId = "b_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        batcheRepo.create(batchId);
        return new BatchWriter(batchId);
    }

    /** Saves payloads one at a time and enforces the limits. */
    private final class BatchWriter {
        private final String batchId;
        private int count;
        private long totalBytes;

        BatchWriter(String batchId) {
            this.batchId = batchId;
        }

        void add(byte[] data) {
            long maxPayload = props.maxPayloadSize().toBytes();
            if (count >= props.maxPayloadsPerBatch()) {
                throw new BadUploadException(413, "TOO_MANY_PAYLOADS",
                        "A batch may contain at most " + props.maxPayloadsPerBatch() + " payloads");
            }
            if (data.length > maxPayload) {
                throw new BadUploadException(413, "PAYLOAD_TOO_LARGE",
                        "Payload " + count + " exceeds the maximum of " + maxPayload + " bytes");
            }
            if (data.length == 0) {
                throw new BadUploadException(400, "EMPTY_PAYLOAD", "Payload " + count + " is empty");
            }
            totalBytes += data.length;
            if (totalBytes > props.maxBatchSize().toBytes()) {
                throw new BadUploadException(413, "BATCH_TOO_LARGE",
                        "Batch exceeds the maximum of " + props.maxBatchSize().toBytes() + " bytes");
            }
            payloadRepo.insert(batchId, count, data);
            count++;
        }

        AcceptedBatch finish() {
            if (count == 0) {
                throw new BadUploadException(400, "EMPTY_UPLOAD", "No payloads were received");
            }
            log.info("Saved batch {} with {} payload(s), {} bytes", batchId, count, totalBytes);
            return new AcceptedBatch(batchId, count);
        }
    }
}
