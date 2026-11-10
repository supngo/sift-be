package com.ripsim.sift.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ripsim.sift.exception.BadUploadException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BatchFrameReaderTest {

    static byte[] frame(byte[]... payloads) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(BatchFrameReader.VERSION);
        out.writeInt(payloads.length);
        for (byte[] p : payloads) {
            out.writeInt(p.length);
            out.write(p);
        }
        return bos.toByteArray();
    }

    private static List<byte[]> readAll(byte[] body, long maxPayload, int maxCount) throws IOException {
        BatchFrameReader reader = new BatchFrameReader(new ByteArrayInputStream(body), maxPayload, maxCount);
        List<byte[]> got = new ArrayList<>();
        while (reader.hasNext()) got.add(reader.next());
        return got;
    }

    private static String codeOf(byte[] body, long maxPayload, int maxCount) {
        BadUploadException e = assertThrows(BadUploadException.class, () -> readAll(body, maxPayload, maxCount));
        return e.code();
    }

    @Test
    void roundTrip_preservesBytesAndOrder_evenWhenPayloadsLookLikeHeaders() throws IOException {
        byte[] a = {0x7B, 1, 0, 0, 0, 1, 0};
        byte[] empty = {};
        byte[] c = new byte[70_000];
        c[0] = 9;
        List<byte[]> got = readAll(frame(a, empty, c), 1 << 20, 10);
        assertEquals(3, got.size());
        assertEquals(true, Arrays.equals(a, got.get(0)));
        assertEquals(0, got.get(1).length);
        assertEquals(70_000, got.get(2).length);
        assertEquals(9, got.get(2)[0]);
    }

    @Test
    void unsupportedVersion_isRejected() {
        assertEquals("UNSUPPORTED_FRAME_VERSION", codeOf(new byte[]{9, 0, 0, 0, 1}, 100, 10));
    }

    @Test
    void headerCutShort_isRejected() {
        assertEquals("TRUNCATED_FRAME", codeOf(new byte[]{1, 0, 0}, 100, 10));
    }

    @Test
    void emptyBody_isRejected() {
        assertEquals("TRUNCATED_FRAME", codeOf(new byte[]{}, 100, 10));
    }

    @Test
    void bodyCutInsidePayload_isRejected() throws IOException {
        byte[] full = frame(new byte[100]);
        assertEquals("TRUNCATED_FRAME", codeOf(Arrays.copyOf(full, full.length - 10), 1000, 10));
    }

    @Test
    void bodyEndsBeforeDeclaredPayloadCount_isRejected() throws IOException {
        byte[] full = frame(new byte[3], new byte[3]);
        // drop the whole second payload (4-byte length + 3 bytes)
        assertEquals("TRUNCATED_FRAME", codeOf(Arrays.copyOf(full, full.length - 7), 1000, 10));
    }

    @Test
    void trailingBytes_areRejected() throws IOException {
        byte[] full = frame(new byte[3]);
        assertEquals("TRAILING_BYTES", codeOf(Arrays.copyOf(full, full.length + 1), 1000, 10));
    }

    @Test
    void oversizedPayload_isRejectedWith413() throws IOException {
        BadUploadException e = assertThrows(BadUploadException.class, () -> readAll(frame(new byte[500]), 100, 10));
        assertEquals(413, e.httpStatus());
        assertEquals("PAYLOAD_TOO_LARGE", e.code());
    }

    @Test
    void absurdDeclaredLength_isRejectedWithoutAllocating() {
        // length = FF FF FF FF = 4,294,967,295 as an unsigned number (would be -1 if read as a signed int)
        byte[] body = {1, 0, 0, 0, 1,(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertEquals("PAYLOAD_TOO_LARGE", codeOf(body, 1 << 20, 10));
    }

    @Test
    void tooManyPayloads_isRejected() throws IOException {
        assertEquals("TOO_MANY_PAYLOADS", codeOf(frame(new byte[1], new byte[1], new byte[1]), 10, 2));
    }

    @Test
    void zeroPayloads_isRejected() throws IOException {
        assertEquals("EMPTY_UPLOAD", codeOf(frame(), 10, 2));
    }
}
