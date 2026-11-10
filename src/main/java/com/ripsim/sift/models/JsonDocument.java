package com.ripsim.sift.models;

import java.util.List;

/**
 * One JSON object found in a payload.
 *
 * @param start   byte offset of the opening brace
 * @param end     byte offset just past the closing brace (or the end of the payload, if {@code partial})
 * @param partial true when the payload ended before the object was closed (a truncated fragment)
 * @param keys    distinct key paths, in first-seen order
 */
public record JsonDocument(int start, int end, boolean partial, List<JsonKey> keys) {
}
