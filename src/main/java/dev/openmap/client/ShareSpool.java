package dev.openmap.client;

import dev.openmap.map.ChunkSample;
import dev.openmap.map.MapCodec;
import dev.openmap.map.MapStorage;
import dev.openmap.mgrs.Streams;
import dev.openmap.share.Batch;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// A per-world upload backlog, held on disk; never deletes ground to make room.
// A segment is deleted only after the collector accepts it, so a crash costs at most one re-post.
final class ShareSpool implements AutoCloseable {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("geosurvey");

    // A count only; SEGMENT_PAYLOAD_BYTES is the byte half of the same seal.
    static final int SEGMENT_SAMPLES = Batch.MAX_SAMPLES;

    static final int MIB_SHIFT = 20;

    // Mirrors Envelopes.MAX_ENTRY on the collector; kept in sync here by hand, not by import.
    static final int MAX_ENTRY_BYTES = 1 << MIB_SHIFT;

    // The largest non-payload overhead one entry can add, worst case, not measured.
    static final int WRAPPING_BYTES =
            13 + 3 * (2 + 3 * Batch.MAX_NAME) + 44 + 4096 + 1024 + 1024;

    // The batch header's worst-case size, reserved uncompressed.
    private static final int BATCH_HEADER_BYTES = 4 + 3 * (2 + 3 * Batch.MAX_NAME) + 8 + 4;

    // Slack for the deflater's own lag: bytes fed in are not yet bytes counted out.
    private static final int PENDING_BYTES = 1 << 16;

    // The byte ceiling for sealing, on top of the record count; measured by compressing.
    static final int SEGMENT_PAYLOAD_BYTES =
            MAX_ENTRY_BYTES - WRAPPING_BYTES - BATCH_HEADER_BYTES - PENDING_BYTES;

    // From MapCodec's chunk record, not a local number.
    // Stamped into the segment's file name; there is no in-file header to carry it.
    static final int RECORD_BYTES = MapCodec.CHUNK_BYTES;

    private static final String SEALED_SUFFIX = ".seg";

    private static final String WRITING_SUFFIX = ".part";

    private static final String REFUSED_SUFFIX = ".refused";

    private static final String WORLD_FILE = "world";

    private static final long WORLD_LEFTOVER_MILLIS = 60L * 60L * 1000L;

    private static final int HASH_MULTIPLIER = 31;

    private static final int SEQUENCE_DIGITS = 12;

    private static final int MAX_LONG_DIGITS = 18;

    private static final int MAX_INT_DIGITS = 9;

    private static final int DECIMAL_RADIX = 10;

    private static final int NAME_CAPACITY = 32;

    private static final long NANOS_PER_MILLI = 1_000_000L;

    // Counts only a refusal the collector will repeat; a 5xx, timeout or unreachable host retries forever.
    private static final int MAX_ATTEMPTS = 3;

    private static final int RESTOCK_ATTEMPTS = 13;

    private static final long RESTOCK_PAUSE_NANOS = 1_000_000L;

    private static final long CHANGES_PER_FILE_WORK = 2L;

    // What set-aside ground may take on disk, per world.
    static final long MAX_REFUSED_BYTES = 64L << MIB_SHIFT;

    private static final int WRITE_BUFFER_BYTES = 1 << 16;

    static final int UNDRAINED_RECORDS =
            (WRITE_BUFFER_BYTES + RECORD_BYTES - 1) / RECORD_BYTES;

    private static final int SIZING_BUFFER_BYTES = 1 << 12;

    private static final int GZIP_HEADER_BYTES = 10;

    private static final int PREFIX_BYTES = Integer.BYTES;

    private static final StandardOpenOption[] NEW_SEGMENT_OPEN_OPTIONS =
            {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};

    private static final StandardOpenOption[] SEGMENT_READ_OPTIONS =
            {StandardOpenOption.READ};

    private static final StandardCopyOption[] MOVE_OPTIONS =
            {StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE};

    private static final StandardCopyOption[] MOVE_FALLBACK_OPTIONS =
            {StandardCopyOption.REPLACE_EXISTING};

    // file plus era is the identity a path alone cannot give; era never repeats.
    // tornBytes: bytes of a record that was never finished being written.
    static final class Loaded {

        private Path file;
        private List<ChunkSample> samples;
        private long fileBytes;
        private long tornBytes;
        private long era;
        private boolean pooled;

        Loaded(Path file, List<ChunkSample> samples, long fileBytes, long tornBytes,
                long era) {
            reset(file, samples, fileBytes, tornBytes, era);
        }

        private Loaded() {
            this.samples = new ArrayList<>();
        }

        private void reset(Path file, List<ChunkSample> samples, long fileBytes,
                long tornBytes, long era) {
            this.file = file;
            this.samples = samples;
            this.fileBytes = fileBytes;
            this.tornBytes = tornBytes;
            this.era = era;
            this.pooled = false;
        }

        Path file() {
            return file;
        }

        List<ChunkSample> samples() {
            return samples;
        }

        long fileBytes() {
            return fileBytes;
        }

        long tornBytes() {
            return tornBytes;
        }

        long era() {
            return era;
        }
    }

    private enum Restock {
        RECOUNTED,
        CONTENDED,
        REFUSED
    }

    private enum SegmentSuffix {
        WRITING(WRITING_SUFFIX),
        SEALED(SEALED_SUFFIX),
        REFUSED(REFUSED_SUFFIX);

        private final String suffix;

        SegmentSuffix(String suffix) {
            this.suffix = suffix;
        }

        boolean matches(String name) {
            return name.endsWith(suffix);
        }
    }

    private final Path directory;
    private final String server;
    private final String dimension;

    private final Map<Path, Integer> attempts = new HashMap<>();

    // Segments given up on, and how many times tried; under this object's monitor.
    private final Map<Path, Integer> spent = new HashMap<>();

    // Segments a take() is currently reading, so a second take skips them.
    // Under this object's monitor; released in a finally even if the read throws.
    private final java.util.Set<Path> reading = new java.util.HashSet<>();

    private java.util.TreeMap<String, SpoolFile> sealedFiles = new java.util.TreeMap<>();

    private final List<GoneSegment> goneMidDiscard = new ArrayList<>();

    // A version stamp for which backlog this spool holds; never repeats. Under this object's monitor.
    // Drawn from a process-wide counter that is not itself guarded; read once per spool and once per discard.
    private static final java.util.concurrent.atomic.AtomicLong EPOCHS =
            new java.util.concurrent.atomic.AtomicLong();

    private long epoch = EPOCHS.incrementAndGet();

    private FileChannel channel;
    private Path writingFile;
    private int inSegment;
    private long segmentOpenedAtNanos;
    private long nextSequence;
    private final StringBuilder nameBuilder = new StringBuilder(NAME_CAPACITY);
    private String pendingSealedName;
    private volatile Thread writeTurn;
    private boolean sealWanted;
    private boolean endWanted;
    private volatile int inFlight;
    private long changes;

    private Counting measured;

    private DataOutputStream measuring;

    private Sizing sizing;

    private byte[] measuringRecord;

    private SegmentStream spareStream;

    private RecordSink recordSink;

    private DataOutputStream recordOut;

    private final MapCodec.ChunkWriter writer = new MapCodec.ChunkWriter();

    // Read without the monitor, by the tick thread and the poster.
    private volatile DataOutputStream writing;
    private volatile int sealedSegments;
    private volatile long records;
    private volatile long bytes;

    private final RefusedBacklog refusedBacklog = new RefusedBacklog(this);

    void refusedCap(long bytes) {
        refusedBacklog.cap = bytes;
    }

    void trimRefusedForTest() {
        refusedBacklog.trim(0);
    }
    private volatile boolean closed;

    private ShareSpool(Path directory, String server, String dimension) {
        this.directory = directory;
        this.server = server;
        this.dimension = dimension;
    }

    static ShareSpool open(Path root, String server, String dimension)
            throws IOException {
        String safeServer = sanitiseServer(server);
        String safeDimension = sanitiseDimension(dimension);
        String folder = folderFor(safeServer, safeDimension, server, dimension);
        Path directory = root.resolve(folder);
        Path earlier = earlierFolder(root, safeServer, folder);
        if (earlier != null && namesWorld(earlier, server, dimension)) {
            directory = earlier;
        }
        Files.createDirectories(directory);
        DIRECTORY_CREATES.incrementAndGet();
        ShareSpool spool = new ShareSpool(directory, server, dimension);
        List<SpoolFile> listing = spool.listDirectory();
        spool.rememberWorld(listing);
        spool.recover(listing);
        return spool;
    }

