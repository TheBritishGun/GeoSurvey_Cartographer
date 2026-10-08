package dev.openmap.map;

import dev.openmap.json.AtomicFileReplace;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntFunction;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;

public final class MapCodec {

    public static final int MAGIC = 0x4C4E4156;

    public static final int VERSION = 4;
    public static final int VERSION_COUNTED = 3;
    public static final int VERSION_COVER = 2;
    public static final int VERSION_COLOUR = 1;

    public static final int COUNT_UNKNOWN = -1;

    private static final int INFLATE_BUFFER = 1 << 16;
    private static final int BYTE_MASK = 0xFF;
    private static final int THREE_BYTE_SHIFT = Byte.SIZE * 3;
    private static final int TWO_BYTE_SHIFT = Short.SIZE;
    private static final int ONE_BYTE_SHIFT = Byte.SIZE;
    private static final int THIRD_BYTE_OFFSET = 2;
    private static final int FOURTH_BYTE_OFFSET = 3;
    private static final long UNSIGNED_INTEGER_MASK = 0xFFFFFFFFL;

    private static final int RECORD_CHUNK_X = 0;
    private static final int RECORD_CHUNK_Z = RECORD_CHUNK_X + Integer.BYTES;
    private static final int RECORD_CAPTURED_AT = RECORD_CHUNK_Z + Integer.BYTES;
    private static final int RECORD_CAPTURE_VERSION = RECORD_CAPTURED_AT + Long.BYTES;
    private static final int RECORD_HEIGHTS = RECORD_CAPTURE_VERSION + Integer.BYTES;
    private static final int RECORD_COVER =
            RECORD_HEIGHTS + ChunkSample.COLUMNS * Short.BYTES;

    private static final int HEIGHT_BYTES = ChunkSample.COLUMNS * Short.BYTES;

    private static final int COLOUR_BYTES = ChunkSample.COLUMNS * Integer.BYTES;

    private static final int COLUMNS_CEILING_BYTES = HEIGHT_BYTES + COLOUR_BYTES;

    private static final int OVERWORLD_FLOOR = -64;

    private static final int NETHER_END_FLOOR = 0;

    private static final byte UNKNOWN_COVER = (byte) LandCover.UNKNOWN.code();

    private MapCodec() {
    }

