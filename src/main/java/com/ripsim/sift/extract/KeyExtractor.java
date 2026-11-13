package com.ripsim.sift.extract;

import com.ripsim.sift.models.ExtractionResult;
import com.ripsim.sift.models.JsonDocument;
import com.ripsim.sift.models.JsonKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


//  Finds JSON objects embedded in arbitrary binary data and reports the keys they contain.

//  <h2>How JSON is found</h2>
//  Every {@code '{'} byte is a candidate start. From there a strict JSON (RFC 8259) parser runs: UTF-8 strings without
//  raw control characters, valid escapes, valid numbers and literals. If the object parses completely it becomes a
//  {@link JsonDocument} and the scan continues <em>after</em> it, so objects nested inside are not reported twice.
//  If parsing fails on an invalid byte, the candidate is dropped and the scan resumes at the next {@code '{'}, so a
//  stray brace in binary framing (or inside a string) never hides the real document that follows it.
//  If parsing fails only because the payload <em>ended</em> mid-object, the keys seen so far are kept as a
//  {@code partial} document: that is a truncated fragment, not noise.

//  <h2>What a "key" is</h2>
//  Every member name of every object, at any depth, including objects inside arrays. Each key is reported with its
//  path (JSON-Pointer style: {@code /warehouse/zone}; array elements are {@link #ARRAY_SEGMENT}, so {@code sku} in
//  every element of an {@code items} array is the single path "/items/&#42;/sku") and its depth (1 = member of the outermost object). Within a
//  document, each distinct path is reported once, in first-seen order.

//  <h2>Adversarial input</h2>
//  All limits are in {@link Limits}: nesting depth (no recursion blow-up), key and path length, distinct paths per
//  payload (no row explosion), and a total work budget proportional to the payload size, so input crafted to make the
//  restart-at-next-brace strategy quadratic stops early with a warning instead of hanging the worker.

// <p>Instances are immutable and thread-safe.
 
public final class KeyExtractor {

    public static final String ARRAY_SEGMENT = "*";

    /** Warnings attached to a result. Stable strings: they are stored and returned to API clients. */
    public static final String SCAN_BUDGET_EXCEEDED = "SCAN_BUDGET_EXCEEDED";
    public static final String DEPTH_LIMIT_EXCEEDED = "DEPTH_LIMIT_EXCEEDED";
    public static final String KEY_TRUNCATED = "KEY_TRUNCATED";
    public static final String PATH_TRUNCATED = "PATH_TRUNCATED";
    public static final String TOO_MANY_KEYS = "TOO_MANY_KEYS";

    /** Warnings that explain an empty or thin result. They are informational: the payload still completes as done. */
    public static final String NO_JSON_FOUND = "NO_JSON_FOUND";     // no JSON object anywhere in the payload
    public static final String NO_KEYS_FOUND = "NO_KEYS_FOUND";     // JSON objects found, but all of them empty
    public static final String INVALID_UTF8 = "INVALID_UTF8";       // a candidate was rejected on a non-UTF-8 byte

    /** The warnings that mean a limit was hit, so the keys reported may be incomplete. */
    public static final Set<String> LIMIT_WARNINGS =
            Set.of(SCAN_BUDGET_EXCEEDED, DEPTH_LIMIT_EXCEEDED, KEY_TRUNCATED, PATH_TRUNCATED, TOO_MANY_KEYS);

    /**
     * @param maxDepth           maximum nesting of objects and arrays; deeper candidates are rejected
     * @param maxKeyLength       key names longer than this (in chars) are stored truncated
     * @param maxPathLength      paths longer than this are stored truncated
     * @param maxPathsPerPayload distinct key paths kept per payload, across all documents
     * @param workFactor         the scan may examine at most {@code workFactor x payload size} bytes in total
     * @param minWork            ... but always at least this many, so small payloads are never cut short
     */
    public record Limits(int maxDepth, int maxKeyLength, int maxPathLength, int maxPathsPerPayload,
                         int workFactor, long minWork) {
        public static final Limits DEFAULT = new Limits(64, 256, 2048, 10_000, 8, 1L << 20);
    }

