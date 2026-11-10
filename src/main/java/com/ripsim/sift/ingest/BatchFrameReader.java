package com.ripsim.sift.ingest;

import com.ripsim.sift.exception.BadUploadException;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the {@code application/x-sift-batch} envelope: many payloads in one request body.
 *
 * <pre>
 *   version           1 byte   currently 1
 *   count             4 bytes  big-endian unsigned: how many payloads follow
 *   count x {
 *     length          4 bytes  big-endian unsigned
 *     payload bytes   length bytes
 *   }
 * </pre>
 *
 * Usage: create the reader (this reads and checks the header), then call {@link #next()} while {@link #hasNext()}.
 * Payloads are returned one at a time, so a large batch is never held in memory all at once.
 * Every problem with the body is reported as a {@link BadUploadException}.
 */
public final class BatchFrameReader {

    public static final int VERSION = 1;
    public static final String MEDIA_TYPE = "application/x-sift-batch";

    private final DataInputStream in;
    private final long maxPayloadBytes;
    private final long count;
    private long returned;

    public BatchFrameReader(InputStream raw, long maxPayloadBytes, int maxPayloads) throws IOException {
        this.in = new DataInputStream(raw);
        this.maxPayloadBytes = maxPayloadBytes;

        long declared;
        try {
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw bad("UNSUPPORTED_FRAME_VERSION", "Unsupported batch envelope version " + version);
            }
            declared = in.readInt() & 0xFFFFFFFFL; // 4 bytes as an UNSIGNED number
        } catch (EOFException e) {
            throw bad("TRUNCATED_FRAME", "Body ended before the batch envelope header was complete");
        }
        if (declared == 0) {
            throw bad("EMPTY_UPLOAD", "Batch envelope declares zero payloads");
        }
        if (declared > maxPayloads) {
            throw new BadUploadException(413, "TOO_MANY_PAYLOADS",
                    "Batch declares " + declared + " payloads; the limit is " + maxPayloads);
        }
        this.count = declared;
    }

    public boolean hasNext() {
        return returned < count;
    }

    /** Returns the next payload's bytes. After the last payload it also checks that nothing follows it. */
    public byte[] next() throws IOException {
        try {
            long length = in.readInt() & 0xFFFFFFFFL;
            if (length > maxPayloadBytes) { // checked BEFORE reading, so a lying length can't make us read or allocate
                throw new BadUploadException(413, "PAYLOAD_TOO_LARGE",
                        "Payload " + returned + " is " + length + " bytes; the limit is " + maxPayloadBytes);
            }
            byte[] data = in.readNBytes((int) length); // returns fewer bytes if the stream ends early
            if (data.length != length) {
                throw bad("TRUNCATED_FRAME", "Body ended inside payload " + returned);
            }
            returned++;
            if (!hasNext() && in.read() != -1) {
                throw bad("TRAILING_BYTES", "Unexpected bytes after the last declared payload");
            }
            return data;
        } catch (EOFException e) {
            throw bad("TRUNCATED_FRAME", "Body ended before payload " + returned + " began");
        }
    }

    private static BadUploadException bad(String code, String message) {
        return new BadUploadException(400, code, message);
    }
}
