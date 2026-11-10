package com.ripsim.sift.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The "sift.worker:" section of application.yml.
 *
 * @param enabled      run the extraction worker in this instance
 * @param pollInterval pause between queue checks when the queue is empty
 * @param lease        how long a claimed payload stays reserved; a worker that dies is replaced after this
 * @param maxAttempts  attempts per payload before it is marked failed
 * @param retryBackoff wait before a retry, multiplied by the attempt number
 */
@ConfigurationProperties(prefix = "sift.worker")
public record WorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("5m") Duration lease,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("10s") Duration retryBackoff) {
}
