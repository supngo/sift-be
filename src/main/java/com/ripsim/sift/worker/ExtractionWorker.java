package com.ripsim.sift.worker;

import com.ripsim.sift.config.WorkerProperties;
import com.ripsim.sift.extract.KeyExtractor;
import com.ripsim.sift.models.ExtractionResult;
import com.ripsim.sift.persistence.ExtractionQueueRepository;
import com.ripsim.sift.persistence.ExtractionQueueRepository.ClaimedPayload;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background worker: claims pending payloads from the queue one at a time, extracts their keys and stores the result.
 *
 * <p>Each poll drains the queue, then sleeps for {@code sift.worker.poll-interval}. Safe to run in several app
 * instances at once (claims use SKIP LOCKED). Every outcome is logged with the batch and payload index, and failures
 * are stored on the payload row ({@code error_code}, {@code error_message}) so they show up in the API.
 */
@Component
@ConditionalOnProperty(prefix = "sift.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    private final ExtractionQueueRepository queue;
    private final KeyExtractor extractor = new KeyExtractor();
    private final WorkerProperties props;

    public ExtractionWorker(ExtractionQueueRepository queue, WorkerProperties props) {
        this.queue = queue;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${sift.worker.poll-interval:500ms}", initialDelayString = "1s")
    public void drain() {
        while (!Thread.currentThread().isInterrupted() && processOne()) {
            // keep going until the queue is empty
        }
    }

    /** @return true if a payload was claimed (so there may be more), false if the queue was empty */
    boolean processOne() {
        Optional<ClaimedPayload> claimed = queue.claimNext(props.lease());
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedPayload p = claimed.get();
        // every log line below carries these as fields in structured (JSON) logs, so one batch can be filtered out
        try (MDC.MDCCloseable b = MDC.putCloseable("batchId", p.batchId());
             MDC.MDCCloseable i = MDC.putCloseable("payloadIdx", Integer.toString(p.idx()));
             MDC.MDCCloseable a = MDC.putCloseable("attempt", Integer.toString(p.attempt()))) {
            process(p);
        }
        return true;
    }

    private void process(ClaimedPayload p) {
        String where = p.batchId() + "/" + p.idx();

        if (p.attempt() > props.maxAttempts()) {
            // previous attempts never reported back (worker crashed or hung past the lease): stop retrying
            log.error("Payload {} gave up after {} attempts that never finished", where, p.attempt() - 1);
            queue.giveUp(p.id(), p.attempt(), "ATTEMPTS_EXHAUSTED",
                    "Extraction did not finish within the lease on " + (p.attempt() - 1) + " attempts");
            return;
        }

        long started = System.nanoTime();
        try {
            ExtractionResult result = extractor.extract(p.content());
            if (queue.complete(p.id(), p.attempt(), result)) {
                long nanos = System.nanoTime() - started;
                if (result.documents().isEmpty()) {
                    log.warn("Payload {}: no JSON found in {} bytes", where, p.content().length);
                } else {
                    log.info("Extracted payload {}: {} bytes, {} document(s), {} key(s){} in {} ms", where,
                            p.content().length, result.documents().size(), result.keyNames().size(),
                            result.warnings().isEmpty() ? "" : ", warnings " + result.warnings(), nanos / 1_000_000);
                }
                if (!result.warnings().isEmpty()) {
                    log.warn("Payload {} hit extractor limits {}; the result may be incomplete", where,
                            result.warnings());
                }
            } else {
                log.warn("Payload {} attempt {} finished after its lease expired; result discarded", where, p.attempt());
            }
        } catch (RuntimeException e) {
            String code = e instanceof DataAccessException ? "STORAGE_ERROR" : "EXTRACTION_ERROR";
            Optional<String> next = queue.fail(p.id(), p.attempt(), props.maxAttempts(), props.retryBackoff(), code,
                    e.toString());
            String outcome = next.map(s -> s.equals("failed") ? "failed" : "retry").orElse("discarded");
            log.error("Payload {} attempt {}/{} failed ({}), {}", where, p.attempt(), props.maxAttempts(), code,
                    outcome.equals("retry") ? "will retry" : outcome.equals("failed") ? "giving up" : "lease lost", e);
        }
    }
}
