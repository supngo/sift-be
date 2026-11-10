package com.ripsim.sift.models;

/**
 * One key found in a JSON document.
 *
 * @param path  JSON-Pointer-style location, e.g. {@code /warehouse/zone}; array elements appear as {@code *}
 * @param name  the decoded member name, e.g. {@code zone}
 * @param depth 1 for members of the outermost object, 2 for members of an object inside it, and so on
 */
public record JsonKey(String path, String name, int depth) {
}
