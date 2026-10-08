package dev.openmap.share;

import dev.openmap.map.ChunkSample;
import dev.openmap.map.LabelText;
import dev.openmap.map.MapCodec;
import dev.openmap.mgrs.Streams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;

public record Batch(
        String server,
        String dimension,
        String by,
        long sent,
        List<ChunkSample> samples) {


    public static final int MAGIC = 0x43524E31;

    public static final int MAX_SAMPLES = 4096;

    public static final int MAX_NAME = Presence.MAX_NAME;

    private static final String NAMELESS = "unnamed";

    private static final int MAX_DERIVED_NAME = 128;

    private static final int HASH_BYTES = 8;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private static final int TRAILER_BYTES = 8;

    private static final int EXTRA_COUNTED_BYTES = 1 << 16;

    private static final int EXTRA_SINK_BYTES = 1 << 9;

    private static final int SINK_CEILING_BYTES = 1 << 22;

    private static final int SINK_FLOOR_BYTES = 1 << 14;

    private static final int SINK_COALESCE_BYTES = 1 << 12;

    private static final int BYTES_PER_SAMPLE_GUESS = 128;

    private static final int ROOM_GROWTH = 2;

    private static final ThreadLocal<Encoder> ENCODER =
            ThreadLocal.withInitial(Encoder::built);

    private static final ThreadLocal<MapCodec.ChunkWriter> WRITERS =
            ThreadLocal.withInitial(MapCodec.ChunkWriter::new);

    public static final class Room {

        private byte[] bytes = new byte[0];

        private int length;

        public byte[] bytes() {
            return bytes;
        }

        public int length() {
            return length;
        }

        private void fill(byte[] source, int count) {
            if (bytes.length < count) {
                bytes = new byte[Math.max(count, bytes.length * ROOM_GROWTH)];
            }
            System.arraycopy(source, 0, bytes, 0, count);
            length = count;
        }
    }

    private static final class Member extends GZIPInputStream {

        private long compressed;

        private boolean latched;

        private boolean reading;

        Member(InputStream source) throws IOException {
            super(source);
        }

        // Every byte leaving this stream leaves through here.
        @Override
        public int read(byte[] into, int at, int length) throws IOException {
            if (reading) {
                throw new IOException("a second gzip member"
                        + " follows the batch.");
            }
            reading = true;
            int read;
            try {
                read = super.read(into, at, length);
                latch();
            } finally {
                reading = false;
            }
            return read;
        }

        private void latch() {
            if (latched) {
                return;
            }
            long read = inf.getBytesRead();
            if (read < compressed) {

                latched = true;
                return;
            }
            compressed = read;
            if (inf.finished()) {
                latched = true;
            }
        }

        long compressed() {
            return compressed;
        }
    }

    private static final class Sink extends ByteArrayOutputStream {

        private Sink() {
            super(sinkCapacity(MAX_SAMPLES));
        }

        private void begin(int capacity) {
            reset();
            if (buf.length < capacity) {
                buf = new byte[capacity];
            }
        }

        private void copyInto(Room into) {
            into.fill(buf, count);
        }
    }

    private static final class Encoder extends DataOutputStream {

        private final Sink bytes;

        private final MapCodec.GzipFramer member;

        private Encoder(Sink bytes, MapCodec.GzipFramer member) {
            super(member);
            this.bytes = bytes;
            this.member = member;
        }

        static Encoder built() {
            Sink bytes = new Sink();
            return new Encoder(bytes, new MapCodec.GzipFramer(bytes,
                    Deflater.DEFAULT_COMPRESSION, SINK_COALESCE_BYTES, SINK_FLOOR_BYTES));
        }

        Encoder begin(int capacity) throws IOException {
            bytes.begin(capacity);
            member.begin();
            written = 0;
            return this;
        }

        void messageInto(Room into) {
            bytes.copyInto(into);
        }

        @Override
        public void close() throws IOException {
            member.finish();
        }
    }

    public Batch {
        by = by == null ? "" : by;
        check(server, dimension, by, samples);
        samples = List.copyOf(samples);
    }

    private static void check(String server, String dimension, String by,
            List<ChunkSample> samples) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(samples, "samples");
        require(server, "server");
        require(dimension, "dimension");
        if (by.length() > MAX_NAME) {
            throw new IllegalArgumentException(
                    "contributor name is " + by.length() + " characters");
        }
        if (samples.isEmpty()) {
            throw new IllegalArgumentException(
                    "an empty batch is not a message");
        }
        if (samples.size() > MAX_SAMPLES) {
            throw new IllegalArgumentException(
                    samples.size() + " samples exceed the " + MAX_SAMPLES + " limit");
        }
    }

    public static String usableServerName(String raw) {
        if (raw == null || usableName(raw)) {
            return raw;
        }
        String cleaned = LabelText.clean(raw, LabelText.UNBOUNDED_READ,
                LabelText.UNBOUNDED_READ, false);
        String kept = cleaned.isBlank() ? NAMELESS : cleaned;
        String suffix = hashSuffix(raw);
        int limit = MAX_DERIVED_NAME - suffix.length();
        if (kept.length() > limit) {
            int end = limit;
            if (Character.isHighSurrogate(kept.charAt(end - 1))
                    && Character.isLowSurrogate(kept.charAt(end))) {
                end--;
            }
            kept = kept.substring(0, end);
        }
        String derived = kept + suffix;
        return usableName(derived) ? derived : NAMELESS + suffix;
    }

    private static String hashSuffix(String raw) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("no SHA-256", absent);
        }
        for (int at = 0; at < raw.length(); at++) {
            char unit = raw.charAt(at);
            digest.update((byte) (unit >>> Byte.SIZE));
            digest.update((byte) unit);
        }
        byte[] bytes = digest.digest();
        char[] text = new char[1 + HASH_BYTES * 2];
        text[0] = '~';
        for (int at = 0; at < HASH_BYTES; at++) {
            int value = bytes[at] & 0xFF;
            text[1 + at * 2] = HEX_DIGITS[value >>> 4];
            text[2 + at * 2] = HEX_DIGITS[value & 0x0F];
        }
        return new String(text);
    }

    static int sinkCapacity(int sampleCount) {
        return Math.min(SINK_CEILING_BYTES,
                Math.max(SINK_FLOOR_BYTES, sampleCount * BYTES_PER_SAMPLE_GUESS));
    }

    public static void encodeTo(Room into, String server, String dimension, String by,
            long sent, List<ChunkSample> samples) throws IOException {
        Objects.requireNonNull(into, "into");
        encoded(server, dimension, by, sent, samples).messageInto(into);
    }

    private static Encoder encoded(String server, String dimension, String by, long sent,
            List<ChunkSample> samples) throws IOException {
        by = by == null ? "" : by;
        check(server, dimension, by, samples);
        Encoder encoder = ENCODER.get();
        try (Encoder out = encoder.begin(sinkCapacity(samples.size()))) {
            out.writeInt(MAGIC);
            out.writeUTF(server);
            out.writeUTF(dimension);
            out.writeUTF(by);
            out.writeLong(sent);
            out.writeInt(samples.size());
            MapCodec.ChunkWriter writer = WRITERS.get();
            int records = samples.size();
            for (int i = 0; i < records; i++) {
                out.writeInt(MapCodec.CHUNK_BYTES);
                writer.write(out, samples.get(i));
            }
        }
        return encoder;
    }

    public interface Header {

        boolean accepts(String by, long sent);
    }

    public static Batch decode(byte[] message) throws IOException {
        return decode(message, null);
    }

    public static Batch decode(byte[] message, Header header) throws IOException {
        return decode(message, header, Long.MAX_VALUE);
    }

    public static Batch decode(byte[] message, Header header, long capturedAtCeiling)
            throws IOException {
        ByteArrayInputStream body = new ByteArrayInputStream(message);
        Member member = new Member(body);
        int gzipHeader = message.length - body.available();
        Batch result;
        try (DataInputStream in = new DataInputStream(member)) {
            if (in.readInt() != MAGIC) {
                throw new IOException("not an Open-Map batch");
            }
            String server = name(in.readUTF(), "server");
            String dimension = name(in.readUTF(), "dimension");
            String by = in.readUTF();
            if (by.length() > MAX_NAME) {
                throw new IOException("contributor name too long");
            }
            long sent = in.readLong();
            int count = in.readInt();
            if (count <= 0 || count > MAX_SAMPLES) {
                throw new IOException("batch claims " + count + " samples");
            }
            if (header != null && !header.accepts(by, sent)) {
                result = null;
            } else {
                ChunkSample[] samples = new ChunkSample[count];
                byte[] data = new byte[MapCodec.CHUNK_BYTES];
                byte[] lengthBuf = new byte[Integer.BYTES];

            // Runs on several request threads at once.
                MapCodec.ChunkReader reader = new MapCodec.ChunkReader();
                for (int i = 0; i < count; i++) {
                    if (Streams.readNBytes(in, lengthBuf, 0, Integer.BYTES) != Integer.BYTES) {
                        throw new EOFException();
                    }
                    int length = MapCodec.getInt(lengthBuf, 0);
                    if (length != MapCodec.CHUNK_BYTES) {
                        throw new IOException("sample " + i + " claims " + length
                                + " bytes, not " + MapCodec.CHUNK_BYTES);
                    }

                    if (Streams.readNBytes(in, data, 0, length) != length) {
                        throw new IOException("batch ended inside sample " + i);
                    }
                    ChunkSample sample = reader.read(data);
                    samples[i] = sample;
                    if (sample.captureVersion() < 0) {
                        throw new IOException("sample " + i + " claims capture version "
                                + sample.captureVersion()
                                + ".");
                    }
                    if (sample.capturedAt() > capturedAtCeiling) {
                        throw new IOException("sample " + i + " claims capturedAt "
                                + sample.capturedAt() + ", past "
                                + capturedAtCeiling);
                    }
                }
                if (in.read() != -1) {
                    throw new IOException(extraBytes(in) + " extra bytes"
                            + " after the batch.");
                }

            // Ask after that read, never before it.
                long compressed = member.compressed();
                if (gzipHeader + compressed + TRAILER_BYTES != message.length) {
                    throw new IOException("the message is " + message.length
                            + " bytes; its batch ends at "
                            + (gzipHeader + compressed + TRAILER_BYTES));
                }
                result = new Batch(server, dimension, by, sent, List.of(samples));
            }
        }
        return result;
    }

    // The bytes left after the one already read, counted up to a ceiling.
    private static String extraBytes(InputStream in) throws IOException {
        long extra = 1L;
        byte[] sink = new byte[EXTRA_SINK_BYTES];
        int read = 0;
        while (read >= 0 && extra <= EXTRA_COUNTED_BYTES) {
            read = in.read(sink);
            extra += Math.max(read, 0);
        }
        return extra > EXTRA_COUNTED_BYTES ? "more than " + EXTRA_COUNTED_BYTES : String.valueOf(extra);
    }

    public static boolean usableName(String value) {
        return value.length() <= MAX_NAME && !value.isBlank()
                && value.equals(LabelText.clean(value, LabelText.UNBOUNDED_READ,
                        LabelText.UNBOUNDED_READ, false));
    }

    private static String name(String value, String what) throws IOException {
        if (!usableName(value)) {
            throw new IOException("bad " + what + " name");
        }
        return value;
    }

    private static void require(String value, String what) {
        if (!usableName(value)) {
            throw new IllegalArgumentException(
                    "a " + what + " name must be non-blank and at most "
                            + MAX_NAME + " characters"
                            + ".");
        }
    }
}
