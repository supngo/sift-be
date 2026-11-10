package com.ripsim.sift.models;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What {@link KeyExtractor} found in one payload. */
public record ExtractionResult(List<JsonDocument> documents, List<String> warnings) {

    /** Distinct key names across all documents, in first-seen order (the "keys" in the API response). */
    public List<String> keyNames() {
        Set<String> names = new LinkedHashSet<>();
        for (JsonDocument d : documents) {
            for (JsonKey k : d.keys()) {
                names.add(k.name());
            }
        }
        return List.copyOf(names);
    }
}