    private final Limits limits;

    public KeyExtractor() {
        this(Limits.DEFAULT);
    }

    public KeyExtractor(Limits limits) {
        this.limits = limits;
    }

    public ExtractionResult extract(byte[] data) {
        return new Scan(data).run();
    }

    /** State of one {@link #extract} call. */
    private final class Scan {
        private final byte[] b;
        private final int n;
        private final List<JsonDocument> documents = new ArrayList<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private int committedPaths;
        private boolean sawObject; // a complete object was parsed, even an empty one

        // state of the current candidate
        private int pos;
        private boolean eof;
        private boolean invalidUtf8;
        private Map<String, JsonKey> keys;
        private final Set<String> attemptWarnings = new LinkedHashSet<>();
        private final StringBuilder name = new StringBuilder();

        Scan(byte[] data) {
            this.b = data;
            this.n = data.length;
        }

        ExtractionResult run() {
            long budget = Math.max((long) n * limits.workFactor(), limits.minWork());
            long work = 0;
            int i = nextBrace(0);
            while (i >= 0) {
                if (work > budget) {
                    warnings.add(SCAN_BUDGET_EXCEEDED);
                    break;
                }
                keys = new LinkedHashMap<>();
                attemptWarnings.clear();
                pos = i;
                eof = false;
                invalidUtf8 = false;
                boolean complete = parseObject(1, 1, "");
                work += Math.max(1, pos - i);

                if (complete) {
                    sawObject = true;
                }
                if (complete || eof) {
                    if (!keys.isEmpty()) {
                        documents.add(new JsonDocument(i, Math.min(pos, n), !complete, List.copyOf(keys.values())));
                        committedPaths += keys.size();
                        warnings.addAll(attemptWarnings);
                    }
                    if (!complete) {
                        break; // the rest of the payload was inside this truncated document
                    }
                    i = nextBrace(pos);
                } else {
                    if (invalidUtf8) {
                        warnings.add(INVALID_UTF8);
                    }
                    i = nextBrace(i + 1);
                }
            }
            if (documents.isEmpty()) {
                warnings.add(sawObject ? NO_KEYS_FOUND : NO_JSON_FOUND);
            }
            return new ExtractionResult(List.copyOf(documents), List.copyOf(warnings));
        }

        private int nextBrace(int from) {
            for (int j = from; j < n; j++) {
                if (b[j] == '{') {
                    return j;
                }
            }
            return -1;
        }

        // ---- grammar ----

        /** {@code level} counts objects and arrays (for the depth limit); {@code objDepth} counts objects only. */
        private boolean parseObject(int level, int objDepth, String prefix) {
            if (level > limits.maxDepth()) {
                warnings.add(DEPTH_LIMIT_EXCEEDED);
                return false;
            }
            pos++; // '{'
            skipWs();
            if (pos >= n) {
                return truncated();
            }
            if (b[pos] == '}') {
                pos++;
                return true;
            }
            while (true) {
                if (pos >= n) {
                    return truncated();
                }
                if (b[pos] != '"' || !scanString(true)) {
                    return false;
                }
                String keyName = sanitize(name);
                skipWs();
                if (pos >= n) {
                    return truncated();
                }
                if (b[pos] != ':') {
                    return false;
                }
                pos++;
                String path = addKey(prefix, keyName, objDepth);
                skipWs();
                if (!parseValue(level, objDepth, path)) {
                    return false;
                }
                skipWs();
                if (pos >= n) {
                    return truncated();
                }
                if (b[pos] == ',') {
                    pos++;
                    skipWs();
                } else if (b[pos] == '}') {
                    pos++;
                    return true;
                } else {
                    return false;
                }
            }
        }

