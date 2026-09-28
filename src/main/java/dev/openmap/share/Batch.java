package dev.openmap.share;

import dev.openmap.map.ChunkSample;
import dev.openmap.map.MapCodec;
import dev.openmap.mgrs.Streams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
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

    private static final int TRAILER_BYTES = 8;

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

    // A gzip member that also counts the compressed bytes it read.
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
                throw new IOException("a second gzip member follows the batch."
                        + " One member only.");
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
                    "an empty batch is not a message; do not send one");
        }
        if (samples.size() > MAX_SAMPLES) {
            throw new IllegalArgumentException(
                    samples.size() + " samples exceeds the " + MAX_SAMPLES + " limit");
        }
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

    // Also refuses a sample whose capturedAt is later than capturedAtCeiling.
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

            // A local, never a field: this method runs on several request threads at once.
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
                    throw new IOException("the batch is followed by more payload."
                            + " Refusing it.");
                }

            // Ask after that read, never before it.
                long compressed = member.compressed();
                if (gzipHeader + compressed + TRAILER_BYTES != message.length) {
                    throw new IOException("the message is " + message.length
                            + " bytes and the batch in it ends at "
                            + (gzipHeader + compressed + TRAILER_BYTES));
                }
                result = new Batch(server, dimension, by, sent, List.of(samples));
            }
        }
        return result;
    }

    private static boolean usableName(String value) {
        return value.length() <= MAX_NAME && !value.isBlank();
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
