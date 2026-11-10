package com.ripsim.sift.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The "sift.cors:" section of application.yml: which browser origins (e.g. the React dashboard) may call the API.
 *
 * @param allowedOrigins exact origins (scheme://host:port), e.g. http://localhost:5173. Override with the
 *                       SIFT_CORS_ALLOWED_ORIGINS environment variable (comma-separated). Empty = no browser access.
 * @param maxAge         how long a browser may cache a preflight answer
 */
@ConfigurationProperties(prefix = "sift.cors")
public record CorsProperties(
        @DefaultValue({"http://localhost:5173", "http://localhost:3000"}) List<String> allowedOrigins,
        @DefaultValue("1h") Duration maxAge) {
}