        private boolean parseArray(int level, int objDepth, String prefix) {
            if (level > limits.maxDepth()) {
                warnings.add(DEPTH_LIMIT_EXCEEDED);
                return false;
            }
            pos++; // '['
            skipWs();
            if (pos >= n) {
                return truncated();
            }
            if (b[pos] == ']') {
                pos++;
                return true;
            }
            String elementPath = child(prefix, ARRAY_SEGMENT);
            while (true) {
                if (!parseValue(level, objDepth, elementPath)) {
                    return false;
                }
                skipWs();
                if (pos >= n) {
                    return truncated();
                }
                if (b[pos] == ',') {
                    pos++;
                    skipWs();
                } else if (b[pos] == ']') {
                    pos++;
                    return true;
                } else {
                    return false;
                }
            }
        }

        private boolean parseValue(int level, int objDepth, String path) {
            if (pos >= n) {
                return truncated();
            }
            return switch (b[pos]) {
                case '{' -> parseObject(level + 1, objDepth + 1, path);
                case '[' -> parseArray(level + 1, objDepth, path);
                case '"' -> scanString(false);
                case 't' -> literal("true");
                case 'f' -> literal("false");
                case 'n' -> literal("null");
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> number();
                default -> false;
            };
        }

        private boolean literal(String word) {
            for (int k = 0; k < word.length(); k++) {
                if (pos >= n) {
                    return truncated();
                }
                if (b[pos] != word.charAt(k)) {
                    return false;
                }
                pos++;
            }
            return true;
        }

        private boolean number() {
            if (b[pos] == '-') {
                pos++;
            }
            if (pos >= n) {
                return truncated();
            }
            if (b[pos] == '0') {
                pos++;
            } else if (isDigit(b[pos])) {
                skipDigits();
            } else {
                return false;
            }
            if (pos < n && b[pos] == '.') {
                pos++;
                if (pos >= n) {
                    return truncated();
                }
                if (!isDigit(b[pos])) {
                    return false;
                }
                skipDigits();
            }
            if (pos < n && (b[pos] == 'e' || b[pos] == 'E')) {
                pos++;
                if (pos < n && (b[pos] == '+' || b[pos] == '-')) {
                    pos++;
                }
                if (pos >= n) {
                    return truncated();
                }
                if (!isDigit(b[pos])) {
                    return false;
                }
                skipDigits();
            }
            return true;
        }

        /**
         * Validates a string starting at the opening quote and moves past the closing quote. When {@code capture} is
         * set, the decoded text is put in {@link #name} (up to the key length limit).
         */
        private boolean scanString(boolean capture) {
            if (capture) {
                name.setLength(0);
            }
            pos++; // opening quote
            while (pos < n) {
                int c = b[pos] & 0xFF;
                if (c == '"') {
                    pos++;
                    return true;
                }
                if (c == '\\') {
                    if (pos + 1 >= n) {
                        return truncated();
                    }
                    int esc = b[pos + 1];
                    char decoded;
                    int width = 2;
                    switch (esc) {
                        case '"' -> decoded = '"';
                        case '\\' -> decoded = '\\';
                        case '/' -> decoded = '/';
                        case 'b' -> decoded = '\b';
                        case 'f' -> decoded = '\f';
                        case 'n' -> decoded = '\n';
                        case 'r' -> decoded = '\r';
                        case 't' -> decoded = '\t';
                        case 'u' -> {
                            if (pos + 6 > n) {
                                return truncated();
                            }
                            int cp = 0;
                            for (int k = 2; k < 6; k++) {
                                int h = Character.digit(b[pos + k], 16);
                                if (h < 0) {
                                    return false;
                                }
                                cp = (cp << 4) | h;
                            }
                            decoded = (char) cp; // surrogates are paired up (or replaced) by sanitize()
                            width = 6;
                        }
                        default -> {
                            return false;
                        }
                    }
                    if (capture) {
                        appendChar(decoded);
                    }
                    pos += width;
                    continue;
                }
                if (c < 0x20) {
                    return false; // raw control characters are not allowed in JSON strings (filters most binary)
                }
                if (c < 0x80) {
                    if (capture) {
                        appendChar((char) c);
                    }
                    pos++;
                    continue;
                }
                int len;
                int cp;
                if (c >= 0xC2 && c <= 0xDF) {
                    len = 2;
                    cp = c & 0x1F;
                } else if (c >= 0xE0 && c <= 0xEF) {
                    len = 3;
                    cp = c & 0x0F;
                } else if (c >= 0xF0 && c <= 0xF4) {
                    len = 4;
                    cp = c & 0x07;
                } else {
                    return badUtf8(); // not a valid UTF-8 lead byte
                }
                if (pos + len > n) {
                    return truncated();
                }
                for (int k = 1; k < len; k++) {
                    int cc = b[pos + k] & 0xFF;
                    if ((cc & 0xC0) != 0x80) {
                        return badUtf8();
                    }
                    cp = (cp << 6) | (cc & 0x3F);
                }
                if (len == 3 && (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF))) {
                    return badUtf8(); // overlong encoding or encoded surrogate
                }
                if (len == 4 && (cp < 0x10000 || cp > 0x10FFFF)) {
                    return badUtf8();
                }
                if (capture) {
                    if (cp >= 0x10000) {
                        appendChar(Character.highSurrogate(cp));
                        appendChar(Character.lowSurrogate(cp));
                    } else {
                        appendChar((char) cp);
                    }
                }
                pos += len;
            }
            return truncated();
        }

