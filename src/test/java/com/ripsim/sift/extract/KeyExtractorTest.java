package com.ripsim.sift.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.ripsim.sift.models.ExtractionResult;
import com.ripsim.sift.models.JsonDocument;
import com.ripsim.sift.models.JsonKey;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class KeyExtractorTest {

    static final byte[] A = hex("DE AD BE EF 01 00 00 12 9C 41 F3 07 7B 22 6F 72 64 65 72 49 64 22 3A 22 41 31 42 32 2D 39 46 22 7D 00 00 FF FF C8 33");
    static final byte[] B = hex("CA FE BA BE 02 00 00 3B 11 E0 7B 22 73 6B 75 22 3A 22 58 2D 31 30 30 22 2C 22 71 74 79 22 3A 35 2C 22 77 61 72 65 68 6F 75 73 65 22 3A 7B 22 7A 6F 6E 65 22 3A 22 43 34 22 7D 7D 0D 0A 00 00 7A 7A");
    static final byte[] C = hex("17 03 03 00 7B 42 42 00 E1 7B 22 6E 6F 74 65 22 3A 22 75 73 65 20 7B 20 77 69 74 68 20 63 61 72 65 22 2C 22 73 74 61 74 75 73 22 3A 22 6F 6B 22 7D 00 04 D2 00");

    private final KeyExtractor extractor = new KeyExtractor();

    @Test
    void a_hasExactlyOrderId() {
        ExtractionResult r = extractor.extract(A);
        assertThat(r.keyNames()).containsExactly("orderId");
        assertThat(r.documents()).hasSize(1);
        JsonDocument d = r.documents().get(0);
        assertThat(d.start()).isEqualTo(12);
        assertThat(d.end()).isEqualTo(33);
        assertThat(d.partial()).isFalse();
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void b_includesNestedKeys_withPaths() {
        ExtractionResult r = extractor.extract(B);
        assertThat(r.keyNames()).containsExactly("sku", "qty", "warehouse", "zone");
        assertThat(paths(r)).containsExactly("/sku", "/qty", "/warehouse", "/warehouse/zone");
        assertThat(r.documents().get(0).keys().get(3).depth()).isEqualTo(2);
    }

    @Test
    void c_ignoresStrayBraceInFramingAndBraceInsideString() {
        ExtractionResult r = extractor.extract(C);
        assertThat(r.keyNames()).containsExactly("note", "status");
        assertThat(r.documents()).hasSize(1);
        assertThat(r.documents().get(0).start()).isEqualTo(9); // not the stray 0x7B at offset 4
    }

    @Test
    void severalDocumentsInOnePayload() {
        ExtractionResult r = extract("\u0001\u0002{\"a\":1}\u00ff\u0000garbage{\"b\":{\"c\":true}}xx{\"a\":2}");
        assertThat(r.documents()).hasSize(3);
        assertThat(r.keyNames()).containsExactly("a", "b", "c");
    }

    @Test
    void objectsInsideArrays_shareOneWildcardPath() {
        ExtractionResult r = extract("{\"items\":[{\"sku\":\"x\"},{\"sku\":\"y\",\"tags\":[[{\"k\":1}]]}],\"n\":[1,2]}");
        assertThat(paths(r)).containsExactly("/items", "/items/*/sku", "/items/*/tags", "/items/*/tags/*/*/k", "/n");
        assertThat(r.documents().get(0).keys().stream().filter(k -> k.name().equals("k")).findFirst().orElseThrow().depth())
                .isEqualTo(3);
    }

    @Test
    void repeatedKey_isReportedOnce() {
        assertThat(paths(extract("{\"a\":1,\"a\":2}"))).containsExactly("/a");
    }

    @Test
    void escapesAreDecoded() {
        ExtractionResult r = extract("{\"say \\\"hi\\\"\":1,\"caf\\u00e9\":2,\"\\ud83d\\ude00\":3,\"tab\\tbed\":4}");
        assertThat(r.keyNames()).containsExactly("say \"hi\"", "café", "\uD83D\uDE00", "tab\tbed");
    }

    @Test
    void rawUtf8KeysAreDecoded() {
        assertThat(extract("{\"größe\":1,\"名前\":2}").keyNames()).containsExactly("größe", "名前");
    }

    @Test
    void slashAndTildeInKeys_areEscapedInPathOnly() {
        ExtractionResult r = extract("{\"a/b\":{\"~c\":1}}");
        assertThat(paths(r)).containsExactly("/a~1b", "/a~1b/~0c");
        assertThat(r.keyNames()).containsExactly("a/b", "~c");
    }

    @Test
    void allValueTypesAndWhitespace() {
        String json = "{ \"s\" : \"x\" ,\n\t\"i\":-0,\"f\":1.5e+10,\"e\":2E-3,\"t\":true,\"fa\":false,\"nu\":null,\"o\":{},\"arr\":[ ] }";
        assertThat(extract(json).keyNames()).containsExactly("s", "i", "f", "e", "t", "fa", "nu", "o", "arr");
    }

    @Test
    void emptyObjects_areNotReportedAsDocuments() {
        assertThat(extract("{}{ }").documents()).isEmpty();
    }

    @Test
    void noJsonAtAll() {
        ExtractionResult r = extractor.extract(new byte[]{0, 1, 2, 3});
        assertThat(r.documents()).isEmpty();
        assertThat(r.warnings()).containsExactly(KeyExtractor.NO_JSON_FOUND);
    }

    @Test
    void onlyEmptyObjects_warnsNoKeysFound() {
        ExtractionResult r = extract("{} [] [1,2,3] \"just a string\" 42 true null { }");
        assertThat(r.documents()).isEmpty();
        assertThat(r.warnings()).containsExactly(KeyExtractor.NO_KEYS_FOUND);
    }

    @Test
    void latin1Key_warnsInvalidUtf8_andNoJsonFound() {
        byte[] latin1 = "{\"café\":\"crème\",\"qty\":5}".getBytes(StandardCharsets.ISO_8859_1);
        ExtractionResult r = extractor.extract(latin1);
        assertThat(r.documents()).isEmpty();
        assertThat(r.warnings()).containsExactly(KeyExtractor.INVALID_UTF8, KeyExtractor.NO_JSON_FOUND);
    }

    @Test
    void invalidUtf8Candidate_beforeAValidDocument_stillWarns() {
        byte[] data = "{\"é\":1} {\"ok\":1}".getBytes(StandardCharsets.ISO_8859_1);
        ExtractionResult r = extractor.extract(data);
        assertThat(r.keyNames()).containsExactly("ok");
        assertThat(r.warnings()).containsExactly(KeyExtractor.INVALID_UTF8);
    }

    @Test
    void otherInvalidJson_doesNotWarnInvalidUtf8() {
        ExtractionResult r = extract("{'a':1} {\"ok\":1}");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void emptyPayload() {
        assertThat(extractor.extract(new byte[0]).documents()).isEmpty();
    }

    @Test
    void invalidDocument_isSkipped_andLaterDocumentFound() {
        // single quotes, trailing comma, leading zero, bad literal, bad escape: none of these is JSON
        String s = "{'a':1} {\"b\":1,} {\"c\":01} {\"d\":tru} {\"e\\x\":1} {\"ok\":1}";
        assertThat(extract(s).keyNames()).containsExactly("ok");
    }

    @Test
    void validInnerObject_insideInvalidOuter_isFound() {
        assertThat(extract("{\"outer\": {\"inner\":1} oops").keyNames()).containsExactly("inner");
    }

    @Test
    void truncatedDocument_isKeptAsPartial() {
        ExtractionResult r = extract("\u0000\u0000{\"a\":1,\"b\":{\"c\":\"unfinish");
        assertThat(r.documents()).hasSize(1);
        assertThat(r.documents().get(0).partial()).isTrue();
        assertThat(r.keyNames()).containsExactly("a", "b", "c");
    }

    @Test
    void truncatedInsideKeyName_doesNotReportTheHalfKey() {
        assertThat(extract("{\"a\":1,\"hal").keyNames()).containsExactly("a");
    }

    @Test
    void rawControlCharacterInString_isNotJson() {
        assertThat(extract("{\"a\u0001\":1}").documents()).isEmpty();
    }

    @Test
    void invalidUtf8InString_isNotJson() {
        byte[] overlong = {'{', '"', (byte) 0xC0, (byte) 0xAF, '"', ':', '1', '}'};
        byte[] badContinuation = {'{', '"', (byte) 0xE2, 'x', 'y', '"', ':', '1', '}'};
        byte[] encodedSurrogate = {'{', '"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"', ':', '1', '}'};
        assertThat(extractor.extract(overlong).documents()).isEmpty();
        assertThat(extractor.extract(badContinuation).documents()).isEmpty();
        assertThat(extractor.extract(encodedSurrogate).documents()).isEmpty();
        assertThat(extractor.extract(overlong).warnings()).contains(KeyExtractor.INVALID_UTF8);
        assertThat(extractor.extract(badContinuation).warnings()).contains(KeyExtractor.INVALID_UTF8);
        assertThat(extractor.extract(encodedSurrogate).warnings()).contains(KeyExtractor.INVALID_UTF8);
    }

    @Test
    void nulAndLoneSurrogatesInKeys_areReplaced_soTheyCanBeStored() {
        ExtractionResult r = extract("{\"a\\u0000b\":1,\"\\ud800x\":2}");
        assertThat(r.keyNames()).containsExactly("a\uFFFDb", "\uFFFDx");
    }

    @Test
    void nestingDeeperThanTheLimit_keepsTheInnermostLevelsThatFit() {
        String deep = "{\"a\":".repeat(100) + "1" + "}".repeat(100);
        ExtractionResult r = extract(deep);
        assertThat(r.warnings()).contains(KeyExtractor.DEPTH_LIMIT_EXCEEDED);
        assertThat(r.documents()).hasSize(1);
        assertThat(r.documents().get(0).keys()).hasSize(KeyExtractor.Limits.DEFAULT.maxDepth());
    }

    @Test
    void extremeNesting_terminatesWithoutStackOverflow() {
        String deep = "{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000);
        ExtractionResult r = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> extract(deep));
        assertThat(r.warnings()).contains(KeyExtractor.DEPTH_LIMIT_EXCEEDED);
    }

    @Test
    void hugeKeyName_isTruncated() {
        ExtractionResult r = extract("{\"" + "k".repeat(100_000) + "\":1}");
        assertThat(r.keyNames().get(0)).hasSize(KeyExtractor.Limits.DEFAULT.maxKeyLength());
        assertThat(r.warnings()).contains(KeyExtractor.KEY_TRUNCATED);
    }

    @Test
    void tooManyDistinctKeys_isCapped() {
        StringBuilder sb = new StringBuilder("{");
        for (int k = 0; k < 20_000; k++) {
            sb.append(k == 0 ? "" : ",").append("\"k").append(k).append("\":0");
        }
        ExtractionResult r = extract(sb.append('}').toString());
        assertThat(r.documents().get(0).keys()).hasSize(KeyExtractor.Limits.DEFAULT.maxPathsPerPayload());
        assertThat(r.warnings()).contains(KeyExtractor.TOO_MANY_KEYS);
    }

    @Test
    void quadraticRescanInput_stopsAtTheWorkBudget() {
        // 60 nested openers, then a long valid body, then garbage: every opener re-parses the whole body
        String body = "\"x\":1,".repeat(200_000);
        String s = "{\"a\":".repeat(60) + "{" + body + "\"end\":1 !!!";
        ExtractionResult r = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> extract(s));
        assertThat(r.warnings()).contains(KeyExtractor.SCAN_BUDGET_EXCEEDED);
    }

    @Test
    void randomNoise_neverThrows() {
        Random rnd = new Random(42);
        byte[] noise = new byte[4 << 20];
        rnd.nextBytes(noise);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> extractor.extract(noise));
    }

    @Test
    void noiseSprinkledWithJsonPunctuation_neverThrows() {
        Random rnd = new Random(7);
        byte[] alphabet = "{}[]\":,\\0123456789-.eEtrufalsn \u00e9".getBytes(StandardCharsets.UTF_8);
        for (int run = 0; run < 2000; run++) {
            byte[] data = new byte[rnd.nextInt(200)];
            for (int k = 0; k < data.length; k++) {
                data[k] = rnd.nextInt(4) == 0 ? (byte) rnd.nextInt(256) : alphabet[rnd.nextInt(alphabet.length)];
            }
            extractor.extract(data); // must not throw
        }
    }

    @Test
    void largePayload_isLinear() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] junk = new byte[512];
        new Random(1).nextBytes(junk);
        byte[] doc = "{\"id\":123,\"user\":{\"name\":\"x\",\"roles\":[\"a\",\"b\"]},\"items\":[{\"sku\":\"s\",\"qty\":1}]}"
                .getBytes(StandardCharsets.UTF_8);
        while (out.size() < 32 << 20) {
            out.writeBytes(junk);
            out.writeBytes(doc);
        }
        byte[] big = out.toByteArray();
        ExtractionResult r = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> extractor.extract(big));
        assertThat(r.keyNames()).contains("id", "user", "name", "roles", "items", "sku", "qty");
        assertThat(r.warnings()).doesNotContain(KeyExtractor.SCAN_BUDGET_EXCEEDED);
    }

    // ---- helpers ----

    private ExtractionResult extract(String s) {
        return extractor.extract(s.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("null")
    private static List<String> paths(ExtractionResult r) {
        return r.documents().stream().flatMap(d -> d.keys().stream()).map(JsonKey::path).toList();
    }

    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s.replace(" ", ""));
    }
}
