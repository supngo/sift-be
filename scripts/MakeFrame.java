import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Builds upload bodies from the sample .hex files. Single-file program: run it with {@code java}, no compile step
 * (Java 11+ source launcher). Writes to a file, so it behaves the same in PowerShell, cmd, bash and zsh.
 *
 * <pre>
 *   # many payloads in one batch envelope  -> use Content-Type: application/x-sift-batch
 *   java scripts/MakeFrame.java batch.bin scripts/samples/payload_a.hex scripts/samples/payload_b.hex scripts/samples/payload_c.hex
 *
 *   # exactly one payload, raw bytes       -> use Content-Type: application/octet-stream
 *   java scripts/MakeFrame.java --raw a.bin scripts/samples/payload_a.hex
 * </pre>
 *
 * Inputs ending in {@code .hex} are read as hex text (whitespace ignored); anything else is read as raw bytes.
 * A bare output filename (e.g. batch.bin) is written to scripts/payload/ under the project root (the nearest
 * directory up from here containing pom.xml), created if missing. An output path that includes a directory
 * (e.g. out/batch.bin) is used as given.
 *
 * <p>The envelope written here must match {@code com.ripsim.sift.ingest.BatchFrameReader}: version byte,
 * big-endian u32 count, then count x (big-endian u32 length + bytes).
 */
public class MakeFrame {

    private static final int ENVELOPE_VERSION = 1; // BatchFrameReader.VERSION

    public static void main(String[] argv) throws IOException {
        boolean raw = false;
        List<String> args = new ArrayList<>();
        for (String a : argv) {
            if (a.equals("--raw")) {
                raw = true;
            } else {
                args.add(a);
            }
        }
        if (args.size() < 2) {
            exit("""
                    usage: java scripts/MakeFrame.java [--raw] <output> <input>...
                      java scripts/MakeFrame.java batch.bin scripts/samples/payload_a.hex scripts/samples/payload_b.hex
                      java scripts/MakeFrame.java --raw a.bin scripts/samples/payload_a.hex""");
        }

        List<byte[]> payloads = new ArrayList<>();
        for (String input : args.subList(1, args.size())) {
            payloads.add(load(Path.of(input)));
        }

        byte[] data;
        if (raw) {
            if (payloads.size() != 1) {
                exit("--raw takes exactly one input file");
            }
            data = payloads.get(0);
        } else {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos); // DataOutputStream writes big-endian
            out.writeByte(ENVELOPE_VERSION);
            out.writeInt(payloads.size());
            for (byte[] p : payloads) {
                out.writeInt(p.length);
                out.write(p);
            }
            data = bos.toByteArray();
        }

        Path outPath = outputPath(args.get(0));
        Files.write(outPath, data);
        System.out.println("wrote " + data.length + " bytes to " + outPath);
    }

    private static byte[] load(Path path) throws IOException {
        if (path.toString().endsWith(".hex")) {
            return HexFormat.of().parseHex(Files.readString(path).replaceAll("\\s+", ""));
        }
        return Files.readAllBytes(path);
    }

    private static Path outputPath(String given) throws IOException {
        Path p = Path.of(given);
        if (p.getParent() == null) {
            p = projectRoot().resolve("scripts/payload").resolve(p);
        }
        p = p.toAbsolutePath().normalize();
        Files.createDirectories(p.getParent());
        return p;
    }

    /** Nearest directory at or above the working directory that contains pom.xml; the working directory if none. */
    private static Path projectRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path d = cwd; d != null; d = d.getParent()) {
            if (Files.exists(d.resolve("pom.xml"))) {
                return d;
            }
        }
        return cwd;
    }

    private static void exit(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