    // Names, from each world's own file; not the sanitised directory name.
    static List<World> worldsUnder(Path root) {
        List<World> found = new ArrayList<>();
        if (root != null && Files.isDirectory(root)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (Path entry : entries) {
                    if (!Files.isDirectory(entry)) {
                        continue;
                    }
                    World world = worldOf(entry);
                    if (world != null) {
                        found.add(world);
                    }
                }
            } catch (IOException unreadable) {
                LOGGER.warn("geosurvey could not read the upload spool at {}."
                        + " Ground stays where it is.",
                        root, unreadable);
            }
        }
        return found;
    }

    private static World worldOf(Path directory) {
        Path world = directory.resolve(WORLD_FILE);
        if (!Files.isRegularFile(world)) {
            return null;
        }
        World found;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(world)))) {
            String server = in.readUTF();
            if (server.length() > Batch.MAX_NAME || server.isBlank()) {
                found = refusedWorld(directory);
            } else {
                String dimension = in.readUTF();
                if (dimension.length() > Batch.MAX_NAME || dimension.isBlank()) {
                    found = refusedWorld(directory);
                } else {
                    found = new World(server, dimension);
                }
            }
        } catch (IOException unreadable) {
            LOGGER.warn("geosurvey is leaving the spool at {} alone: world file"
                    + " unreadable ({}). Nothing deleted.",
                    directory, unreadable.toString());
            found = null;
        }
        return found;
    }

    private static World refusedWorld(Path directory) {
        LOGGER.warn("geosurvey is leaving the spool at {} alone: it names a"
                + " world no batch could carry.", directory);
        return null;
    }

    private static Path earlierFolder(Path root, String safeServer, String current) {
        int dot = safeServer.indexOf('.');
        if (dot <= 0 || safeServer.charAt(dot - 1) != '_') {
            return null;
        }
        String earlier = safeServer.substring(0, dot - 1) + safeServer.substring(dot)
                + current.substring(safeServer.length());
        return root.resolve(earlier);
    }

    record World(String server, String dimension) {
    }

    private static boolean namesWorld(Path directory, String server, String dimension) {
        World world = worldOf(directory);
        return world != null && server.equals(world.server())
                && dimension.equals(world.dimension());
    }

    static String folderFor(String server, String dimension) {
        return folderFor(sanitiseServer(server), sanitiseDimension(dimension),
                server, dimension);
    }

    private static String folderFor(String safeServer, String safeDimension,
            String server, String dimension) {
        // A separator no host name or resource id can hold.
        int hash = 0;
        for (int i = 0; i < server.length(); i++) {
            hash = HASH_MULTIPLIER * hash + server.charAt(i);
        }
        hash = HASH_MULTIPLIER * hash + '|';
        for (int i = 0; i < dimension.length(); i++) {
            hash = HASH_MULTIPLIER * hash + dimension.charAt(i);
        }
        String hex = Integer.toHexString(hash);
        return safeServer + "@" + safeDimension + "-"
                + "00000000".substring(hex.length()) + hex;
    }

    private void rememberWorld(List<SpoolFile> listing) throws IOException {
        sweepWorldLeftovers(listing);
        Path world = directory.resolve(WORLD_FILE);
        if (!Files.isRegularFile(world)) {
            Path staging = Files.createTempFile(directory, WORLD_FILE + ".", ".writing");
            boolean moved = false;
            try {
                try (DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(staging)))) {
                    out.writeUTF(server);
                    out.writeUTF(dimension);
                }
                move(staging, world);
                moved = true;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(staging);
                }
            }
        }
    }

    private void sweepWorldLeftovers(List<SpoolFile> listing) {
        String ours = WORLD_FILE + ".";
        long leftOverBy = System.currentTimeMillis() - WORLD_LEFTOVER_MILLIS;
        for (SpoolFile each : listing) {
            removeWorldLeftoverIfOld(each.file(), ours, leftOverBy);
        }
    }

    private void removeWorldLeftoverIfOld(Path name, String ours, long leftOverBy) {
        String leaf = name.getFileName().toString();
        if (!leaf.startsWith(ours) || !leaf.endsWith(".writing")) {
            return;
        }
        try {
            if (Files.isRegularFile(name)
                    && Files.getLastModifiedTime(name).toMillis() <= leftOverBy) {
                if (Files.deleteIfExists(name)) {
                    LOGGER.info("geosurvey removed {}: an unfinished world marker for"
                            + " {} {}."
                            + " Nothing reads it.", name, server, dimension);
                }
            }
        } catch (IOException | RuntimeException leftBehind) {
            LOGGER.warn("geosurvey could not remove {}: a leftover world marker for"
                    + " {} {} ({}). Delete it by hand"
                    + ".", name, server, dimension, leftBehind.toString());
        }
    }

    // Seals a leftover .part from a stopped session; only its torn tail is lost.
    private void recover(List<SpoolFile> listing) throws IOException {

        long highest = -1;
        Map<Path, Long> oversized = null;
        for (SpoolFile each : listing) {
            Path entry = each.file();
            String name = entry.getFileName().toString();
            if (SegmentSuffix.WRITING.matches(name)) {
                Path sealed = directory.resolve(
                        name.substring(0, name.length() - WRITING_SUFFIX.length())
                                + SEALED_SUFFIX);
                move(entry, sealed);
                entry = sealed;
                name = sealed.getFileName().toString();
            }
            long size = each.size();
            if (SegmentSuffix.SEALED.matches(name)) {
                int stemEnd = name.length() - SEALED_SUFFIX.length();
                int stamp = stampOf(name, stemEnd);
                highest = Math.max(highest, sequenceIn(name, stemEnd, stamp));

                // Checks the stride of a file this process did not write.
                int stride = strideOf(name, stemEnd, stamp);
                if (stride != RECORD_BYTES) {
                    setAside(entry, name, size, stride);
                } else if (size / RECORD_BYTES > SEGMENT_SAMPLES) {
                    if (oversized == null) {
                        oversized = new java.util.LinkedHashMap<>();
                    }
                    oversized.put(entry, size);
                } else {
                    sealedSegments++;
                    records += size / RECORD_BYTES;
                    bytes += size;
                    sealedFiles.put(name, new SpoolFile(entry, size));
                }
            } else {
                if (SegmentSuffix.REFUSED.matches(name)) {
                    int stemEnd = name.length() - REFUSED_SUFFIX.length();
                    int stamp = stampOf(name, stemEnd);
                    highest = Math.max(highest, sequenceIn(name, stemEnd, stamp));
                    refusedBacklog.quarantinedRecords += heldAt(strideOf(name, stemEnd, stamp), size);
                    refusedBacklog.quarantinedBytes += size;
                }
            }
        }
        nextSequence = highest + 1;
        if (oversized != null) {
            for (Map.Entry<Path, Long> each : oversized.entrySet()) {
                respool(each.getKey(), each.getValue());
            }
        }
        if (records > 0) {
            LOGGER.info("geosurvey found {} surveyed chunks queued for"
                    + " {} {} and will send them.",
                    records, server, dimension);
        }
        if (refusedBacklog.quarantinedRecords > 0) {
            LOGGER.warn("geosurvey is holding {} surveyed chunks for {} {} that the"
                    + " collector refused. Set aside at {}, not retried. New ground"
                    + " is unaffected.",
                    refusedBacklog.quarantinedRecords, server, dimension, directory);
        }
    }

    private void respool(Path segment, long size) {
        long before = records;
        boolean batched;
        try (InputStream in = new BufferedInputStream(
                Files.newInputStream(segment), WRITE_BUFFER_BYTES)) {
            byte[] record = new byte[RECORD_BYTES];
            while (Streams.readNBytes(in, record, 0, RECORD_BYTES) == RECORD_BYTES) {
                appendEncoded(record, 0);
            }
            seal();
            batched = true;
        } catch (IOException couldNotRespool) {
            long written = records - before;
            records = Math.max(0L, records - written);
            bytes = Math.max(0L, bytes - written * RECORD_BYTES);
            keepOversized(segment, size, written);
            LOGGER.warn("geosurvey found {} surveyed chunks for {} {} in {}, too big for"
                    + " one batch, and could not rewrite them as batches"
                    + " that can be sent ({}). Left in place, set aside"
                    + " instead of sent in part; {} already written and will be"
                    + " sent. Nothing deleted.",
                    size / RECORD_BYTES, server, dimension, segment,
                    couldNotRespool.toString(), written);
            batched = false;
        }
        if (batched) {
            long respooled = records - before;
            boolean deleted;
            try {
                Files.delete(segment);
                deleted = true;
            } catch (IOException couldNotDelete) {
                records = Math.max(0L, records - respooled);
                bytes = Math.max(0L, bytes - respooled * RECORD_BYTES);
                keepOversized(segment, size, respooled);
                LOGGER.warn("geosurvey wrote the {} surveyed chunks in {} out again as batches"
                        + " that can be sent, and could not delete the original ({})."
                        + " Set aside instead of sent twice. Delete it by"
                        + " hand.", respooled, segment, couldNotDelete.toString());
                deleted = false;
            }
            if (deleted) {
                LOGGER.info("geosurvey found {} surveyed chunks for {} {} in {}, too big for"
                        + " one batch. Rewrote as batches and removed it."
                        + " {} bytes after the last whole chunk were"
                        + " never finished.", respooled, server, dimension,
                        segment.getFileName(), size - respooled * RECORD_BYTES);
            }
        }
    }

    private void keepOversized(Path segment, long size, long rewritten) {
        refusedBacklog.keepOversized(segment, size, rewritten);
    }

    // Moves an unreadable-stride segment to .refused rather than reading it.
    private void setAside(Path segment, String name, long size, int stride) {
        refusedBacklog.setAside(segment, name, size, stride);
    }

    private static IOException moveFailure(Path from, Path to) {
        IOException failure;
        try {
            move(from, to);
            failure = null;
        } catch (IOException notMoved) {
            failure = notMoved;
        }
        return failure;
    }

    private static IOException deleteFailure(Path file) {
        IOException failure;
        try {
            if (!Files.deleteIfExists(file)) {
                LOGGER.debug("geosurvey found {} already gone when it tried to delete it.",
                        file);
            }
            failure = null;
        } catch (IOException notDeleted) {
            failure = notDeleted;
        }
        return failure;
    }

    private void beginFileWork() {
        inFlight++;
        changes++;
    }

    private void endFileWork() {
        inFlight--;
        changes++;
    }

    // Keeps the quarantine under MAX_REFUSED_BYTES, oldest first.
    private record RefusedFile(long sequence, Path path) {
    }

    private record Unmoved(long size, long held, int tries) {
    }

    private record GoneSegment(String name, long fileBytes) {
    }

    private void trimRefused() {
        refusedBacklog.trim(1);
    }

    private static final class RefusedBacklog {

        private enum Trimmed {
            DELETED,
            GONE,
            KEPT
        }

        private final ShareSpool owner;

        private volatile long quarantinedRecords;

        private volatile long quarantinedBytes;

        private volatile long cap = MAX_REFUSED_BYTES;

        private final Map<Path, Unmoved> unmoved = new HashMap<>();

        private final Map<String, Long> rewrittenOfKept = new HashMap<>();

        private boolean recountWanted = false;

        private RefusedBacklog(ShareSpool owner) {
            this.owner = owner;
        }

        private void keepOversized(Path segment, long size, long rewritten) {
            owner.sealedSegments++;
            owner.records += size / RECORD_BYTES;
            owner.bytes += size;
            rewrittenOfKept.put(segment.getFileName().toString(),
                    Long.valueOf(Math.min(rewritten, size / RECORD_BYTES)));
            owner.sealedFiles.put(segment.getFileName().toString(), new SpoolFile(segment, size));
        }

        private void setAside(Path segment, String name, long size, int stride) {
            Path aside = owner.directory.resolve(
                    name.substring(0, name.length() - SEALED_SUFFIX.length())
                            + REFUSED_SUFFIX);
            synchronized (owner) {
                owner.beginFileWork();
            }
            try {
                IOException couldNotMove = moveFailure(segment, aside);
                if (couldNotMove != null) {
                    LOGGER.warn("geosurvey found {} bytes of surveyed ground at {} written"
                            + " at a record size this build does not read, and"
                            + " could not move it aside ({}). Left in place,"
                            + " not contributed.",
                            size, segment, couldNotMove.toString());
                } else {
                    synchronized (owner) {
                        quarantinedRecords += heldAt(stride, size);
                        quarantinedBytes += size;
                    }
                    LOGGER.warn("geosurvey set {} bytes of surveyed ground for {} {} aside at"
                            + " {}: different record size."
                            + " Nothing deleted; your map still has it."
                            + "",
                            size, owner.server, owner.dimension, aside);
                }
            } finally {
                synchronized (owner) {
                    owner.endFileWork();
                }
            }
        }

        private void trim(int callerClaims) {
            retryUnmoved();
            recountIfWanted(callerClaims);
            if (quarantinedBytes > cap) {
                long seen;
                boolean quiet;
                synchronized (owner) {
                    seen = owner.changes;
                    quiet = owner.inFlight == callerClaims;
                }
                List<RefusedFile> oldestFirst = refusedOldestFirst();
                if (oldestFirst != null) {
                    trimOldestFirst(oldestFirst, seen, quiet, callerClaims);
                }
            }
        }

        private List<RefusedFile> refusedOldestFirst() {
            List<RefusedFile> oldestFirst = new ArrayList<>();
            boolean listed;
            try (DirectoryStream<Path> entries =
                         Files.newDirectoryStream(owner.directory, "*" + REFUSED_SUFFIX)) {
                for (Path entry : entries) {
                    oldestFirst.add(new RefusedFile(
                            sequenceOf(entry.getFileName().toString(), REFUSED_SUFFIX), entry));
                }
                listed = true;
            } catch (IOException unreadable) {
                listed = false;
            }
            if (listed) {
                List<Path> stillUnmoved;
                synchronized (owner) {
                    stillUnmoved = new ArrayList<>(unmoved.keySet());
                }
                for (Path segment : stillUnmoved) {
                    oldestFirst.add(new RefusedFile(
                            sequenceOf(segment.getFileName().toString(), SEALED_SUFFIX), segment));
                }
                oldestFirst.sort(java.util.Comparator.comparingLong(RefusedFile::sequence));
            }
            return listed ? oldestFirst : null;
        }

        private void trimOldestFirst(List<RefusedFile> oldestFirst, long seen, boolean quiet,
                int callerClaims) {
            long expected = seen;
            int next = 0;
            boolean trimming = true;
            while (trimming && quarantinedBytes > cap) {
                if (next >= oldestFirst.size()) {
                    // When the total and the directory disagree, trust the directory.
                    synchronized (owner) {
                        if (quiet && owner.changes == expected) {
                            quarantinedBytes = 0;
                            quarantinedRecords = 0;
                            owner.changes++;
                        }
                    }
                    trimming = false;
                } else {
                    Path oldest = oldestFirst.get(next).path();
                    next++;
                    expected += CHANGES_PER_FILE_WORK;
                    if (SegmentSuffix.REFUSED.matches(oldest.getFileName().toString())) {
                        Trimmed outcome = trimOne(oldest);
                        if (outcome == Trimmed.GONE) {
                            trimming = recountAfterGone(quiet, expected, callerClaims);
                            if (trimming) {
                                expected++;
                            }
                        } else {
                            trimming = outcome == Trimmed.DELETED;
                        }
                    } else {
                        trimming = trimOneUnmoved(oldest);
                    }
                }
            }
        }

        private void recountIfWanted(int callerClaims) {
            long seen;
            boolean due;
            synchronized (owner) {
                seen = owner.changes;
                due = recountWanted && owner.inFlight == callerClaims;
            }
            if (due) {
                recountFromDisk(seen, callerClaims);
            }
        }

        private boolean recountAfterGone(boolean quiet, long expected, int callerClaims) {
            boolean counted;
            if (quiet) {
                counted = recountFromDisk(expected, callerClaims);
            } else {
                synchronized (owner) {
                    recountWanted = true;
                }
                counted = false;
            }
            return counted;
        }

        private boolean recountFromDisk(long expected, int callerClaims) {
            boolean counted;
            try {
                List<SpoolFile> listing = owner.listDirectory();
                long asideRecords = 0L;
                long asideBytes = 0L;
                for (SpoolFile each : listing) {
                    String name = each.file().getFileName().toString();
                    if (SegmentSuffix.REFUSED.matches(name)) {
                        asideRecords += heldAside(name, each.size());
                        asideBytes += each.size();
                    }
                }
                synchronized (owner) {
                    counted = owner.changes == expected && owner.inFlight == callerClaims;
                    if (counted) {
                        for (Unmoved stuck : unmoved.values()) {
                            asideRecords += stuck.held();
                            asideBytes += stuck.size();
                        }
                        quarantinedRecords = asideRecords;
                        quarantinedBytes = asideBytes;
                        recountWanted = false;
                        owner.changes++;
                    } else {
                        recountWanted = true;
                    }
                }
            } catch (IOException unreadable) {
                synchronized (owner) {
                    recountWanted = true;
                }
                counted = false;
            }
            return counted;
        }

        private void retryUnmoved() {
            List<Map.Entry<Path, Unmoved>> candidates;
            synchronized (owner) {
                candidates = unmoved.isEmpty() ? null : new ArrayList<>(unmoved.entrySet());
            }
            if (candidates != null) {
                for (Map.Entry<Path, Unmoved> candidate : candidates) {
                    Unmoved info = candidate.getValue();
                    if (info.tries() >= MAX_ATTEMPTS) {
                        continue;
                    }
                    Path segment = candidate.getKey();
                    String name = segment.getFileName().toString();
                    Path aside = owner.directory.resolve(
                            name.substring(0, name.length() - SEALED_SUFFIX.length())
                                    + REFUSED_SUFFIX);
                    synchronized (owner) {
                        owner.beginFileWork();
                    }
                    try {
                        IOException stillCannotMove = moveFailure(segment, aside);
                        synchronized (owner) {
                            if (stillCannotMove == null) {
                                if (unmoved.remove(segment) == null) {
                                    quarantinedRecords += info.held();
                                    quarantinedBytes += info.size();
                                }
                            } else if (stillCannotMove
                                    instanceof java.nio.file.NoSuchFileException) {
                                settleGone(segment);
                            } else {
                                unmoved.replace(segment,
                                        new Unmoved(info.size(), info.held(), info.tries() + 1));
                            }
                        }
                        if (stillCannotMove == null) {
                            LOGGER.warn("geosurvey moved {} aside on a retry:"
                                    + " {} surveyed chunks now at {}.",
                                    segment, info.held(), aside);
                        }
                    } finally {
                        synchronized (owner) {
                            owner.endFileWork();
                        }
                    }
                }
            }
        }

        private Trimmed trimOne(Path oldest) {
            long size = sizeOf(oldest);
            synchronized (owner) {
                owner.beginFileWork();
            }
            Trimmed outcome;
            try {
                if (Files.deleteIfExists(oldest)) {
                    long held = heldAside(oldest.getFileName().toString(), size);
                    synchronized (owner) {
                        quarantinedBytes -= size;
                        quarantinedRecords -= held;
                    }
                    LOGGER.warn("geosurvey deleted {} set-aside chunks for {} {} ({}): backlog"
                            + " over {} MiB. New ground still goes"
                            + " up.",
                            held, owner.server, owner.dimension, oldest.getFileName(),
                            cap >> MIB_SHIFT);
                    outcome = Trimmed.DELETED;
                } else {
                    LOGGER.debug("geosurvey found {} already gone when it tried to delete it.",
                            oldest);
                    outcome = Trimmed.GONE;
                }
            } catch (IOException couldNotDelete) {
                LOGGER.warn("geosurvey could not delete the oldest set-aside batch"
                        + " {} ({}). Backlog stays over its limit.",
                        oldest, couldNotDelete.toString());
                outcome = Trimmed.KEPT;
            } finally {
                synchronized (owner) {
                    owner.endFileWork();
                }
            }
            return outcome;
        }

        private boolean trimOneUnmoved(Path segment) {
            synchronized (owner) {
                owner.beginFileWork();
            }
            boolean deleted;
            try {
                Unmoved info;
                synchronized (owner) {
                    info = unmoved.get(segment);
                }
                if (info != null) {
                    boolean removedHere = Files.deleteIfExists(segment);
                    synchronized (owner) {
                        settleGone(segment);
                    }
                    if (removedHere) {
                        LOGGER.warn("geosurvey deleted {} set-aside chunks for {} {} ({}): backlog"
                                + " over {} MiB; its move aside had failed. New"
                                + " ground still goes"
                                + " up.",
                                info.held(), owner.server, owner.dimension,
                                segment.getFileName(), cap >> MIB_SHIFT);
                    } else {
                        LOGGER.debug("geosurvey found {} already gone when it tried to delete"
                                + " it.", segment);
                    }
                }
                deleted = true;
            } catch (IOException couldNotDelete) {
                LOGGER.warn("geosurvey could not delete the oldest set-aside batch"
                        + " {} ({}). Backlog stays over its limit.",
                        segment, couldNotDelete.toString());
                deleted = false;
            } finally {
                synchronized (owner) {
                    owner.endFileWork();
                }
            }
            return deleted;
        }

        private void settleGone(Path segment) {
            Unmoved settled = unmoved.remove(segment);
            if (settled != null) {
                quarantinedBytes -= settled.size();
                quarantinedRecords -= settled.held();
            }
        }

        private boolean giveUpOnReading(Path segment, long size, IOException why, long era) {
            boolean overtaken;
            int tries;
            synchronized (owner) {
                overtaken = owner.closed || owner.epoch != era;
                if (overtaken) {
                    tries = 0;
                } else {
                    tries = owner.spent.merge(segment, 1, Integer::sum);
                }
                if (tries >= MAX_ATTEMPTS) {
                    owner.beginFileWork();
                }
            }
            if (!overtaken) {
                if (tries < MAX_ATTEMPTS) {
                    LOGGER.debug("geosurvey could not read a spool segment back", why);
                } else {
                    String name = segment.getFileName().toString();
                    Path aside = owner.directory.resolve(
                            name.substring(0, name.length() - SEALED_SUFFIX.length())
                                    + REFUSED_SUFFIX);
                    long held = size / RECORD_BYTES;
                    try {
                        IOException couldNotMove = moveFailure(segment, aside);
                        boolean gone = couldNotMove instanceof java.nio.file.NoSuchFileException;
                        boolean left = couldNotMove == null || gone;
                        synchronized (owner) {
                            boolean current = !owner.closed && owner.epoch == era;
                            if (current) {
                                owner.unsealed(segment, name);
                                owner.records = Math.max(0L, owner.records - held);
                                owner.bytes = Math.max(0L, owner.bytes - size);
                            } else {
                                if (left) {
                                    owner.letGoAfterDiscard(segment, name, size);
                                }
                            }
                            if (left) {
                                owner.spent.remove(segment);
                            } else {
                                if (current) {
                                    unmoved.put(segment, new Unmoved(size, held, 0));
                                }
                            }
                            if (!gone && (couldNotMove == null || current)) {
                                quarantinedRecords += held;
                                quarantinedBytes += size;
                            }
                        }
                        if (gone) {
                            LOGGER.debug("geosurvey found {} already gone when it tried to set it"
                                    + " aside.", segment);
                        } else if (couldNotMove != null) {
                            LOGGER.warn("geosurvey could not read {} after {} tries ({}) and"
                                    + " could not move it aside either ({}). Stepped"
                                    + " over. Nothing"
                                    + " deleted.", segment, MAX_ATTEMPTS, why.toString(),
                                    couldNotMove.toString());
                        } else {
                            LOGGER.warn("geosurvey could not read {} bytes of surveyed ground for"
                                    + " {} {} after {} tries ({}). Set aside at {}, not"
                                    + " offered again. Nothing deleted; your"
                                    + " map still has it.", size, owner.server,
                                    owner.dimension, MAX_ATTEMPTS, why.toString(), aside);
                            trim(1);
                        }
                    } finally {
                        synchronized (owner) {
                            owner.endFileWork();
                        }
                    }
                }
            }
            return overtaken || tries >= MAX_ATTEMPTS;
        }

        private boolean setAsideOversized(Path segment, long size, long era) {
            String name = segment.getFileName().toString();
            long held = size / RECORD_BYTES;
            boolean claimed;
            synchronized (owner) {
                claimed = !owner.closed && owner.epoch == era;
                if (claimed) {
                    Long rewritten = rewrittenOfKept.remove(name);
                    long already = rewritten == null ? 0L : Math.min(rewritten.longValue(), held);
                    owner.unsealed(segment, name);
                    owner.records = Math.max(0L, owner.records - (held - already));
                    owner.bytes = Math.max(0L, owner.bytes - (size - already * RECORD_BYTES));
                    owner.beginFileWork();
                }
            }
            if (claimed) {
                Path aside = owner.directory.resolve(
                        name.substring(0, name.length() - SEALED_SUFFIX.length())
                                + REFUSED_SUFFIX);
                try {
                    IOException couldNotMove = moveFailure(segment, aside);
                    boolean gone = couldNotMove instanceof java.nio.file.NoSuchFileException;
                    if (couldNotMove != null && !gone) {
                        synchronized (owner) {
                            if (!owner.closed && owner.epoch == era) {
                                owner.spent.put(segment, MAX_ATTEMPTS);
                                unmoved.put(segment, new Unmoved(size, held, 0));
                                quarantinedRecords += held;
                                quarantinedBytes += size;
                            }
                        }
                        LOGGER.warn("geosurvey found {} surveyed chunks in {}, too big for"
                                + " one batch, and could not move it aside ({})."
                                + " Stepped over, not sent in part. Nothing"
                                + " deleted.", held,
                                segment, couldNotMove.toString());
                    } else {
                        synchronized (owner) {
                            owner.spent.remove(segment);
                            if (owner.closed || owner.epoch != era) {
                                owner.letGoAfterDiscard(segment, name, size);
                            }
                            if (!gone) {
                                quarantinedRecords += held;
                                quarantinedBytes += size;
                            }
                        }
                        if (gone) {
                            LOGGER.debug("geosurvey found {} already gone when it tried to set it"
                                    + " aside.", segment);
                        } else {
                            LOGGER.warn("geosurvey set {} surveyed chunks for {} {} aside at {}:"
                                    + " one batch would have dropped the rest."
                                    + " Nothing deleted; your own map still has"
                                    + " it."
                                    + "", held, owner.server, owner.dimension, aside);
                            trim(1);
                        }
                    }
                } finally {
                    synchronized (owner) {
                        owner.endFileWork();
                    }
                }
            }
            return claimed;
        }
    }

    // The sequence from a segment's name, or -1 if this spool did not write it.
    static long sequenceOf(String name, String suffix) {
        SEQUENCE_OF_CALLS.incrementAndGet();
        int end = name.length() - suffix.length();
        return sequenceIn(name, end, stampOf(name, end));
    }

    private static long sequenceIn(String name, int end, int stamp) {
        int digitsEnd = stamp < 0 ? end : stamp;
        return isPlainDigits(name, 0, digitsEnd, MAX_LONG_DIGITS)
                ? Long.parseLong(name, 0, digitsEnd, DECIMAL_RADIX)
                : -1L;
    }

    private static boolean isPlainDigits(String name, int from, int to, int most) {
        boolean plain = to > from && to - from <= most;
        for (int at = from; at < to && plain; at++) {
            char digit = name.charAt(at);
            plain = digit >= '0' && digit <= '9';
        }
        return plain;
    }

    private static int stampOf(String name, int end) {
        NAME_DASH_SCANS.incrementAndGet();
        int stamp = name.indexOf('-');
        return stamp >= end ? -1 : stamp;
    }

    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE_OF_CALLS =
            new java.util.concurrent.atomic.AtomicLong();

    static long sequenceOfCalls() {
        return SEQUENCE_OF_CALLS.get();
    }

    static void resetSequenceOfCalls() {
        SEQUENCE_OF_CALLS.set(0L);
    }

    private static final java.util.concurrent.atomic.AtomicLong SIZINGS_BUILT =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SIZING_ARRAY_COPIES =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong RESPOOLED_RECORDS =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong DIRECTORY_LISTINGS =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SIZE_OF_CALLS =
            new java.util.concurrent.atomic.AtomicLong();

    static long sizingsBuilt() {
        return SIZINGS_BUILT.get();
    }

    static long sizingArrayCopies() {
        return SIZING_ARRAY_COPIES.get();
    }

    static long respooledRecords() {
        return RESPOOLED_RECORDS.get();
    }

    static long directoryListings() {
        return DIRECTORY_LISTINGS.get();
    }

    static long sizeOfCalls() {
        return SIZE_OF_CALLS.get();
    }

    private static final java.util.concurrent.atomic.AtomicLong SEGMENT_READS =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong WRITE_BUFFERS_BUILT =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SERVER_SANITISES =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong DIMENSION_SANITISES =
            new java.util.concurrent.atomic.AtomicLong();

    static long segmentReads() {
        return SEGMENT_READS.get();
    }

    static long writeBuffersBuilt() {
        return WRITE_BUFFERS_BUILT.get();
    }

    static long serverSanitises() {
        return SERVER_SANITISES.get();
    }

    static long dimensionSanitises() {
        return DIMENSION_SANITISES.get();
    }

    private static final java.util.concurrent.atomic.AtomicLong DEFLATERS_BUILT =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong DEFLATER_ENDS =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong NAME_DASH_SCANS =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong DIRECTORY_CREATES =
            new java.util.concurrent.atomic.AtomicLong();

    static long deflatersBuilt() {
        return DEFLATERS_BUILT.get();
    }

    static long deflaterEnds() {
        return DEFLATER_ENDS.get();
    }

    static long nameDashScans() {
        return NAME_DASH_SCANS.get();
    }

    static long directoryCreates() {
        return DIRECTORY_CREATES.get();
    }

    static void resetCostCounters() {
        SIZINGS_BUILT.set(0L);
        SIZING_ARRAY_COPIES.set(0L);
        RESPOOLED_RECORDS.set(0L);
        DIRECTORY_LISTINGS.set(0L);
        SIZE_OF_CALLS.set(0L);
        SEGMENT_READS.set(0L);
        WRITE_BUFFERS_BUILT.set(0L);
        SERVER_SANITISES.set(0L);
        DIMENSION_SANITISES.set(0L);
        DEFLATERS_BUILT.set(0L);
        DEFLATER_ENDS.set(0L);
        NAME_DASH_SCANS.set(0L);
        DIRECTORY_CREATES.set(0L);
    }

    private static int readSegment(InputStream in, byte[] into, int at, int length)
            throws IOException {
        SEGMENT_READS.incrementAndGet();
        return Streams.readNBytes(in, into, at, length);
    }

    private static String sanitiseServer(String server) {
        SERVER_SANITISES.incrementAndGet();
        return MapStorage.sanitise(server);
    }

    private static String sanitiseDimension(String dimension) {
        DIMENSION_SANITISES.incrementAndGet();
        return MapStorage.sanitise(dimension);
    }

    // The record stride from a segment's name; RECORD_BYTES if unstamped, -1 if it will not parse.
    private static int strideOf(String name, int end, int stamp) {
        if (stamp < 0) {
            return RECORD_BYTES;
        }
        return isPlainDigits(name, stamp + 1, end, MAX_INT_DIGITS)
                ? Integer.parseInt(name, stamp + 1, end, DECIMAL_RADIX)
                : -1;
    }

    private static long heldAt(int stride, long size) {
        return size / (stride > 0 ? stride : RECORD_BYTES);
    }

    private static long heldAside(String name, long size) {
        int stemEnd = name.length() - REFUSED_SUFFIX.length();
        return heldAt(strideOf(name, stemEnd, stampOf(name, stemEnd)), size);
    }

    private static long sizeOf(Path file) {
        SIZE_OF_CALLS.incrementAndGet();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException unreadable) {
            size = 0L;
        }
        return size;
    }

    private record SpoolFile(Path file, long size) {
    }

    private List<SpoolFile> listDirectory() throws IOException {
        DIRECTORY_LISTINGS.incrementAndGet();
        List<SpoolFile> found = new ArrayList<>();
        Files.walkFileTree(directory, java.util.EnumSet.noneOf(
                java.nio.file.FileVisitOption.class), 1, new Lister(found));
        return found;
    }

    private static final class Lister extends java.nio.file.SimpleFileVisitor<Path> {
        private final List<SpoolFile> found;

        Lister(List<SpoolFile> found) {
            this.found = found;
        }

        @Override
        public java.nio.file.FileVisitResult visitFile(Path file,
                java.nio.file.attribute.BasicFileAttributes attrs) {
            found.add(new SpoolFile(file, attrs.size()));
            return java.nio.file.FileVisitResult.CONTINUE;
        }

        @Override
        public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc)
                throws IOException {
            throw exc;
        }
    }

    String server() {
        return server;
    }

    String dimension() {
        return dimension;
    }

    long records() {
        return records;
    }

    long bytes() {
        return bytes;
    }

    long refusedRecords() {
        return refusedBacklog.quarantinedRecords;
    }

    // What set-aside ground takes on disk, against MAX_REFUSED_BYTES.
    long refusedBytes() {
        return refusedBacklog.quarantinedBytes;
    }

    boolean hasSealed() {
        return sealedSegments > 0;
    }

    boolean isEmpty() {
        return records == 0 && refusedBacklog.quarantinedRecords == 0 && writing == null
                && writeTurn == null && inFlight == 0;
    }

    void append(ChunkSample sample) throws IOException {
        DataOutputStream out;
        synchronized (this) {
            if (closed) {
                throw new IOException("this spool has been closed");
            }
            takeWriteTurn();
            out = writing;
        }
        boolean due;
        try {
            if (out == null) {
                openSegment();
                out = writing;
            }
            // Counts the same length-prefix-and-record bytes Batch.encode would send.
            recordSink.cursor = PREFIX_BYTES;
            writer.write(recordOut, sample);
            due = flushRecord(out);
        } catch (IOException | RuntimeException | Error failed) {
            releaseWriteTurn();
            throw failed;
        }
        if (endWrite(due)) {
            seal();
        }
    }

    void appendEncoded(byte[] record, int at) throws IOException {
        DataOutputStream out;
        synchronized (this) {
            takeWriteTurn();
            out = writing;
        }
        boolean due;
        try {
            if (out == null) {
                openSegment();
                out = writing;
            }
            System.arraycopy(record, at, measuringRecord, PREFIX_BYTES, RECORD_BYTES);
            due = flushRecord(out);
        } catch (IOException | RuntimeException | Error failed) {
            releaseWriteTurn();
            throw failed;
        }
        if (endWrite(due)) {
            seal();
        }
    }

    private boolean flushRecord(DataOutputStream out) throws IOException {
        measuring.write(measuringRecord, 0, measuringRecord.length);
        out.write(measuringRecord, PREFIX_BYTES, RECORD_BYTES);
        synchronized (this) {
            inSegment++;
            records++;
            bytes += RECORD_BYTES;
        }

        return inSegment >= SEGMENT_SAMPLES || measured.count() >= SEGMENT_PAYLOAD_BYTES;
    }

    void flush() throws IOException {
        DataOutputStream out;
        synchronized (this) {
            out = writing;
            if (out != null) {
                takeWriteTurn();
            }
        }
        if (out != null) {
            try {
                out.flush();
            } catch (IOException | RuntimeException | Error failed) {
                releaseWriteTurn();
                throw failed;
            }
            if (endWrite(false)) {
                seal();
            }
        }
    }

    private void takeWriteTurn() throws IOException {
        if (writeTurn != null) {
            throw new IOException("another thread is writing to this spool");
        }
        writeTurn = Thread.currentThread();
    }

    private synchronized void releaseWriteTurn() {
        writeTurn = null;
        if (endWanted) {
            endWanted = false;
            endMeasuring();
        }
    }

    private synchronized boolean endWrite(boolean due) {
        boolean sealNow = due || sealWanted;
        if (!sealNow) {
            releaseWriteTurn();
        }
        return sealNow;
    }

    // Seals on the interval, but only while nothing sealed is already waiting.
    void sealIfDue(long intervalMillis) throws IOException {
        sealOpenSegment(true, intervalMillis);
    }

    void seal() throws IOException {
        sealOpenSegment(false, 0L);
    }

    private void sealOpenSegment(boolean onlyIfDue, long intervalMillis) throws IOException {
        boolean detach;
        Path toClose;
        String sealedName;
        long sealedSize;
        DataOutputStream out;
        FileChannel open;
        long era;
        synchronized (this) {
            Thread turn = writeTurn;
            boolean othersTurn = turn != null && turn != Thread.currentThread();
            if (othersTurn) {
                if (!onlyIfDue) {
                    sealWanted = true;
                }
            } else {
                if (turn != null) {
                    releaseWriteTurn();
                }
            }
            detach = !othersTurn && writing != null
                    && (!onlyIfDue || dueToSeal(intervalMillis));
            toClose = writingFile;
            sealedName = inSegment == 0 ? null : pendingSealedName;
            sealedSize = (long) inSegment * RECORD_BYTES;
            out = writing;
            open = channel;
            era = epoch;
            if (detach) {
                stopMeasuring();
                writing = null;
                channel = null;
                writingFile = null;
                inSegment = 0;
                sealWanted = false;
                beginFileWork();
            }
        }
        if (detach) {
            try {
                if (sealedName == null) {
                    closeDetached(out, open, false);
                    if (!Files.deleteIfExists(toClose)) {
                        LOGGER.debug("geosurvey found {} already gone when the empty segment's"
                                + " seal tried to delete it.", toClose);
                    }
                } else {
                    Path sealed = directory.resolve(sealedName);
                    closeDetached(out, open, true);
                    move(toClose, sealed);
                    synchronized (this) {
                        if (!closed && epoch == era) {
                            sealedSegments++;
                            sealedFiles.put(sealedName, new SpoolFile(sealed, sealedSize));
                        }
                    }
                }
            } finally {
                synchronized (this) {
                    endFileWork();
                }
            }
        }
    }

    private boolean dueToSeal(long intervalMillis) {
        return inSegment != 0 && sealedSegments <= 0
                && (System.nanoTime() - segmentOpenedAtNanos) >= intervalMillis * NANOS_PER_MILLI;
    }

    private static void appendSegmentStem(StringBuilder into, long sequence, int stride) {
        int start = into.length();
        into.append(sequence);
        int padded = SEQUENCE_DIGITS - (into.length() - start);
        for (int i = 0; i < padded; i++) {
            into.insert(start, '0');
        }
        into.append('-').append(stride);
    }

    static String segmentName(long sequence, int stride) {
        StringBuilder stem = new StringBuilder(NAME_CAPACITY);
        appendSegmentStem(stem, sequence, stride);
        stem.append(WRITING_SUFFIX);
        return stem.toString();
    }

    private String buildOpenSegmentName(long sequence, int stride) {
        nameBuilder.setLength(0);
        appendSegmentStem(nameBuilder, sequence, stride);
        int stemEnd = nameBuilder.length();
        nameBuilder.append(SEALED_SUFFIX);
        pendingSealedName = nameBuilder.toString();
        nameBuilder.setLength(stemEnd);
        nameBuilder.append(WRITING_SUFFIX);
        return nameBuilder.toString();
    }

    private void openSegment() throws IOException {

        // Name format: a 12-digit sequence, then the stride; the width keeps names in sequence order.
        synchronized (this) {
            writingFile = directory.resolve(buildOpenSegmentName(nextSequence++, RECORD_BYTES));
            inSegment = 0;
        }
        try {
            channel = FileChannel.open(writingFile, NEW_SEGMENT_OPEN_OPTIONS);
        } catch (java.nio.file.NoSuchFileException folderGone) {
            Files.createDirectories(directory);
            DIRECTORY_CREATES.incrementAndGet();
            channel = FileChannel.open(writingFile, NEW_SEGMENT_OPEN_OPTIONS);
        }

        // Set up before writing, so append() never sees one without the other.
        // Buffered the same way Batch.encode is, so the byte count matches it exactly.
        if (sizing == null) {
            measured = new Counting(GZIP_HEADER_BYTES);
            sizing = new Sizing(measured);
            measuring = new DataOutputStream(sizing);
            measuringRecord = new byte[PREFIX_BYTES + RECORD_BYTES];
            writeRecordLength(measuringRecord);
            recordSink = new RecordSink(measuringRecord);
            recordOut = new DataOutputStream(recordSink);
        } else {
            sizing.beginSegment(GZIP_HEADER_BYTES);
        }
        SegmentStream stream;
        synchronized (this) {
            stream = spareStream;
            spareStream = null;
        }
        if (stream == null) {
            stream = new SegmentStream(
                    new SegmentSink(channel, new byte[WRITE_BUFFER_BYTES], this));
            WRITE_BUFFERS_BUILT.incrementAndGet();
        } else {
            stream.openOn(channel);
        }
        writing = stream;
        segmentOpenedAtNanos = System.nanoTime();
    }

    private void closeSegment() throws IOException {
        closeSegment(true);
    }

    private void closeSegment(boolean durable) throws IOException {

        // Must run first; this is the only part holding native deflater state.
        stopMeasuring();
        DataOutputStream out = writing;
        FileChannel open = channel;
        writing = null;
        channel = null;
        if (out != null) {
            closeDetached(out, open, durable);
        }
    }

    private static void closeDetached(DataOutputStream out, FileChannel open, boolean durable)
            throws IOException {
        try {
            out.flush();
            if (durable && open != null) {
                open.force(false);
            }
        } catch (IOException failed) {
            if (open != null) {
                try {
                    open.close();
                } catch (IOException closing) {
                    failed.addSuppressed(closing);
                }
            }
            throw failed;
        }
        out.close();
    }

    private synchronized void keepSpare(SegmentStream stream) {
        if (spareStream == null) {
            spareStream = stream;
        }
    }

    // Resets the byte count for the next segment; does not end the deflater.
    private void stopMeasuring() {
        if (sizing != null) {
            sizing.beginSegment(GZIP_HEADER_BYTES);
        }
    }

    private void endMeasuring() {
        measuring = null;
        measured = null;
        if (sizing != null) {
            sizing.close();
            sizing = null;
        }
    }

    // Writes nowhere; counts bytes only.
    private static final class Counting extends java.io.OutputStream {

        private long count;

        Counting(long already) {
            count = already;
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] from, int at, int length) {
            count += length;
        }

        void reset(long already) {
            count = already;
        }
    }

    private static void writeRecordLength(byte[] into) {
        for (int at = 0; at < PREFIX_BYTES; at++) {
            into[at] = (byte) (MapCodec.CHUNK_BYTES >>> (Byte.SIZE * (PREFIX_BYTES - 1 - at)));
        }
    }

    private static final class RecordSink extends java.io.OutputStream {

        private final byte[] into;

        private int cursor;

        RecordSink(byte[] into) {
            this.into = into;
        }

        @Override
        public void write(int b) {
            into[cursor++] = (byte) b;
        }

        @Override
        public void write(byte[] from, int at, int length) {
            System.arraycopy(from, at, into, cursor, length);
            cursor += length;
        }
    }

    private static final class SegmentSink extends java.io.OutputStream {

        private FileChannel target;

        private final byte[] buffer;

        private final java.nio.ByteBuffer view;

        private final ShareSpool owner;

        private int filled;

        private boolean failed;

        SegmentSink(FileChannel target, byte[] buffer, ShareSpool owner) {
            this.target = target;
            this.buffer = buffer;
            this.view = java.nio.ByteBuffer.wrap(buffer);
            this.owner = owner;
        }

        private void openOn(FileChannel target) {
            this.target = target;
            filled = 0;
            failed = false;
        }

        @Override
        public void write(int b) throws IOException {
            if (filled == buffer.length) {
                drain();
            }
            buffer[filled++] = (byte) b;
        }

        @Override
        public void write(byte[] from, int at, int length) throws IOException {
            while (length > 0) {
                if (filled == buffer.length) {
                    drain();
                }
                int take = Math.min(length, buffer.length - filled);
                System.arraycopy(from, at, buffer, filled, take);
                filled += take;
                at += take;
                length -= take;
            }
        }

        @Override
        public void flush() throws IOException {
            drain();
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            try {
                drain();
            } catch (IOException draining) {
                first = draining;
            }
            try {
                target.close();
            } catch (IOException closing) {
                if (first == null) {
                    first = closing;
                } else {
                    first.addSuppressed(closing);
                }
            }
            if (first != null) {
                throw first;
            }
        }

        private void drain() throws IOException {
            if (failed) {
                throw new IOException("geosurvey could not finish writing a segment;"
                        + " bytes already sent will not replay.");
            }
            if (filled == 0) {
                return;
            }
            view.clear();
            view.limit(filled);
            try {
                while (view.hasRemaining()) {
                    target.write(view);
                }
            } catch (IOException couldNotWrite) {
                failed = true;
                throw couldNotWrite;
            }
            filled = 0;
        }
    }

    private static final class SegmentStream extends DataOutputStream {

        private final SegmentSink sink;

        private SegmentStream(SegmentSink sink) {
            super(sink);
            this.sink = sink;
        }

        private void openOn(FileChannel target) {
            written = 0;
            sink.openOn(target);
        }

        @Override
        public void close() throws IOException {
            IOException notClosed = null;
            try {
                sink.close();
            } catch (IOException closing) {
                notClosed = closing;
            }
            sink.owner.keepSpare(this);
            if (notClosed != null) {
                throw notClosed;
            }
        }
    }

    private static final class Sizing extends java.io.OutputStream {

        private final byte[] pending = new byte[SIZING_BUFFER_BYTES];

        private final Counting counter;

        private final java.util.zip.Deflater deflater = newDeflater();

        private static java.util.zip.Deflater newDeflater() {
            DEFLATERS_BUILT.incrementAndGet();
            return new java.util.zip.Deflater(
                    java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
        }

        private final java.util.zip.DeflaterOutputStream deflating;

        private int held;

        Sizing(Counting counter) {
            SIZINGS_BUILT.incrementAndGet();
            this.counter = counter;
            deflating = new java.util.zip.DeflaterOutputStream(counter, deflater);
        }

        void beginSegment(long headerBytes) {
            deflater.reset();
            held = 0;
            counter.reset(headerBytes);
        }

        @Override
        public void write(int b) throws IOException {
            if (held >= pending.length) {
                drain();
            }
            pending[held++] = (byte) b;
        }

        @Override
        public void write(byte[] from, int at, int length) throws IOException {
            if (length >= pending.length) {
                drain();
                deflating.write(from, at, length);
            } else {
                if (length > pending.length - held) {
                    drain();
                }
                SIZING_ARRAY_COPIES.incrementAndGet();
                System.arraycopy(from, at, pending, held, length);
                held += length;
            }
        }

        @Override
        public void close() {
            DEFLATER_ENDS.incrementAndGet();
            deflater.end();
        }

        private void drain() throws IOException {
            if (held > 0) {
                deflating.write(pending, 0, held);
                held = 0;
            }
        }
    }

    interface Decoder {
        ChunkSample read(byte[] record) throws IOException;
    }

    private java.util.function.Supplier<Decoder> decoderFactory =
            () -> new MapCodec.ChunkReader()::read;

    private DecodeScratch spareScratch;

    private final java.util.ArrayDeque<Loaded> loadedPool = new java.util.ArrayDeque<>();

    synchronized void decoderFactory(java.util.function.Supplier<Decoder> factory) {
        decoderFactory = factory;
        if (spareScratch != null) {
            spareScratch.decoder = null;
        }
    }

    private DecodeScratch spareOrNewScratch() {
        DecodeScratch spare;
        synchronized (this) {
            spare = spareScratch;
            spareScratch = null;
        }
        return spare != null ? spare : new DecodeScratch();
    }

    private synchronized void keepSpareScratch(DecodeScratch scratch) {
        if (spareScratch == null) {
            spareScratch = scratch;
        }
    }

    private synchronized Loaded pooledOrNewLoaded() {
        Loaded spare = loadedPool.poll();
        if (spare == null) {
            return new Loaded();
        }
        spare.pooled = false;
        return spare;
    }

    private synchronized void keepSpareLoaded(Loaded loaded) {
        if (!loaded.pooled) {
            loaded.pooled = true;
            loadedPool.push(loaded);
        }
    }

    private static final class DecodeScratch {
        private Decoder decoder;
        private byte[] segmentBuffer;
        private byte[] record;
        private Loaded decodedCandidate;
        private long decodedTorn;
        private boolean decodedOversized;
        private IOException decodedUnreadable;
        private Loaded outcomeLoaded;
    }

    // Null when nothing is sealed; the file stays until done() removes it.
    // Holds the monitor only to reserve and to finish; the read and decode run without it.
    Loaded take() throws IOException {

        // A local: two takes can run at once without the monitor held.
        DecodeScratch scratch = spareOrNewScratch();
        try {
            return takeFrom(scratch);
        } finally {
            keepSpareScratch(scratch);
        }
    }

    private Loaded takeFrom(DecodeScratch scratch) throws IOException {
        scratch.outcomeLoaded = null;
        boolean retry = true;
        while (retry) {
            long era;
            boolean shut;
            synchronized (this) {

                // Closed: the directory may now belong to a different, newer spool.
                shut = closed;
                era = epoch;
            }
            Path oldest = shut ? null : reserveOldest();
            if (oldest == null) {
                retry = false;
            } else {
                retry = takeReservedSegment(oldest, era, scratch);
            }
        }
        return scratch.outcomeLoaded;
    }

    private boolean takeReservedSegment(Path oldest, long era, DecodeScratch scratch)
            throws IOException {
        scratch.outcomeLoaded = null;
        boolean handedOff = false;
        boolean retry;
        try {
            long size = sizeOf(oldest);
            if (size == 0L && Files.notExists(oldest)) {
                synchronized (this) {
                    SpoolFile sealed = sealedFiles.get(oldest.getFileName().toString());
                    size = sealed == null ? 0L : sealed.size();
                }
            }
            if (size / RECORD_BYTES > SEGMENT_SAMPLES) {
                retry = setAsideOversized(oldest, size, era);
            } else {

                // Unlocked from here to the finally; this block touches no field, only locals.
                decodeSegment(oldest, size, scratch);
                boolean overtaken;
                synchronized (this) {

                    // Overtaken meanwhile: say nothing; never call giveUpOnReading.
                    overtaken = closed || epoch != era;
                }
                if (overtaken) {
                    retry = false;
                } else {
                    retry = decideOutcome(oldest, size, era, scratch);
                }
            }

            // Stamped for done()/failed() to reuse later, not just compared here.
            handedOff = scratch.outcomeLoaded != null;
        } finally {

            if (!handedOff) {
                synchronized (this) {
                    reading.remove(oldest);
                }
            }
            if (scratch.decodedCandidate != null) {
                keepSpareLoaded(scratch.decodedCandidate);
                scratch.decodedCandidate = null;
            }
        }
        return retry;
    }

    private void decodeSegment(Path oldest, long size, DecodeScratch scratch) {
        if (scratch.decoder == null) {
            java.util.function.Supplier<Decoder> factory;
            synchronized (this) {
                factory = decoderFactory;
            }
            scratch.decoder = factory.get();
        }
        Loaded candidate = pooledOrNewLoaded();
        List<ChunkSample> samples = candidate.samples();
        samples.clear();
        if (samples instanceof ArrayList<?> sized) {
            sized.ensureCapacity((int) Math.min(SEGMENT_SAMPLES, size / RECORD_BYTES));
        }
        long torn = 0;
        boolean oversized = false;
        IOException unreadable = null;
        try (InputStream in = Files.newInputStream(oldest, SEGMENT_READ_OPTIONS)) {
            long room = size > 0
                    ? size + RECORD_BYTES
                    : (long) (SEGMENT_SAMPLES + 1) * RECORD_BYTES;
            int want = (int) Math.min(room,
                    (long) (SEGMENT_SAMPLES + 1) * RECORD_BYTES);
            if (scratch.segmentBuffer == null || scratch.segmentBuffer.length < want) {
                scratch.segmentBuffer = new byte[want];
            }
            if (scratch.record == null) {
                scratch.record = new byte[RECORD_BYTES];
            }
            int got = readSegment(in, scratch.segmentBuffer, 0, want);
            long whole = got / RECORD_BYTES;
            int copies = whole > SEGMENT_SAMPLES ? SEGMENT_SAMPLES : (int) whole;
            for (int at = 0; at < copies; at++) {
                System.arraycopy(scratch.segmentBuffer, at * RECORD_BYTES,
                        scratch.record, 0, RECORD_BYTES);
                samples.add(scratch.decoder.read(scratch.record));
            }
            if (whole > SEGMENT_SAMPLES) {
                oversized = true;
            } else {
                torn = got - (long) copies * RECORD_BYTES;
            }
        } catch (IOException couldNotRead) {
            unreadable = couldNotRead;
        }
        scratch.decodedCandidate = candidate;
        scratch.decodedTorn = torn;
        scratch.decodedOversized = oversized;
        scratch.decodedUnreadable = unreadable;
    }

    private boolean decideOutcome(Path oldest, long size, long era, DecodeScratch scratch)
            throws IOException {
        boolean retry;
        if (scratch.decodedUnreadable != null) {

            IOException unreadable = scratch.decodedUnreadable;
            boolean endedByShutdown = false;
            for (Throwable cause = unreadable; cause != null;
                    cause = cause.getCause()) {
                if (cause instanceof java.nio.channels.ClosedByInterruptException) {
                    endedByShutdown = true;
                    break;
                }
            }
            if (endedByShutdown) {
                Thread.currentThread().interrupt();
                retry = false;
            } else {
                if (!giveUpOnReading(oldest, size, unreadable, era)) {
                    throw unreadable;
                }
                retry = true;
            }
        } else if (scratch.decodedOversized) {
            retry = setAsideOversized(oldest, size, era);
        } else if (scratch.decodedCandidate.samples().isEmpty()) {
            LOGGER.warn("geosurvey is letting go of {}: no whole"
                    + " surveyed chunk, only {} bytes.",
                    oldest, scratch.decodedTorn);
            drop(oldest, size, 0, era);
            retry = true;
        } else {
            Loaded candidate = scratch.decodedCandidate;
            if (scratch.decodedTorn > 0) {
                LOGGER.warn("geosurvey read {} whole surveyed chunks out of"
                        + " {}: {} bytes were an unfinished chunk. Those"
                        + " bytes are gone; the chunks"
                        + " are not.",
                        candidate.samples().size(), oldest, scratch.decodedTorn);
            }
            candidate.reset(oldest, candidate.samples(), size, scratch.decodedTorn, era);
            scratch.outcomeLoaded = candidate;
            scratch.decodedCandidate = null;
            retry = false;
        }
        return retry;
    }

    private boolean sealedDirectoryScannedEmpty;

    private Path reserveOldest() throws IOException {
        Path chosen;
        boolean scan;
        long seen;
        synchronized (this) {
            chosen = closed ? null : chooseAndReserve();
            scan = chosen == null && !closed && !sealedDirectoryScannedEmpty && inFlight == 0;
            seen = changes;
        }
        if (scan) {
            boolean whole = scanSealed(seen);
            synchronized (this) {
                chosen = closed ? null : chooseAndReserve();
                sealedDirectoryScannedEmpty = chosen == null && whole;
            }
        }
        return chosen;
    }

    private Path chooseAndReserve() {
        Path chosen = choose();
        if (chosen != null) {
            sealedDirectoryScannedEmpty = false;
            reading.add(chosen);
        }
        return chosen;
    }

    private boolean scanSealed(long seen) throws IOException {
        List<Path> aside = null;
        boolean whole = true;
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(directory, "*" + SEALED_SUFFIX)) {
            java.util.Iterator<Path> each = entries.iterator();
            while (each.hasNext() && whole) {
                Path entry = each.next();
                synchronized (this) {
                    whole = changes == seen && inFlight == 0;
                    if (whole) {
                        aside = adoptSealed(entry, aside);
                    }
                }
            }
        }
        if (aside != null) {
            for (Path entry : aside) {
                setAsideFound(entry);
            }
        }
        return whole;
    }

    private List<Path> adoptSealed(Path entry, List<Path> aside) {
        List<Path> found = aside;
        Integer tries = spent.get(entry);
        if (tries == null || tries < MAX_ATTEMPTS) {
            String name = entry.getFileName().toString();
            int stemEnd = name.length() - SEALED_SUFFIX.length();
            int stamp = stampOf(name, stemEnd);
            int stride = strideOf(name, stemEnd, stamp);
            if (stride != RECORD_BYTES) {
                if (found == null) {
                    found = new ArrayList<>();
                }
                found.add(entry);
            } else if (!sealedFiles.containsKey(name)) {
                sealedFiles.put(name, new SpoolFile(entry, 0L));
            }
        }
        return found;
    }

    private void setAsideFound(Path entry) {
        String name = entry.getFileName().toString();
        int stemEnd = name.length() - SEALED_SUFFIX.length();
        int stamp = stampOf(name, stemEnd);
        setAside(entry, name, sizeOf(entry), strideOf(name, stemEnd, stamp));
    }

    private int lastChooseVisited;

    private Path choose() {
        lastChooseVisited = 0;
        Path chosen = null;
        String name = sealedFiles.isEmpty() ? null : sealedFiles.firstKey();
        while (name != null) {
            lastChooseVisited++;
            SpoolFile file = sealedFiles.get(name);
            Path entry = file.file();

            // Stepped over, not re-offered.
            Integer tries = spent.get(entry);
            boolean steppedOver = tries != null && tries >= MAX_ATTEMPTS;

            // Already being read: skipped, not waited for, so this take tries the next one.
            if (steppedOver || reading.contains(entry)) {
                name = sealedFiles.higherKey(name);
            } else {
                chosen = entry;
                name = null;
            }
        }
        return chosen;
    }

    int lastChooseVisited() {
        return lastChooseVisited;
    }

    // Returns true once set aside (a repeatable refusal); false and the caller retries otherwise.
    // Leaves the live total and credits the refused total, whether or not the move succeeds.
    private boolean giveUpOnReading(Path segment, long size, IOException why, long era) {
        return refusedBacklog.giveUpOnReading(segment, size, why, era);
    }

    private boolean setAsideOversized(Path segment, long size, long era) {
        return refusedBacklog.setAsideOversized(segment, size, era);
    }

    // The collector took it.
    void done(Loaded loaded) {
        try {
            boolean current;
            synchronized (this) {
                current = !closed && loaded.era() == epoch;
                if (current) {
                    attempts.remove(loaded.file());
                } else {
                    reading.remove(loaded.file());
                }
            }
            if (current) {
                drop(loaded.file(), loaded.fileBytes(), loaded.samples().size(), loaded.era());
            }
        } finally {
            keepSpareLoaded(loaded);
        }
    }

    // The collector would not take it. Returns true once set aside, for a refusal it will repeat.
    boolean failed(Loaded loaded, boolean permanent) {
        boolean setAside;
        try {
            Path file = loaded.file();
            boolean due;
            synchronized (this) {
                if (permanent && !closed && loaded.era() == epoch) {
                    due = attempts.merge(file, 1, Integer::sum) >= MAX_ATTEMPTS;
                } else {
                    due = false;
                }
                if (due) {
                    reading.add(file);
                    beginFileWork();
                } else {
                    reading.remove(file);
                }
            }
            if (due) {
                String name = file.getFileName().toString();
                int held = loaded.samples().size();
                Path aside = directory.resolve(
                        name.substring(0, name.length() - SEALED_SUFFIX.length())
                                + REFUSED_SUFFIX);
                try {
                    IOException couldNotMove = moveFailure(file, aside);
                    boolean gone = couldNotMove instanceof java.nio.file.NoSuchFileException;
                    synchronized (this) {
                        boolean current = !closed && loaded.era() == epoch;
                        if (couldNotMove == null || gone) {
                            attempts.remove(file);

                            long moved = loaded.fileBytes();
                            if (current) {
                                unsealed(file, name);
                                records = Math.max(0L, records - held);
                                // Bytes move with the file: off the live total, onto the refused one.
                                bytes = Math.max(0L, bytes - moved);
                            } else {
                                letGoAfterDiscard(file, name, moved);
                            }
                            if (!gone) {
                                refusedBacklog.quarantinedRecords += held;
                                refusedBacklog.quarantinedBytes += moved;
                            }
                        } else {
                            if (current) {
                                spent.put(file, MAX_ATTEMPTS);
                                refusedBacklog.unmoved.put(file,
                                        new Unmoved(loaded.fileBytes(), held, 0));
                                unsealed(file, name);
                                records = Math.max(0L, records - held);
                                bytes = Math.max(0L, bytes - loaded.fileBytes());
                                refusedBacklog.quarantinedRecords += held;
                                refusedBacklog.quarantinedBytes += loaded.fileBytes();
                            }
                        }
                    }
                    if (gone) {
                        LOGGER.debug("geosurvey found {} already gone when it tried to set it"
                                + " aside.", file);
                    } else if (couldNotMove == null) {
                        LOGGER.warn("geosurvey has set aside {} surveyed chunks for {} {}: the"
                                + " collector refused it {} times. Now at {}, not"
                                + " deleted, not sent again on its"
                                + " own.",
                                held, server, dimension, MAX_ATTEMPTS, aside);
                        trimRefused();
                    } else {
                        LOGGER.warn("geosurvey could not set aside {} ({})."
                                + " Stepped over.", file, couldNotMove.toString());
                    }
                    setAside = couldNotMove == null;
                } finally {
                    synchronized (this) {
                        endFileWork();
                        reading.remove(file);
                    }
                }
            } else {
                setAside = false;
            }
        } finally {
            keepSpareLoaded(loaded);
        }
        return setAside;
    }

    // Releases a finished segment: posted, or holding no whole record.
    private void drop(Path file, long fileBytes, int held, long era) {
        boolean claimed;
        synchronized (this) {
            claimed = !closed && era == epoch;
            if (claimed) {
                reading.add(file);
                beginFileWork();
            } else {
                reading.remove(file);
            }
        }
        if (claimed) {
            try {
                IOException stillThere = deleteFailure(file);
                synchronized (this) {
                    boolean current = !closed && era == epoch;
                    if (stillThere == null) {
                        spent.remove(file);
                    } else {
                        if (current) {
                            spent.put(file, MAX_ATTEMPTS);
                        }

                    }
                    if (current) {
                        unsealed(file);
                        records = Math.max(0L, records - held);
                        bytes = Math.max(0L, bytes - fileBytes);
                    } else {
                        if (stillThere == null) {
                            letGoAfterDiscard(file, file.getFileName().toString(), fileBytes);
                        }
                    }
                }
                if (stillThere != null) {
                    LOGGER.warn("geosurvey could not delete {} after it was contributed ({})."
                            + " Stepped over from here on."
                            + " Delete the file by"
                            + " hand.", file,
                            stillThere.toString());
                }
            } finally {
                synchronized (this) {
                    endFileWork();
                    reading.remove(file);
                }
            }
        }
    }

    // One sealed segment leaves the count; clamped so it cannot go under zero.
    // Two calls for the same file are possible; nothing ever walks the count back up.
    private void unsealed(Path segment) {
        unsealed(segment, segment.getFileName().toString());
    }

    private void unsealed(Path segment, String name) {
        int left = sealedSegments - 1;
        sealedSegments = left < 0 ? 0 : left;
        sealedFiles.remove(name);
    }

    private void letGoAfterDiscard(Path file, String name, long fileBytes) {
        if (sealedFiles.containsKey(name)) {
            takeOffCount(file, name, fileBytes);
        } else {
            goneMidDiscard.add(new GoneSegment(name, fileBytes));
        }
    }

    private void takeOffCount(Path file, String name, long fileBytes) {
        unsealed(file, name);
        records = Math.max(0L, records - fileBytes / RECORD_BYTES);
        bytes = Math.max(0L, bytes - fileBytes);
    }

    // Deletes this world's backlog. Returns how much WENT, not how much was held.
    long discard() {
        long held;
        long limit;
        long kept;
        Path spare;
        DataOutputStream out;
        FileChannel open;
        int sealedBefore;
        long bytesBefore;
        java.util.TreeMap<String, SpoolFile> before;
        synchronized (this) {
            held = records;

            // Bumps epoch first, before any file goes, so a take() or post already in flight is abandoned.
            epoch = EPOCHS.incrementAndGet();
            beginFileWork();
            limit = nextSequence;
            spare = writeTurn == null ? null : writingFile;
            kept = spare == null ? 0L : inSegment;
            out = spare == null ? writing : null;
            open = spare == null ? channel : null;
            if (out != null) {
                stopMeasuring();
            }
            if (spare == null) {
                writing = null;
                channel = null;
                writingFile = null;
                inSegment = 0;
            }
            attempts.clear();

            // Cleared beside attempts: a refilled spool owes nothing to a segment that is gone.
            spent.keySet().retainAll(refusedBacklog.unmoved.keySet());
            sealedBefore = sealedSegments;
            bytesBefore = bytes;
            before = sealedFiles;
            sealedFiles = new java.util.TreeMap<>();
        }
        long went;
        try {
            if (out != null) {
                letGoOfSegment(out, open);
            }
            java.util.TreeMap<String, SpoolFile> leftSealed = new java.util.TreeMap<>();
            long leftBytes = removeAll(SEALED_SUFFIX, limit, spare, leftSealed);
            long leftParts = removeAll(WRITING_SUFFIX, limit, spare, leftSealed);

            // Recounted from the directory, not zeroed or computed, so a failed delete still counts right.
            java.util.TreeMap<String, SpoolFile> stillSealed = new java.util.TreeMap<>();
            long walked = recount(limit, spare, stillSealed);
            boolean fromDeletes = walked < 0L && leftBytes >= 0L && leftParts >= 0L;
            long live;
            java.util.TreeMap<String, SpoolFile> counted;
            if (fromDeletes) {
                live = leftBytes + leftParts;
                counted = leftSealed;
            } else {
                live = walked;
                counted = stillSealed;
            }
            synchronized (this) {
                if (live < 0L) {
                    sealedFiles.putAll(before);
                    takeOffHandedOver();
                } else {
                    live -= goneFromRecount(counted);
                    sealedSegments = Math.max(0, sealedSegments - sealedBefore)
                            + counted.size();
                    records = Math.max(0L, records - (held - kept)) + live / RECORD_BYTES;
                    bytes = Math.max(0L, bytes - (bytesBefore - kept * RECORD_BYTES)) + live;
                    refusedBacklog.rewrittenOfKept.clear();
                    sealedFiles.putAll(counted);
                }
            }

            went = live < 0L ? 0L : held - kept - live / RECORD_BYTES;
        } finally {
            synchronized (this) {
                endFileWork();
            }
        }
        return went < 0 ? 0 : went;
    }

    private static void letGoOfSegment(DataOutputStream out, FileChannel open) {
        try {
            closeDetached(out, open, false);
        } catch (IOException notClosed) {
            LOGGER.debug("geosurvey could not close a spool segment it was letting go of",
                    notClosed);
        }
    }

    // Recounts only the live totals; refused ground has its own totals, untouched here.
    // If the directory cannot be walked, every total is left as it was.
    private long recount(long limit, Path spare, java.util.TreeMap<String, SpoolFile> stillSealed) {
        long spared = sparedSequence(spare, limit);
        long live = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if ((!SegmentSuffix.SEALED.matches(name)
                        && !SegmentSuffix.WRITING.matches(name))
                        || refusedInPlace(entry)
                        || !Files.isRegularFile(entry)) {
                    continue;
                }
                boolean sealed = SegmentSuffix.SEALED.matches(name);
                int stemEnd = name.length()
                        - (sealed ? SEALED_SUFFIX.length() : WRITING_SUFFIX.length());
                if (!keptBy(sequenceIn(name, stemEnd, stampOf(name, stemEnd)), limit, spared)) {
                    long size = sizeOf(entry);
                    if (size > 0L || !Files.notExists(entry)) {
                        if (sealed) {
                            stillSealed.put(name, new SpoolFile(entry, size));
                        }
                        live += size;
                    }
                }
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException unreadable) {
            LOGGER.warn("geosurvey could not read the spool at {} back after letting"
                    + " go of it ({}). Totals are unchanged."
                    + "", directory, unreadable.toString());
            live = -1L;
        }
        return live;
    }

    // Deletes every file with that suffix, one at a time; one failure does not stop the rest.
    private long removeAll(String suffix, long limit, Path spare,
            java.util.TreeMap<String, SpoolFile> left) {
        long spared = sparedSequence(spare, limit);
        long leftBytes = 0L;
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(directory, "*" + suffix)) {
            for (Path entry : entries) {
                if (keptBy(sequenceOf(entry.getFileName().toString(), suffix), limit, spared)
                        || refusedInPlace(entry)) {
                    continue;
                }
                try {
                    if (!Files.deleteIfExists(entry)) {
                        LOGGER.debug("geosurvey found {} already gone while letting go"
                                + " of the backlog for {} {}.", entry, server, dimension);
                    }
                } catch (IOException stillThere) {
                    LOGGER.warn("geosurvey could not delete {} while letting go of"
                            + " the backlog for {} {} ({}). Still counted, queued"
                            + " for next launch. Delete it by"
                            + " hand.",
                            entry, server, dimension, stillThere.toString());
                    leftBytes += leftBehind(entry, left);
                }
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException unreadable) {
            LOGGER.warn("geosurvey could not read the spool directory {} while"
                    + " letting go of its backlog ({}). Nothing"
                    + " deleted.", directory, unreadable.toString());
            leftBytes = -1L;
        }
        return leftBytes;
    }

    private static long leftBehind(Path entry, java.util.TreeMap<String, SpoolFile> left) {
        boolean regular = Files.isRegularFile(entry);
        long size = regular ? sizeOf(entry) : 0L;
        boolean there = regular && (size > 0L || !Files.notExists(entry));
        String name = entry.getFileName().toString();
        if (there && SegmentSuffix.SEALED.matches(name)) {
            left.put(name, new SpoolFile(entry, size));
        }
        return there ? size : 0L;
    }

    private static long sparedSequence(Path spare, long limit) {
        return spare == null ? limit : sequenceOf(spare.getFileName().toString(), WRITING_SUFFIX);
    }

    private static boolean keptBy(long sequence, long limit, long spared) {
        return sequence >= limit || sequence == spared;
    }

    private synchronized boolean refusedInPlace(Path entry) {
        return refusedBacklog.unmoved.containsKey(entry);
    }

    private long goneFromRecount(java.util.TreeMap<String, SpoolFile> stillSealed) {
        long gone = 0L;
        for (GoneSegment each : goneMidDiscard) {
            if (stillSealed.remove(each.name()) != null) {
                gone += each.fileBytes();
            }
        }
        goneMidDiscard.clear();
        return gone;
    }

    private void takeOffHandedOver() {
        for (GoneSegment each : goneMidDiscard) {
            SpoolFile counted = sealedFiles.get(each.name());
            if (counted != null) {
                takeOffCount(counted.file(), each.name(), each.fileBytes());
            }
        }
        goneMidDiscard.clear();
    }

    // Seals and releases handles; the files stay, and appending can resume later.
    // Only removeIfEmpty ends the spool for good.
    @Override
    public void close() {
        sealOnTheWayOut();
        synchronized (this) {
            if (writing == null && writeTurn == null) {
                endMeasuring();
            }
        }
    }

    private void sealOnTheWayOut() {
        try {
            seal();
        } catch (IOException notSealed) {
            LOGGER.debug("geosurvey could not seal a spool segment on the way out",
                    notSealed);
        }
    }

    // Ends this spool's Deflater for good; for retiring the object, not necessarily its directory.
    // Different from close(): close() must not end it, since reopen() reuses it.
    synchronized void endForGood() {
        Thread turn = writeTurn;
        if (turn == null || turn == Thread.currentThread()) {
            endMeasuring();
        } else {
            endWanted = true;
        }
    }

    boolean reopen() {
        sealOnTheWayOut();
        Restock answer = restock();
        for (int attempt = 1; attempt < RESTOCK_ATTEMPTS && answer == Restock.CONTENDED;
                attempt++) {
            java.util.concurrent.locks.LockSupport.parkNanos(RESTOCK_PAUSE_NANOS);
            answer = restock();
        }
        return answer == Restock.RECOUNTED;
    }

    private Restock restock() {
        boolean writerBusy;
        boolean free;
        long seen;
        long limit;
        long era;
        synchronized (this) {
            writerBusy = writing != null || writeTurn != null;
            free = !writerBusy && inFlight <= 0;
            if (free) {
                writingFile = null;
                inSegment = 0;
                beginFileWork();
            }
            seen = changes;
            limit = nextSequence;
            era = epoch;
        }
        Restock answer;
        if (free) {
            try {
                answer = restockFrom(seen, limit, era);
            } finally {
                synchronized (this) {
                    endFileWork();
                }
            }
        } else {
            answer = writerBusy ? Restock.REFUSED : Restock.CONTENDED;
        }
        return answer;
    }

    private Restock restockFrom(long seen, long limit, long era) {
        List<SpoolFile> listing;
        try {
            listing = listDirectory();
        } catch (IOException unreadable) {
            LOGGER.warn("geosurvey could not read the spool at {} back after a write"
                    + " failed ({}). Totals are unchanged."
                    + "", directory, unreadable.toString());
            listing = null;
        }
        Restock answer;
        if (listing == null) {
            answer = Restock.REFUSED;
        } else {
            List<Path> renamed = sealLeftovers(listing, limit);
            List<Path> aside = new ArrayList<>();
            boolean alone;
            synchronized (this) {
                alone = aloneSince(seen);
                if (alone) {
                    recountListing(listing, aside);
                } else {
                    if (!closed && epoch == era) {
                        for (SpoolFile file : listing) {
                            if (!renamed.contains(file.file())) {
                                continue;
                            }
                            sealedSegments++;
                            sealedFiles.put(file.file().getFileName().toString(), file);
                        }
                    }
                }
            }
            for (Path entry : aside) {
                setAsideFound(entry);
            }
            answer = alone ? Restock.RECOUNTED : Restock.CONTENDED;
        }
        return answer;
    }

    private boolean aloneSince(long seen) {
        return changes == seen && inFlight == 1 && writing == null && writeTurn == null;
    }

    private List<Path> sealLeftovers(List<SpoolFile> listing, long limit) {
        List<Path> renamed = new ArrayList<>();
        for (int at = 0; at < listing.size(); at++) {
            SpoolFile each = listing.get(at);
            String name = each.file().getFileName().toString();
            if (SegmentSuffix.WRITING.matches(name)) {
                long sequence = sequenceOf(name, WRITING_SUFFIX);
                if (sequence < limit) {
                    Path sealing = directory.resolve(
                            name.substring(0, name.length() - WRITING_SUFFIX.length())
                                    + SEALED_SUFFIX);
                    IOException stillWriting = moveFailure(each.file(), sealing);
                    if (stillWriting == null) {
                        listing.set(at, new SpoolFile(sealing, each.size()));
                        renamed.add(sealing);
                    } else {
                        LOGGER.debug("geosurvey could not seal a spool segment after a"
                                + " write to it failed", stillWriting);
                    }
                }
            }
        }
        return renamed;
    }

    private void recountListing(List<SpoolFile> listing, List<Path> aside) {
        int sealed = 0;
        long liveRecords = 0;
        long liveBytes = 0;
        long asideRecords = 0;
        long asideBytes = 0;
        long highest = -1;
        java.util.TreeMap<String, SpoolFile> stillSealed = new java.util.TreeMap<>();
        for (SpoolFile each : listing) {
            Path entry = each.file();
            long size = each.size();
            String name = entry.getFileName().toString();
            if (SegmentSuffix.SEALED.matches(name)) {
                int stemEnd = name.length() - SEALED_SUFFIX.length();
                int stamp = stampOf(name, stemEnd);
                highest = Math.max(highest, sequenceIn(name, stemEnd, stamp));
                if (strideOf(name, stemEnd, stamp) != RECORD_BYTES) {
                    aside.add(entry);
                } else {
                    Integer tries = spent.get(entry);
                    if (tries == null || tries < MAX_ATTEMPTS) {
                        sealed++;
                        liveRecords += size / RECORD_BYTES;
                        liveBytes += size;
                        stillSealed.put(name, each);
                    }
                }
            } else if (SegmentSuffix.WRITING.matches(name)) {
                highest = Math.max(highest, sequenceOf(name, WRITING_SUFFIX));
                liveRecords += size / RECORD_BYTES;
                liveBytes += size;
            } else {
                if (SegmentSuffix.REFUSED.matches(name)) {
                    int stemEnd = name.length() - REFUSED_SUFFIX.length();
                    int stamp = stampOf(name, stemEnd);
                    highest = Math.max(highest, sequenceIn(name, stemEnd, stamp));
                    asideRecords += heldAt(strideOf(name, stemEnd, stamp), size);
                    asideBytes += size;
                }
            }
        }
        for (Unmoved stuck : refusedBacklog.unmoved.values()) {
            asideRecords += stuck.held();
            asideBytes += stuck.size();
        }
        nextSequence = Math.max(nextSequence, highest + 1);
        sealedSegments = sealed;
        records = liveRecords;
        bytes = liveBytes;
        refusedBacklog.quarantinedRecords = asideRecords;
        refusedBacklog.quarantinedBytes = asideBytes;
        refusedBacklog.rewrittenOfKept.clear();
        sealedFiles = stillSealed;
    }

    // Removes a spool that holds nothing, so the directory does not accrete.
    boolean removeIfEmpty() {
        if (!shutIfEmpty()) {
            return false;
        }
        boolean removed;
        try {
            Path worldFile = directory.resolve(WORLD_FILE);
            if (!Files.deleteIfExists(worldFile)) {
                LOGGER.debug("geosurvey found {} already gone when it tried to delete it.",
                        worldFile);
            }
            if (!Files.deleteIfExists(directory)) {
                LOGGER.debug("geosurvey found {} already gone when it tried to delete it.",
                        directory);
            }
            removed = true;
        } catch (IOException stillThere) {
            removed = false;
        }
        return removed;
    }

    private boolean shutIfEmpty() {
        if (!isEmpty()) {
            return false;
        }
        long seen;
        synchronized (this) {
            seen = changes;
        }
        boolean holdsNothing = folderHoldsNothing();
        boolean shut;
        synchronized (this) {
            shut = holdsNothing && isEmpty() && changes == seen;
            if (shut) {
                closed = true;
                endMeasuring();
            }
        }
        return shut;
    }

    private boolean folderHoldsNothing() {
        boolean nothing;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            nothing = true;
            java.util.Iterator<Path> each = entries.iterator();
            while (nothing && each.hasNext()) {
                nothing = each.next().getFileName().toString().equals(WORLD_FILE);
            }
        } catch (IOException | RuntimeException unreadable) {
            nothing = false;
        }
        return nothing;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, MOVE_OPTIONS);
        } catch (java.nio.file.AtomicMoveNotSupportedException acrossDevices) {
            Files.move(from, to, MOVE_FALLBACK_OPTIONS);
        }
    }
}