        // ---- helpers ----

        /** Rejects the candidate because a string holds bytes that are not UTF-8 (often Latin-1 text). */
        private boolean badUtf8() {
            invalidUtf8 = true;
            return false;
        }

        private boolean truncated() {
            eof = true;
            return false;
        }

        private void appendChar(char c) {
            if (name.length() < limits.maxKeyLength()) {
                name.append(c);
            } else {
                attemptWarnings.add(KEY_TRUNCATED);
            }
        }

        private String addKey(String prefix, String keyName, int objDepth) {
            String path = child(prefix, escape(keyName));
            if (!keys.containsKey(path)) {
                if (committedPaths + keys.size() >= limits.maxPathsPerPayload()) {
                    attemptWarnings.add(TOO_MANY_KEYS);
                } else {
                    keys.put(path, new JsonKey(path, keyName, objDepth));
                }
            }
            return path;
        }

        private String child(String prefix, String segment) {
            int room = limits.maxPathLength() - prefix.length() - 1;
            if (room <= 0) {
                attemptWarnings.add(PATH_TRUNCATED);
                return prefix;
            }
            if (segment.length() > room) {
                attemptWarnings.add(PATH_TRUNCATED);
                segment = segment.substring(0, room);
            }
            return prefix + "/" + segment;
        }

        private void skipWs() {
            while (pos < n && (b[pos] == ' ' || b[pos] == '\n' || b[pos] == '\r' || b[pos] == '\t')) {
                pos++;
            }
        }

        private void skipDigits() {
            while (pos < n && isDigit(b[pos])) {
                pos++;
            }
        }
    }

    private static boolean isDigit(byte c) {
        return c >= '0' && c <= '9';
    }

    /** JSON-Pointer escaping (RFC 6901), so a key containing '/' cannot be confused with a nested path. */
    static String escape(String keyName) {
        if (keyName.indexOf('~') < 0 && keyName.indexOf('/') < 0) {
            return keyName;
        }
        return keyName.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Makes a key safe to store: NUL (rejected by Postgres text columns) and unpaired surrogates (not encodable as
     * UTF-8) become U+FFFD.
     */
    static String sanitize(CharSequence s) {
        StringBuilder out = null;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            boolean bad;
            if (c == 0) {
                bad = true;
            } else if (Character.isHighSurrogate(c)) {
                bad = k + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(k + 1));
                if (!bad) {
                    if (out != null) {
                        out.append(c).append(s.charAt(k + 1));
                    }
                    k++;
                    continue;
                }
            } else {
                bad = Character.isLowSurrogate(c);
            }
            if (bad && out == null) {
                out = new StringBuilder(s.length()).append(s, 0, k);
            }
            if (out != null) {
                out.append(bad ? '�' : c);
            }
        }
        return out == null ? s.toString() : out.toString();
    }
}
