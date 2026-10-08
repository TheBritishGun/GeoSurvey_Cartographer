package dev.openmap.client;

import dev.sandpaper.core.Background;
import dev.openmap.LandNav;
import dev.openmap.share.Attestation;
import dev.openmap.share.UtcClock;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class LocalKey {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    static final String FILE_NAME = "geosurvey-identity.key";

    private static final String WRITING_SUFFIX = ".writing";

    private static final String PUBLISH_LOCK_SUFFIX = ".publish.lock";

    private static final Duration PUBLISH_LOCK_WAIT = Duration.ofSeconds(5);

    private static final long PUBLISH_LOCK_WAIT_NANOS = PUBLISH_LOCK_WAIT.toNanos();

    private static final long PUBLISH_LOCK_RETRY_MILLIS = 5L;

    private static volatile Thread publicationOwner;

    private static final int BITS = 2048;

    private static final long MAX_FILE = 64 * 1024;

    private static final Duration LIFETIME = Duration.ofDays(7);

    private static final Duration REMINT_WITHIN = Duration.ofDays(1);

    private static final long REMINT_WITHIN_MILLIS = REMINT_WITHIN.toMillis();

    private static final Duration LEFTOVER_AFTER = Duration.ofHours(1);

    private static final long LEFTOVER_AFTER_MILLIS = LEFTOVER_AFTER.toMillis();

    private static final Duration RETRY_AFTER = Duration.ofMinutes(1);

    private static final long RETRY_AFTER_NANOS = RETRY_AFTER.toNanos();

    private static final long RETRY_AFTER_SECONDS = RETRY_AFTER.toSeconds();

    private static final Duration MINT_RETRY_AFTER = RETRY_AFTER;

    private static final long MINT_RETRY_AFTER_NANOS = MINT_RETRY_AFTER.toNanos();

    private static final ThreadPoolExecutor ATTEMPTS = new ThreadPoolExecutor(
            0, 1, RETRY_AFTER.multipliedBy(2).toNanos(), TimeUnit.NANOSECONDS,
            new LinkedBlockingQueue<>(), Background.factory("geosurvey-identity"));

    private static final AtomicReference<CompletableFuture<KeyPair>> LOADING =
            new AtomicReference<>();

    private static volatile StoppableWorkers.Registration attemptsRegistration = null;

    // A nanoTime value.
    private static volatile long nextAttemptAt;

    private static volatile byte[] publicKey;

    private static volatile String unusable = "";

    private static volatile ShareSender.Identity minted;

    private static volatile boolean announced;

    private static volatile String warnedUnusable;

    private static volatile String warnedSigning;

    private static volatile KeyPair mintAttemptKey;

    private static volatile UUID mintAttemptPlayer;

    // A nanoTime value.
    private static volatile long nextMintAttemptAt;

    private static volatile Path sweptFolder;

    private LocalKey() {
    }

    static ShareSender.Identity identity(UUID player) {
        if (player == null) {
            return null;
        }
        ShareSender.Identity held = minted;
        if (held != null && usable(held, player)) {
            return held;
        }
        KeyPair pair = keyPair();
        if (pair == null) {
            return null;
        }
        if (pair == mintAttemptKey && player.equals(mintAttemptPlayer)
                && nextMintAttemptAt - System.nanoTime() > 0) {
            return null;
        }
        ShareSender.Identity made = mint(pair, player);
        minted = made;
        return made;
    }

    private static boolean usable(ShareSender.Identity held, UUID player) {
        Attestation.Credential credential = held.credential();
        return credential.player().equals(player)
                && UtcClock.collector().nowMillis()
                        < credential.expiresAt().toEpochMilli() - REMINT_WITHIN_MILLIS;
    }

    // Null until ready.
    private static KeyPair keyPair() {
        CompletableFuture<KeyPair> started = LOADING.get();
        if (started != null) {
            if (!started.isDone()) {
                return null;
            }
            KeyPair made = started.getNow(null);
            if (made != null) {
                return made;
            }
            if (nextAttemptAt - System.nanoTime() > 0) {
                return null;
            }
        }
        CompletableFuture<KeyPair> fresh = new CompletableFuture<>();
        if (!LOADING.compareAndSet(started, fresh)) {
            return null;
        }
        nextAttemptAt = System.nanoTime() + RETRY_AFTER_NANOS;
        Runnable making = () -> {
            KeyPair made = null;
            try {
                made = loadOrCreate();
            } catch (RuntimeException unexpected) {
                String kind = unexpected.getClass().getName();
                if (!kind.equals(warnedUnusable)) {
                    warnedUnusable = kind;
                    LOGGER.warn("geosurvey could not prepare its local identity key."
                            + " Retries every " + RETRY_AFTER_SECONDS + " seconds.",
                            unexpected);
                }
            } finally {
                if (made == null && unusable.isEmpty()) {
                    unusable = "the local identity key could not be prepared";
                }
                fresh.complete(made);
            }
        };
        registerAttempts();
        ATTEMPTS.execute(making);
        return null;
    }

    private static void registerAttempts() {
        if (attemptsRegistration != null) {
            return;
        }
        synchronized (LocalKey.class) {
            if (attemptsRegistration == null) {
                attemptsRegistration = StoppableWorkers.executor("geosurvey-identity", ATTEMPTS);
            }
        }
    }

    private static KeyPair loadOrCreate() {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        Path file = keyFile();
        sweepLeftovers(file);
        KeyPair loaded;
        try {
            OptionalLong size = presentSize(file);
            if (size.isPresent()) {
                loaded = read(file, size.getAsLong());
            } else if (Thread.currentThread().isInterrupted()) {
                loaded = null;
            } else {
                try {
                    loaded = create(file);
                } catch (FileAlreadyExistsException raced) {
                    loaded = read(file);
                }
            }
        } catch (IOException | GeneralSecurityException | RuntimeException noKey) {

            unusable = "the local identity key could not be opened (see the log)";

            String kind = noKey.getClass().getName();
            if (!kind.equals(warnedUnusable)) {
                warnedUnusable = kind;
                LOGGER.warn("geosurvey could not open its local identity key at " + file
                        + ". No replacement is minted;"
                        + " retries every "
                        + RETRY_AFTER_SECONDS + " seconds.", noKey);
            }
            loaded = null;
        }
        return loaded;
    }

    static Path keyFile() {
        return LandNav.configPath().resolveSibling(FILE_NAME);
    }

    private static void sweepLeftovers(Path file) {
        Path folder = file.getParent();
        if (folder == null) {
            return;
        }
        if (folder.equals(sweptFolder)) {
            return;
        }
        sweptFolder = folder;
        String ours = file.getFileName() + ".";
        long leftOverBy = System.currentTimeMillis() - LEFTOVER_AFTER_MILLIS;
        try (DirectoryStream<Path> names = Files.newDirectoryStream(folder)) {
            for (Path name : names) {
                removeIfLeftOver(name, ours, leftOverBy);
            }
        } catch (IOException | RuntimeException cannotLook) {
            LOGGER.warn("geosurvey could not scan {} for leftover files"
                    + " ({})."
                    + " Look for {}<something>{}; safe to delete.",
                    folder, cannotLook.toString(), ours, WRITING_SUFFIX);
        }
    }

    private static void removeIfLeftOver(Path name, String ours, long leftOverBy) {
        String leaf = name.getFileName().toString();
        if (leaf.startsWith(ours) && leaf.endsWith(WRITING_SUFFIX)) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(name, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attrs.isRegularFile() && attrs.lastModifiedTime().toMillis() <= leftOverBy) {
                    try {
                        if (Files.deleteIfExists(name)) {
                            LOGGER.info("geosurvey removed {}."
                                    + " It could hold a copy"
                                    + " of the key.", name);
                        }
                    } catch (IOException | RuntimeException leftBehind) {
                        LOGGER.warn("geosurvey could not remove {}"
                                + " ({}). Nothing reads it;"
                                + " safe to delete.", name, leftBehind.toString());
                    }
                }
            } catch (IOException vanished) {
            } catch (RuntimeException leftBehind) {
                LOGGER.warn("geosurvey could not remove {}"
                        + " ({}). Nothing reads it;"
                        + " safe to delete.", name, leftBehind.toString());
            }
        }
    }

    // Deletes an empty file.
    private static OptionalLong presentSize(Path file) throws IOException {
        OptionalLong size;
        try {
            size = OptionalLong.of(Files.readAttributes(file, BasicFileAttributes.class).size());
        } catch (NoSuchFileException absent) {
            size = OptionalLong.empty();
        }
        OptionalLong present;
        if (size.isEmpty()) {
            present = OptionalLong.empty();
        } else if (size.getAsLong() != 0) {
            present = size;
        } else {
            Files.delete(file);
            present = OptionalLong.empty();
        }
        return present;
    }

    private static KeyPair read(Path file)
            throws IOException, GeneralSecurityException {
        return read(file, Files.size(file));
    }

    private static KeyPair read(Path file, long size)
            throws IOException, GeneralSecurityException {
        if (size > MAX_FILE) {
            throw new IOException("the identity key file is " + size + " bytes, over"
                    + " the " + MAX_FILE + "-byte"
                    + " ceiling");
        }
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        PrivateKey held = rsa.generatePrivate(
                new PKCS8EncodedKeySpec(Files.readAllBytes(file)));
        if (!(held instanceof RSAPrivateCrtKey crt)) {
            throw new GeneralSecurityException("the stored key has no public"
                    + " half.");
        }
        PublicKey half = rsa.generatePublic(
                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        return new KeyPair(half, held);
    }

    private static KeyPair create(Path file)
            throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(BITS);
        KeyPair pair = generator.generateKeyPair();
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path staging = file.resolveSibling(
                file.getFileName() + "." + UUID.randomUUID() + WRITING_SUFFIX);
        try {
            Files.createFile(staging, PosixFilePermissions.asFileAttribute(
                    Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException noPosixBits) {
            Files.createFile(staging);
        }
        try {
            Files.write(staging, pair.getPrivate().getEncoded(),
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.createLink(file, staging);
            } catch (FileAlreadyExistsException raced) {
                throw raced;
            } catch (IOException | UnsupportedOperationException noHardLinks) {
                pair = publishByMove(file, staging, pair);
            }
        } finally {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException leftBehind) {
                LOGGER.warn("geosurvey could not remove {}"
                        + " ({}). Nothing reads it;"
                        + " safe to delete.", staging, leftBehind.toString());
            }
        }
        return pair;
    }

    private static KeyPair publishByMove(Path file, Path staging, KeyPair mine)
            throws IOException, GeneralSecurityException {
        FileChannel window = openPublicationWindow(file);
        KeyPair published;
        if (window == null) {
            Files.move(staging, file);
            published = wonFallbackRace(file, mine);
        } else {
            try {
                Files.move(staging, file);
                published = mine;
            } finally {
                publicationOwner = null;
                closePublicationWindow(window);
            }
        }
        return published;
    }

    private static FileChannel openPublicationWindow(Path file) {
        Path lockFile = file.resolveSibling(file.getFileName() + PUBLISH_LOCK_SUFFIX);
        FileChannel channel;
        try {
            channel = FileChannel.open(lockFile,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE));
        } catch (IOException | RuntimeException refused) {
            channel = null;
        }
        FileChannel window = null;
        if (channel != null) {
            long until = System.nanoTime() + PUBLISH_LOCK_WAIT_NANOS;
            boolean refused = false;
            while (publicationOwner != Thread.currentThread() && !refused && window == null) {
                try {
                    if (channel.tryLock() != null) {
                        publicationOwner = Thread.currentThread();
                        window = channel;
                    }
                } catch (OverlappingFileLockException heldInThisProcess) {
                } catch (IOException | RuntimeException cannotLock) {
                    refused = true;
                }
                if (!refused && window == null) {
                    if (System.nanoTime() < until) {
                        try {
                            Thread.sleep(PUBLISH_LOCK_RETRY_MILLIS);
                        } catch (InterruptedException cutShort) {
                            Thread.currentThread().interrupt();
                            refused = true;
                        }
                    } else {
                        refused = true;
                    }
                }
            }
            if (window == null) {
                closePublicationWindow(channel);
            }
        }
        return window;
    }

    private static void closePublicationWindow(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException | RuntimeException closed) {
            LOGGER.warn("geosurvey could not close its local identity key file"
                    + " ({}). The lock stays"
                    + " until this process ends.", closed.toString());
        }
    }

    // Returns the key pair on disk, possibly another process's.
    private static KeyPair wonFallbackRace(Path file, KeyPair mine)
            throws IOException, GeneralSecurityException {
        KeyPair onDisk = read(file);
        if (Arrays.equals(onDisk.getPublic().getEncoded(), mine.getPublic().getEncoded())) {
            return mine;
        }
        return onDisk;
    }

    private static ShareSender.Identity mint(KeyPair pair, UUID player) {
        byte[] encoded = pair.getPublic().getEncoded();
        Instant expiresAt = Instant.ofEpochMilli(UtcClock.collector().nowMillis()).plus(LIFETIME);
        byte[] keySignature;
        ShareSender.Identity identity;
        try {
            Signature self = Signature.getInstance(ShareSender.PLAYER_ALGORITHM);
            self.initSign(pair.getPrivate());
            self.update(Attestation.signedPayload(player, expiresAt, encoded));
            keySignature = self.sign();
            mintAttemptKey = null;
            publicKey = encoded;
            unusable = "";
            announce();
            identity = new ShareSender.Identity(
                    new Attestation.Credential(player, expiresAt, encoded, keySignature),
                    ShareSender.reusableSigner(pair.getPrivate(), ShareSender.PLAYER_ALGORITHM));
        } catch (GeneralSecurityException cannotSign) {

            String kind = cannotSign.getClass().getName();
            if (!kind.equals(warnedSigning)) {
                warnedSigning = kind;
                LOGGER.warn("geosurvey's local identity key could not sign its credential."
                        + " Nothing is contributed under it;"
                        + " retries on the next scan.", cannotSign);
            }

            unusable = "the local identity key could not sign for itself (see the log)";
            mintAttemptKey = pair;
            mintAttemptPlayer = player;
            nextMintAttemptAt = System.nanoTime() + MINT_RETRY_AFTER_NANOS;
            identity = null;
        }
        return identity;
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        LOGGER.info("A collector needs manual trust of the local key"
                + " at {},"
                + " or turn on Prove this account to the"
                + " collector."
                + " Fingerprint: {}",
                keyFile(), fingerprint());
    }

    static String fingerprintOf(byte[] x509PublicKey) {
        String fingerprint;
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(x509PublicKey));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("no SHA-256", impossible);
        }
        return fingerprint;
    }

    static String fingerprint() {
        byte[] mine = publicKey;
        return mine == null ? "" : fingerprintOf(mine);
    }

    static boolean isMine(Attestation.Credential credential) {
        byte[] mine = publicKey;
        return mine != null && credential != null
                && Arrays.equals(mine, credential.publicKey());
    }

    static String unusable() {
        return unusable;
    }
}
