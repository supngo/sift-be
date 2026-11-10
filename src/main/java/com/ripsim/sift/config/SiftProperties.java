package com.ripsim.sift.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** The "sift:" section of application.yml, as a typed object. */
@ConfigurationProperties(prefix = "sift")
public record SiftProperties(DataSize maxPayloadSize, DataSize maxBatchSize, int maxPayloadsPerBatch) {
}