    public static void write(MapStore store, Path path) throws IOException {
        Path parent = path.getParent();

        Path temp = path.resolveSibling(
                path.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        AtomicFileReplace.write(temp, path, written -> {
            try (OutputStream out = openTemp(written, parent)) {
                write(store, out);
            }
        });
    }

    private static OutputStream openTemp(Path temp, Path parent) throws IOException {
        OutputStream out;
        try {
            out = Files.newOutputStream(temp);
        } catch (NoSuchFileException missingParent) {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            out = Files.newOutputStream(temp);
        }
        return out;
    }

    public static void write(MapStore store, OutputStream raw) throws IOException {
        try (RegionWriter out = openForWriting(raw, store.size())) {
            for (int at = store.firstIndex(); at != 0; at = store.nextIndex(at)) {
                out.writeRecord(store.sampleAt(at));
            }
        }
    }

    private static final int SPARE_REGION_WRITERS = 4;

    private static final AtomicReferenceArray<RegionWriter> SPARE_REGION_WRITERS_POOL =
            new AtomicReferenceArray<>(SPARE_REGION_WRITERS);

    static RegionWriter openForWriting(OutputStream raw, int count) throws IOException {
        RegionWriter out = heldRegionWriter(raw);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(count);
        return out;
    }

    private static RegionWriter heldRegionWriter(OutputStream target) throws IOException {
        RegionWriter held = null;
        for (int slot = 0; held == null && slot < SPARE_REGION_WRITERS; slot++) {
            if (SPARE_REGION_WRITERS_POOL.get(slot) != null) {
                held = SPARE_REGION_WRITERS_POOL.getAndSet(slot, null);
            }
        }
        RegionWriter writer = held != null ? held : new RegionWriter(new RegionBuffer(target));
        return writer.begin(target);
    }

    private static void spareRegionWriter(RegionWriter writer) {
        boolean kept = false;
        for (int slot = 0; !kept && slot < SPARE_REGION_WRITERS; slot++) {
            if (SPARE_REGION_WRITERS_POOL.get(slot) == null) {
                kept = SPARE_REGION_WRITERS_POOL.compareAndSet(slot, null, writer);
            }
        }
    }

    static final class RegionWriter extends DataOutputStream {

        private final GzipFramer member;

        private final RegionBuffer buffer;

        private final ChunkWriter chunks = new ChunkWriter();

        private boolean spent;

        private RegionWriter(RegionBuffer buffer) {
            this(new GzipFramer(buffer, Deflater.BEST_SPEED, INFLATE_BUFFER, INFLATE_BUFFER), buffer);
        }

        private RegionWriter(GzipFramer member, RegionBuffer buffer) {
            super(member);
            this.member = member;
            this.buffer = buffer;
        }

        private RegionWriter begin(OutputStream target) throws IOException {
            buffer.retarget(target);
            member.retarget(buffer);
            member.begin();
            written = 0;
            spent = false;
            return this;
        }

        @Override
        public void close() throws IOException {
            if (spent) {
                return;
            }
            spent = true;
            IOException notClosed = null;
            try {
                member.finish();
            } catch (IOException failed) {
                notClosed = failed;
            }
            try {
                buffer.close();
            } catch (IOException failed) {
                if (notClosed == null) {
                    notClosed = failed;
                } else {
                    notClosed.addSuppressed(failed);
                }
            }
            spareRegionWriter(this);
            if (notClosed != null) {
                throw notClosed;
            }
        }

        void writeRecord(ChunkSample sample) throws IOException {
            chunks.write(this, sample);
        }
    }

    private static final class RegionBuffer extends BufferedOutputStream {

        private RegionBuffer(OutputStream target) {
            super(target, INFLATE_BUFFER);
        }

        private void retarget(OutputStream target) {
            out = target;
            count = 0;
        }

        @Override
        public void close() throws IOException {
            Throwable unflushed = null;
            try {
                flush();
            } catch (IOException | RuntimeException | Error failed) {
                unflushed = failed;
                throw failed;
            } finally {
                closeTarget(unflushed);
            }
        }

        private void closeTarget(Throwable unflushed) throws IOException {
            if (unflushed == null) {
                out.close();
            } else {
                try {
                    out.close();
                } catch (IOException | RuntimeException | Error unclosed) {
                    if (unclosed != unflushed) {
                        unclosed.addSuppressed(unflushed);
                    }
                    throw unclosed;
                }
            }
        }
    }

    public static final int CHUNK_BYTES =
            4 + 4 + 8 + 4 + ChunkSample.COLUMNS * Short.BYTES + ChunkSample.COLUMNS;

    private static int measureChunk() {
        DataOutputStream out = new DataOutputStream(new Nowhere());
        try {
            writeChunk(out, new ChunkSample(0, 0), new ChunkWriter());
        } catch (IOException impossible) {
            throw new IllegalStateException("measuring write failed", impossible);
        }
        return out.size();
    }

    static {
        int measured = measureChunk();
        if (measured != CHUNK_BYTES) {
            throw new IllegalStateException("CHUNK_BYTES is " + CHUNK_BYTES
                    + ", measured " + measured + ":"
                    + " ChunkWriter.writeInto and ChunkWriter.write"
                    + " disagree");
        }
    }

    public static ChunkSample decodeChunk(byte[] data) throws IOException {
        if (data.length < CHUNK_BYTES) {
            throw new EOFException();
        }
        int cx = getInt(data, RECORD_CHUNK_X);
        int cz = getInt(data, RECORD_CHUNK_Z);
        long capturedAt = getLong(data, RECORD_CAPTURED_AT);
        int chunkVersion = getInt(data, RECORD_CAPTURE_VERSION);
        ChunkSample sample = ChunkSample.forFullWriter(cx, cz);
        for (int lz = 0; lz < ChunkSample.SIZE; lz++) {
            for (int lx = 0; lx < ChunkSample.SIZE; lx++) {
                int i = lz * ChunkSample.SIZE + lx;
                short height = bigEndianShort(data, RECORD_HEIGHTS + i * Short.BYTES);
                sample.set(lx, lz, height, LandCover.byCode(data[RECORD_COVER + i]));
            }
        }
        sample.setCapturedAt(capturedAt);
        sample.setCaptureVersion(chunkVersion);
        return sample;
    }

    public static int getInt(byte[] from, int at) {
        return ((from[at] & BYTE_MASK) << THREE_BYTE_SHIFT)
                | ((from[at + 1] & BYTE_MASK) << TWO_BYTE_SHIFT)
                | ((from[at + THIRD_BYTE_OFFSET] & BYTE_MASK) << ONE_BYTE_SHIFT)
                | (from[at + FOURTH_BYTE_OFFSET] & BYTE_MASK);
    }

    private static long getLong(byte[] from, int at) {
        long value = 0;
        for (int k = 0; k < Long.BYTES; k++) {
            value = (value << Byte.SIZE) | (from[at + k] & BYTE_MASK);
        }
        return value;
    }

    private static short bigEndianShort(byte[] from, int at) {
        return (short) ((from[at] << Byte.SIZE) | (from[at + 1] & BYTE_MASK));
    }

    // Not thread-safe; one per decoding loop.
    public static final class ChunkReader {

        private final DecodeScratch scratch = new DecodeScratch();
        private final ArraySource from = new ArraySource();
        private final DataInputStream in = new DataInputStream(from);

        // One CHUNK_BYTES record; throws EOFException if short.
        public ChunkSample read(byte[] record) throws IOException {
            from.on(record);
            return readChunk(in, VERSION, null, scratch);
        }
    }

    private static final class DecodeScratch {

        private final short[] heights = new short[ChunkSample.COLUMNS];
        private final byte[] cover = new byte[ChunkSample.COLUMNS];
        private final byte[] rawHeights = new byte[ChunkSample.COLUMNS * Short.BYTES];
        private final byte[] columns = new byte[COLUMNS_CEILING_BYTES];
        private final ArraySource from = new ArraySource();
        private final DataInputStream body = new DataInputStream(from);
        private int cx;
        private int cz;
        private long captured;
        private int captureVersion;
    }

    private static final int SPARE_SCRATCH_SLOTS = 8;

    private static final AtomicReferenceArray<DecodeScratch> SPARE_SCRATCH =
            new AtomicReferenceArray<>(SPARE_SCRATCH_SLOTS);

    private static DecodeScratch borrowScratch() {
        DecodeScratch held = null;
        for (int slot = 0; held == null && slot < SPARE_SCRATCH_SLOTS; slot++) {
            if (SPARE_SCRATCH.get(slot) != null) {
                held = SPARE_SCRATCH.getAndSet(slot, null);
            }
        }
        return held == null ? new DecodeScratch() : held;
    }

    private static void returnScratch(DecodeScratch scratch) {
        boolean kept = false;
        for (int slot = 0; !kept && slot < SPARE_SCRATCH_SLOTS; slot++) {
            if (SPARE_SCRATCH.get(slot) == null) {
                kept = SPARE_SCRATCH.compareAndSet(slot, null, scratch);
            }
        }
    }

    private static ChunkSample readChunk(DataInputStream in, int version,
                                         IntFunction<LandCover> legacyColourToCover,
                                         DecodeScratch scratch)
            throws IOException {
        scratch.cx = in.readInt();
        scratch.cz = in.readInt();
        scratch.captured = in.readLong();
        scratch.captureVersion = version >= VERSION_COUNTED ? in.readInt() : version;
        return readRecord(in, version, legacyColourToCover, scratch);
    }

    private static ChunkSample readRecord(DataInputStream in, int version,
                                          IntFunction<LandCover> legacyColourToCover,
                                          DecodeScratch scratch)
            throws IOException {
        ChunkSample sample = ChunkSample.forFullWriter(scratch.cx, scratch.cz);
        in.readFully(scratch.rawHeights);
        for (int k = 0; k < scratch.heights.length; k++) {
            scratch.heights[k] = bigEndianShort(scratch.rawHeights, k * Short.BYTES);
        }
        if (version >= VERSION_COVER) {
            in.readFully(scratch.cover);
        } else {
            for (int i = 0; i < ChunkSample.COLUMNS; i++) {
                scratch.cover[i] =
                        (byte) convertLegacy(in.readInt(), legacyColourToCover).code();
            }
        }
        sample.setColumns(scratch.heights, scratch.cover);
        sample.setCapturedAt(scratch.captured);
        sample.setCaptureVersion(scratch.captureVersion);
        return sample;
    }

    static boolean columnsHoldGround(byte[] columns, int version,
                                     IntFunction<LandCover> legacyColourToCover) {
        boolean holdsGround = false;
        for (int column = 0; column < ChunkSample.COLUMNS && !holdsGround; column++) {
            if (holdsGroundHeight(bigEndianShort(columns, column * Short.BYTES))) {
                holdsGround = true;
            }
        }
        if (!holdsGround) {
            holdsGround = holdsGroundCover(columns, version, legacyColourToCover);
        }
        return holdsGround;
    }

    private static boolean holdsGroundCover(byte[] columns, int version,
                                            IntFunction<LandCover> legacyColourToCover) {
        int at = HEIGHT_BYTES;
        boolean holdsGround = false;
        if (version >= VERSION_COVER) {
            for (int column = 0; column < ChunkSample.COLUMNS && !holdsGround; column++) {
                if (storedCover(columns[at + column]) != UNKNOWN_COVER) {
                    holdsGround = true;
                }
            }
        } else {
            for (int column = 0; column < ChunkSample.COLUMNS && !holdsGround; column++) {
                if (convertLegacy(getInt(columns, at + column * Integer.BYTES),
                        legacyColourToCover).isKnown()) {
                    holdsGround = true;
                }
            }
        }
        return holdsGround;
    }

    private static boolean holdsGroundHeight(short height) {
        return height > OVERWORLD_FLOOR && height != NETHER_END_FLOOR;
    }

    private static byte storedCover(byte ordinal) {
        return ordinal < 0 || ordinal >= LandCover.count() ? UNKNOWN_COVER : ordinal;
    }

    public static final class ChunkWriter {

        private final byte[] heightBytes = new byte[ChunkSample.COLUMNS * Short.BYTES];
        private final byte[] coverBytes = new byte[ChunkSample.COLUMNS];

        public void write(DataOutputStream out, ChunkSample sample) throws IOException {
            out.writeInt(sample.chunkX);
            out.writeInt(sample.chunkZ);
            out.writeLong(sample.capturedAt());
            out.writeInt(sample.captureVersion());
            sample.copyColumnsInto(heightBytes, coverBytes);
            out.write(heightBytes);
            out.write(coverBytes);
        }

    }

    static void writeChunk(DataOutputStream out, ChunkSample sample, ChunkWriter writer)
            throws IOException {
        writer.write(out, sample);
    }

    public static int stream(Path path, IntFunction<LandCover> legacyColourToCover,
                             java.util.function.Consumer<ChunkSample> sink)
            throws IOException {
        return stream(path, legacyColourToCover, sink, UNLIMITED_CHUNKS);
    }

    public static final int UNLIMITED_CHUNKS = Integer.MAX_VALUE;

    public static int stream(Path path, IntFunction<LandCover> legacyColourToCover,
                             java.util.function.Consumer<ChunkSample> sink,
                             int maxChunks)
            throws IOException {
        return stream(path, legacyColourToCover, sink, maxChunks, null);
    }

    public static int stream(Path path, IntFunction<LandCover> legacyColourToCover,
                             java.util.function.Consumer<ChunkSample> sink,
                             int maxChunks,
                             java.util.function.Consumer<String> damaged)
            throws IOException {
        return stream(path, legacyColourToCover, null, sink, maxChunks, damaged);
    }

    public interface ChunkGate {

        boolean keeps(int chunkX, int chunkZ, long capturedAt, int captureVersion);

        default boolean stops() {
            return false;
        }
    }

    interface ColumnGate extends ChunkGate {

        boolean keepsColumns(byte[] columns, int version,
                             IntFunction<LandCover> legacyColourToCover);
    }

    public static int stream(Path path, IntFunction<LandCover> legacyColourToCover,
                             ChunkGate gate, java.util.function.Consumer<ChunkSample> sink,
                             int maxChunks,
                             java.util.function.Consumer<String> damaged)
            throws IOException {
        if (path == null) {
            return 0;
        }
        if (!present(path)) {
            return 0;
        }
        int read = 0;
        int promised = COUNT_UNKNOWN;
        boolean headed = false;
        boolean betweenRecords = true;
        DecodeScratch scratch = borrowScratch();
        try (InputStream raw = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(
                     new BufferedInputStream(new GZIPInputStream(raw, INFLATE_BUFFER),
                             INFLATE_BUFFER))) {
            long head = readHeader(in);
            int version = (int) (head >>> Integer.SIZE);
            int count = (int) head;
            promised = count;
            headed = true;
            if (count < 0 && count != COUNT_UNKNOWN) {
                throw new IOException("negative chunk count " + count);
            }
            if (count == COUNT_UNKNOWN) {
                count = Integer.MAX_VALUE;
            }
            int skipBytes = bodyBytes(version);
            boolean wanted = true;
            for (int i = 0; wanted && i < count; i++) {
                if (read == maxChunks) {
                    if (promised == COUNT_UNKNOWN && noMoreBytes(in)) {
                        break;
                    }
                    throw new IOException(path.getFileName() + " holds more than "
                            + maxChunks + " chunks");
                }
                if (promised == COUNT_UNKNOWN && noMoreBytes(in)) {
                    break;
                }
                betweenRecords = false;
                wanted = decodeRecord(in, version, legacyColourToCover, gate, sink, scratch, skipBytes);
                if (wanted) {
                    read++;
                    betweenRecords = true;
                }
            }
            if (wanted) {
                checkLongRead(in, damaged, path, promised);
            }
        } catch (EOFException end) {
            if (damaged != null) {
                if (!headed) {
                    damaged.accept(noHead(path.getFileName().toString()));
                } else if (promised != COUNT_UNKNOWN && read < promised) {
                    damaged.accept(shortRead(path.getFileName().toString(), promised, read));
                } else if (promised == COUNT_UNKNOWN && betweenRecords) {
                    damaged.accept(tornTailWithoutCount(path.getFileName().toString(), read));
                } else if (promised == COUNT_UNKNOWN) {
                    damaged.accept(tornMidRecord(path.getFileName().toString(), read));
                } else {
                    damaged.accept(tornTrailer(path.getFileName().toString(), promised));
                }
            }
        } finally {
            returnScratch(scratch);
        }
        return read;
    }

    static boolean present(Path path) throws IOException {
        boolean found = Files.isRegularFile(path);
        if (!found) {
            found = AtomicFileReplace.recoverStaleAside(path) && Files.isRegularFile(path);
        }
        return found;
    }

    private static boolean noMoreBytes(DataInputStream in) throws IOException {
        in.mark(1);
        boolean noMore = in.read() == -1;
        if (!noMore) {
            in.reset();
        }
        return noMore;
    }

    private static long readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("not a .landnav file");
        }
        int version = in.readInt();
        if (version < VERSION_COLOUR || version > VERSION) {
            throw new IOException("unsupported map version " + version);
        }
        return (((long) version) << Integer.SIZE) | (in.readInt() & UNSIGNED_INTEGER_MASK);
    }

    private static boolean decodeRecord(DataInputStream in, int version,
                                        IntFunction<LandCover> legacyColourToCover, ChunkGate gate,
                                        java.util.function.Consumer<ChunkSample> sink,
                                        DecodeScratch scratch, int skipBytes)
            throws IOException {
        scratch.cx = in.readInt();
        scratch.cz = in.readInt();
        scratch.captured = in.readLong();
        scratch.captureVersion = version >= VERSION_COUNTED ? in.readInt() : version;
        boolean wanted = gate == null || !gate.stops();
        if (wanted) {
            if (gate == null || gate.keeps(scratch.cx, scratch.cz, scratch.captured,
                    scratch.captureVersion)) {
                decodeColumns(in, version, legacyColourToCover, gate, sink, scratch);
            } else {
                skipRecord(in, skipBytes);
            }
        }
        return wanted;
    }

    private static void decodeColumns(DataInputStream in, int version,
                                      IntFunction<LandCover> legacyColourToCover, ChunkGate gate,
                                      java.util.function.Consumer<ChunkSample> sink,
                                      DecodeScratch scratch) throws IOException {
        if (!(gate instanceof ColumnGate ranker)) {
            sink.accept(readRecord(in, version, legacyColourToCover, scratch));
            return;
        }
        int length = bodyBytes(version);
        in.readFully(scratch.columns, 0, length);
        if (ranker.keepsColumns(scratch.columns, version, legacyColourToCover)) {
            scratch.from.on(scratch.columns, length);
            sink.accept(readRecord(scratch.body, version, legacyColourToCover, scratch));
        }
    }

    private static void checkLongRead(DataInputStream in,
                                      java.util.function.Consumer<String> damaged,
                                      Path path, int promised) throws IOException {
        if (damaged != null && promised != COUNT_UNKNOWN && in.read() != -1) {
            damaged.accept(longRead(path.getFileName().toString(), promised));
        }
    }

    private static String tornMidRecord(String what, int read) {
        return what + " has no count and ended inside a record, after "
                + read + " complete records. The ground past that point is"
                + " missing";
    }

    private static String shortRead(String what, int promised, int read) {
        return what + " says it holds " + promised + " chunks and only " + read + " of"
                + " them could be read. The ground past that point"
                + " is missing";
    }

    private static String longRead(String what, int promised) {
        return what + " says it holds " + promised + " chunks and has more bytes"
                + " after them. Those bytes were"
                + " not read";
    }

    private static String noHead(String what) {
        return what + " ended before its header. Nothing was"
                + " read";
    }

    private static String tornTrailer(String what, int promised) {
        return what + " gave all " + promised + " chunks, then ended"
                + " inside its closing bytes. The chunks were"
                + " not checked";
    }

    private static String tornTailWithoutCount(String what, int read) {
        return what + " has no count and gave " + read + " records, then ended"
                + " inside its closing bytes. The records were"
                + " not checked";
    }

    public static MapStore read(Path path, int capacity) {
        return read(path, capacity, null);
    }

    public static MapStore read(Path path, int capacity,
                                IntFunction<LandCover> legacyColourToCover) {
        MapStore store;
        if (path == null || !Files.isRegularFile(path)) {
            store = new MapStore(capacity);
        } else {
            try (InputStream in = Files.newInputStream(path)) {
                store = read(in, capacity, legacyColourToCover);
            } catch (IOException | RuntimeException e) {
                store = new MapStore(capacity);
            }
        }
        return store;
    }

    public static MapStore read(InputStream raw, int capacity) throws IOException {
        return read(raw, capacity, null);
    }

    public static MapStore read(InputStream raw, int capacity,
                                IntFunction<LandCover> legacyColourToCover) throws IOException {
        return read(raw, capacity, legacyColourToCover, null);
    }

    public static MapStore read(InputStream raw, int capacity,
                                IntFunction<LandCover> legacyColourToCover,
                                java.util.function.Consumer<String> damaged) throws IOException {
        MapStore store = new MapStore(capacity);
        int read = 0;
        int promised = COUNT_UNKNOWN;
        boolean headed = false;
        boolean betweenRecords = true;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(raw, INFLATE_BUFFER),
                        INFLATE_BUFFER))) {
            long head = readHeader(in);
            int version = (int) (head >>> Integer.SIZE);
            int count = (int) head;
            promised = count;
            headed = true;
            if (count < 0 && count != COUNT_UNKNOWN) {
                throw new IOException("negative chunk count " + count);
            }
            if (count == COUNT_UNKNOWN) {
                count = Integer.MAX_VALUE;
            }
            DecodeScratch scratch = new DecodeScratch();
            for (int i = 0; i < count; i++) {
                if (promised == COUNT_UNKNOWN && noMoreBytes(in)) {
                    break;
                }
                betweenRecords = false;
                store.putClean(readChunk(in, version, legacyColourToCover, scratch));
                read++;
                betweenRecords = true;
            }
            if (damaged != null && promised != COUNT_UNKNOWN && in.read() != -1) {
                damaged.accept(longRead("the map", promised));
            }
        } catch (EOFException truncated) {
            if (damaged != null) {
                if (!headed) {
                    damaged.accept(noHead("the map"));
                } else if (promised != COUNT_UNKNOWN && read < promised) {
                    damaged.accept(shortRead("the map", promised, read));
                } else if (promised == COUNT_UNKNOWN && betweenRecords) {
                    damaged.accept(tornTailWithoutCount("the map", read));
                } else if (promised == COUNT_UNKNOWN) {
                    damaged.accept(tornMidRecord("the map", read));
                } else {
                    damaged.accept(tornTrailer("the map", promised));
                }
            }
        }
        return store;
    }

    private static LandCover convertLegacy(int rgb, IntFunction<LandCover> converter) {
        return converter == null ? LandCover.UNKNOWN : converter.apply(rgb);
    }

    private static int bodyBytes(int version) {
        return version >= VERSION_COVER
                ? ChunkSample.COLUMNS * (Short.BYTES + Byte.BYTES)
                : ChunkSample.COLUMNS * (Short.BYTES + Integer.BYTES);
    }

    private static void skipRecord(DataInputStream in, int bytes) throws IOException {
        int left = bytes;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException();
                }
                left--;
            } else {
                left -= (int) skipped;
            }
        }
    }

    public static final class GzipFramer extends OutputStream {

        private static final byte[] HEADER = {
                (byte) 0x1F,
                (byte) 0x8B,
                (byte) Deflater.DEFLATED,
                0,
                0,
                0,
                0,
                0,
                0,
                (byte) 0xFF
        };

        private static final int TRAILER_BYTES = 8;

        private OutputStream target;

        private final Deflater def;

        private final CRC32 crc = new CRC32();

        private final byte[] pending;

        private final byte[] made;

        private final byte[] trailer = new byte[TRAILER_BYTES];

        private int held;

        private boolean spent;

        public GzipFramer(OutputStream target, int level, int coalesceBytes, int madeBytes) {
            this.target = target;
            this.def = new Deflater(level, true);
            this.pending = new byte[coalesceBytes];
            this.made = new byte[madeBytes];
        }

        void retarget(OutputStream to) {
            target = to;
        }

        public void begin() throws IOException {
            def.reset();
            crc.reset();
            held = 0;
            spent = false;
            target.write(HEADER, 0, HEADER.length);
        }

        @Override
        public void write(int one) throws IOException {
            if (held == pending.length) {
                drain();
            }
            pending[held++] = (byte) one;
        }

        @Override
        public void write(byte[] from, int at, int length) throws IOException {
            if (length == 0) {
                return;
            }
            if (length >= pending.length) {
                drain();
                feed(from, at, length);
            } else {
                if (length > pending.length - held) {
                    drain();
                }
                System.arraycopy(from, at, pending, held, length);
                held += length;
            }
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        public void finish() throws IOException {
            if (def.finished()) {
                return;
            }
            drain();
            def.finish();
            while (!def.finished()) {
                emit();
            }
            seal();
        }

        @Override
        public void close() throws IOException {
            if (spent) {
                return;
            }
            spent = true;
            IOException notClosed = null;
            try {
                finish();
            } catch (IOException failed) {
                notClosed = failed;
            }
            try {
                target.close();
            } catch (IOException failed) {
                if (notClosed == null) {
                    notClosed = failed;
                } else {
                    notClosed.addSuppressed(failed);
                }
            }
            def.end();
            if (notClosed != null) {
                throw notClosed;
            }
        }

        private void drain() throws IOException {
            if (held > 0) {
                int filled = held;
                held = 0;
                feed(pending, 0, filled);
            }
        }

        private void feed(byte[] from, int at, int length) throws IOException {
            crc.update(from, at, length);
            def.setInput(from, at, length);
            while (!def.needsInput()) {
                emit();
            }
        }

        private void emit() throws IOException {
            int produced = def.deflate(made, 0, made.length);
            if (produced > 0) {
                target.write(made, 0, produced);
            }
        }

        private void seal() throws IOException {
            putLittleInt(trailer, 0, (int) crc.getValue());
            putLittleInt(trailer, Integer.BYTES, (int) def.getBytesRead());
            target.write(trailer, 0, TRAILER_BYTES);
            target.flush();
        }

        private static void putLittleInt(byte[] into, int at, int value) {
            into[at] = (byte) value;
            into[at + 1] = (byte) (value >>> ONE_BYTE_SHIFT);
            into[at + THIRD_BYTE_OFFSET] = (byte) (value >>> TWO_BYTE_SHIFT);
            into[at + FOURTH_BYTE_OFFSET] = (byte) (value >>> THREE_BYTE_SHIFT);
        }
    }

    private static final class ArraySource extends InputStream {

        private static final byte[] NOTHING = new byte[0];

        private byte[] from = NOTHING;
        private int at;
        private int end;

        void on(byte[] data) {
            on(data, data.length);
        }

        void on(byte[] data, int length) {
            from = data;
            at = 0;
            end = length;
        }

        @Override
        public int read() {
            return at < end ? from[at++] & BYTE_MASK : -1;
        }

        @Override
        public int read(byte[] into, int off, int len) {
            if (len == 0) {
                return 0;
            }
            int left = end - at;
            if (left <= 0) {
                return -1;
            }
            int took = Math.min(len, left);
            System.arraycopy(from, at, into, off, took);
            at += took;
            return took;
        }

        @Override
        public int available() {
            return end - at;
        }
    }

    private static final class Nowhere extends OutputStream {

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
