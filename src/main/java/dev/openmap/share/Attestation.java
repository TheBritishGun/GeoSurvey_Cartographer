package dev.openmap.share;

import java.util.Objects;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

public final class Attestation {

    private static final String MOJANG_ALGORITHM = "SHA1withRSA";

    public static final String PLAYER_ALGORITHM = "SHA256withRSA";

    // Lowercase hex; the exact bytes matter.
    private static final byte[] HEX_DIGITS =
            "0123456789abcdef".getBytes(StandardCharsets.ISO_8859_1);

    private static final ThreadLocal<MessageDigest> SHA_256 = new Sha256Digest();

    public static final Duration GRACE = Duration.ofHours(8);

    private static final int CREDENTIALS_REMEMBERED = RosterReport.MAX_PLAYERS;

    private static final float CACHE_LOAD_FACTOR = 0.75f;

    private static final int CACHE_TABLE_SLOTS =
            (int) (CREDENTIALS_REMEMBERED / CACHE_LOAD_FACTOR) + 1;

    private static final int MOJANG_KEYS_REMEMBERED = 16;

    private static final PublicKey[] NO_KEYS = new PublicKey[0];

    private static final int HASH_MULTIPLIER = 31;

    private static final int UUID_BYTES = 2 * Long.BYTES;

    private static final int SIGNED_PAYLOAD_HEADER_BYTES = UUID_BYTES + Long.BYTES;

    private static final int HEX_CHARACTERS_PER_BYTE = 2;

    private static final int UNSIGNED_BYTE_MASK = 0xFF;

    private static final int HIGH_NIBBLE_SHIFT = 4;

    private static final int LOW_NIBBLE_MASK = 0x0F;

    private static final int REFUSAL_GROWTH_FACTOR = 2;

    private static final class Sha256Digest extends ThreadLocal<MessageDigest> {

        @Override
        protected MessageDigest initialValue() {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("no SHA-256", impossible);
            }
            return digest;
        }
    }

    private static final class VerdictCache extends LinkedHashMap<Contents, Verdicts> {

        private static final long serialVersionUID = 1L;

        VerdictCache() {
            super(CACHE_TABLE_SLOTS, CACHE_LOAD_FACTOR, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Contents, Verdicts> eldest) {
            return size() > CREDENTIALS_REMEMBERED;
        }
    }

    private static final class PlayerKeyCache extends LinkedHashMap<Encoded, PublicKey> {

        private static final long serialVersionUID = 1L;

        PlayerKeyCache() {
            super(CACHE_TABLE_SLOTS, CACHE_LOAD_FACTOR, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Encoded, PublicKey> eldest) {
            return size() > CREDENTIALS_REMEMBERED;
        }
    }

    private static final Map<Contents, Verdicts> VERDICTS = new VerdictCache();

    private static final Map<Encoded, PublicKey> PLAYER_KEYS = new PlayerKeyCache();

    private Attestation() {
    }

    public record Credential(UUID player, Instant expiresAt, byte[] publicKey,
                             byte[] keySignature) {
        public Credential {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(publicKey, "publicKey");
            Objects.requireNonNull(keySignature, "keySignature");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Credential that
                    && player.equals(that.player)
                    && expiresAt.equals(that.expiresAt)
                    && Arrays.equals(publicKey, that.publicKey)
                    && Arrays.equals(keySignature, that.keySignature);
        }

        @Override
        public int hashCode() {
            int hash = player.hashCode();
            hash = HASH_MULTIPLIER * hash + expiresAt.hashCode();
            hash = HASH_MULTIPLIER * hash + Arrays.hashCode(publicKey);
            return HASH_MULTIPLIER * hash + Arrays.hashCode(keySignature);
        }
    }

    static final class Seen {

        private Credential of;

        private PublicKey playerKey;
    }

    public static byte[] signedPayload(UUID player, Instant expiresAt,
                                       byte[] publicKey) {
        ByteBuffer buffer = ByteBuffer.allocate(SIGNED_PAYLOAD_HEADER_BYTES + publicKey.length)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(player.getMostSignificantBits());
        buffer.putLong(player.getLeastSignificantBits());
        buffer.putLong(expiresAt.toEpochMilli());
        buffer.put(publicKey);
        return buffer.array();
    }

    public static boolean vouchedByMojang(PublicKey mojangKey, Credential credential) {
        if (mojangKey == null || credential == null) {
            return false;
        }
        return vouchingKey(Collections.singletonList(mojangKey), credential, null)
                != null;
    }

    public static PublicKey vouchingKey(Collection<? extends PublicKey> mojangKeys,
                                        Credential credential,
                                        BiConsumer<PublicKey,
                                                GeneralSecurityException> unusable) {
        return vouchingKey(mojangKeys, credential, unusable, null);
    }

    static PublicKey vouchingKey(Collection<? extends PublicKey> mojangKeys,
                                 Credential credential,
                                 BiConsumer<PublicKey,
                                         GeneralSecurityException> unusable,
                                 Seen seen) {
        if (mojangKeys == null || mojangKeys.isEmpty() || credential == null) {
            return null;
        }
        Verdicts known = verdictsOn(Contents.viewOf(credential));
        if (seen != null) {
            seen.of = credential;
            seen.playerKey = known.playerKey;
        }
        Contents held = null;
        byte[] payload = null;
        Signature settled = null;
        PublicKey settledBy = null;
        Refusals refused = null;
        PublicKey proven = null;
        PublicKey accepted = null;
        for (PublicKey candidate : mojangKeys) {
            if (known.vouchedBy(candidate)) {
                accepted = candidate;
                break;
            }
            if (!known.refusedBy(candidate)
                    && (refused == null || !holds(refused.slots, refused.size, candidate))) {
                if (held == null) {
                    held = Contents.copyOf(credential);
                    payload = payloadOf(held.credential);
                }
                Signature signature = initialisedOrReported(settled, settledBy, candidate,
                        unusable);
                if (signature != null) {
                    settled = signature;
                    settledBy = candidate;
                    if (verifies(signature, payload, held.credential.keySignature())) {
                        accepted = candidate;
                        proven = candidate;
                        break;
                    }
                    if (refused == null) {
                        refused = new Refusals();
                    }
                    refused.add(candidate);
                }
            }
        }
        remember(held, proven, refused, seen);
        return accepted;
    }

    public static PublicKey vouchingKey(Collection<? extends PublicKey> mojangKeys,
                                        Credential credential) {
        return vouchingKey(mojangKeys, credential, null);
    }

    public static boolean vouchedByAnyOf(Collection<? extends PublicKey> mojangKeys,
                                         Credential credential) {
        return vouchingKey(mojangKeys, credential) != null;
    }

    private static byte[] payloadOf(Credential credential) {
        return signedPayload(credential.player(), credential.expiresAt(),
                credential.publicKey());
    }

    private static Signature initialised(Signature settled, PublicKey settledBy,
                                         PublicKey candidate)
            throws GeneralSecurityException {
        if (settled != null && settledBy != null && candidate != null
                && candidate.getClass() == settledBy.getClass()
                && initialises(settled, candidate)) {
            return settled;
        }
        Signature fresh = Signature.getInstance(MOJANG_ALGORITHM);
        fresh.initVerify(candidate);
        return fresh;
    }

    private static boolean initialises(Signature signature, PublicKey key) {
        boolean initialises;
        try {
            signature.initVerify(key);
            initialises = true;
        } catch (GeneralSecurityException refused) {
            initialises = false;
        }
        return initialises;
    }

    private static boolean verifies(Signature signature, byte[] payload, byte[] signed) {
        boolean verifies;
        try {
            signature.update(payload);
            verifies = signature.verify(signed);
        } catch (GeneralSecurityException refused) {
            verifies = false;
        }
        return verifies;
    }

    private static Signature initialisedOrReported(Signature settled, PublicKey settledBy,
                                                   PublicKey candidate,
                                                   BiConsumer<PublicKey,
                                                           GeneralSecurityException> unusable) {
        Signature signature;
        try {
            signature = initialised(settled, settledBy, candidate);
        } catch (GeneralSecurityException unusableKey) {
            if (unusable != null) {
                unusable.accept(candidate, unusableKey);
            }
            signature = null;
        }
        return signature;
    }

    private static Verdicts verdictsOn(Contents contents) {
        synchronized (VERDICTS) {
            Verdicts known = VERDICTS.get(contents);
            return known == null ? Verdicts.NONE : known;
        }
    }

    private static void remember(Contents held, PublicKey vouched, Refusals refused,
                                 Seen seen) {
        if (held == null || (vouched == null
                && (refused == null || refused.isEmpty()))) {
            return;
        }
        final PublicKey player;
        if (vouched != null && seen != null) {
            player = seen.playerKey == null
                    ? parsed(held.credential.publicKey())
                    : seen.playerKey;
            seen.playerKey = player;
        } else {
            player = null;
        }
        PublicKey[] rejected = refused == null ? NO_KEYS : refused.publish();
        synchronized (VERDICTS) {
            Verdicts before = VERDICTS.get(held);
            if (before != null || vouched != null) {
                VERDICTS.put(held, (before == null ? Verdicts.NONE : before)
                        .and(vouched, rejected).with(player));
            }
        }
    }

    private static PublicKey[] remembering(PublicKey[] keys, PublicKey key) {
        if (keys.length >= MOJANG_KEYS_REMEMBERED || holds(keys, key)) {
            return keys;
        }
        PublicKey[] more = Arrays.copyOf(keys, keys.length + 1);
        more[keys.length] = key;
        return more;
    }

    private static boolean holds(PublicKey[] keys, PublicKey key) {
        return holds(keys, keys.length, key);
    }

    private static boolean holds(PublicKey[] keys, int members, PublicKey key) {
        boolean holds = false;
        for (int i = 0; i < members; i++) {
            if (keys[i] == key) {
                holds = true;
                break;
            }
        }
        return holds;
    }

    public static String fingerprint(byte[] x509PublicKey) {
        MessageDigest sha = SHA_256.get();
        sha.reset();
        byte[] digest = sha.digest(x509PublicKey);
        byte[] out = new byte[digest.length * HEX_CHARACTERS_PER_BYTE];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & UNSIGNED_BYTE_MASK;
            out[i * HEX_CHARACTERS_PER_BYTE] = HEX_DIGITS[b >>> HIGH_NIBBLE_SHIFT];
            out[i * HEX_CHARACTERS_PER_BYTE + 1] = HEX_DIGITS[b & LOW_NIBBLE_MASK];
        }
        return new String(out, StandardCharsets.ISO_8859_1);
    }

    public static String fingerprint(Credential credential) {
        return fingerprint(credential.publicKey());
    }

    public static boolean current(Credential credential, Instant now) {
        return current(credential, now.toEpochMilli());
    }

    public static boolean current(Credential credential, long nowEpochMilli) {
        return nowEpochMilli - GRACE.toMillis() < credential.expiresAt().toEpochMilli();
    }

    public static boolean signedByPlayer(Credential credential, byte[] payload,
                                         byte[] playerSignature) {
        PublicKey known = playerKeyFor(Encoded.viewOf(credential.publicKey()));
        boolean signed = false;
        try {
            if (known != null) {
                signed = signedBy(known, payload, playerSignature);
            } else {
                Encoded held = Encoded.copyOf(credential.publicKey());
                PublicKey key = parsed(held.bytes);
                signed = key != null && signedBy(key, payload, playerSignature);
                if (signed) {
                    rememberPlayerKey(held, key);
                }
            }
        } catch (GeneralSecurityException refused) {
            signed = false;
        }
        return signed;
    }

    static boolean signedByPlayer(Credential credential, byte[] payload,
                                  byte[] playerSignature, Seen seen) {
        PublicKey vouchedFor = seen == null || seen.of != credential ? null : seen.playerKey;
        boolean signed;
        if (vouchedFor == null) {
            signed = signedByPlayer(credential, payload, playerSignature);
        } else {
            try {
                signed = signedBy(vouchedFor, payload, playerSignature);
            } catch (GeneralSecurityException refused) {
                signed = false;
            }
        }
        return signed;
    }

    private static PublicKey parsed(byte[] x509PublicKey) {
        PublicKey parsed;
        try {
            parsed = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(x509PublicKey));
        } catch (GeneralSecurityException refused) {
            parsed = null;
        }
        return parsed;
    }

    private static boolean signedBy(PublicKey key, byte[] payload, byte[] signed)
            throws GeneralSecurityException {
        Signature signature = Signature.getInstance(PLAYER_ALGORITHM);
        signature.initVerify(key);
        signature.update(payload);
        return signature.verify(signed);
    }

    private static PublicKey playerKeyFor(Encoded encoded) {
        synchronized (PLAYER_KEYS) {
            return PLAYER_KEYS.get(encoded);
        }
    }

    private static void rememberPlayerKey(Encoded held, PublicKey key) {
        synchronized (PLAYER_KEYS) {
            PLAYER_KEYS.put(held, key);
        }
    }

    public static boolean verify(PublicKey mojangKey, Credential credential,
                                 byte[] payload, byte[] playerSignature, Instant now) {
        if (mojangKey == null || credential == null || now == null || payload == null
                || playerSignature == null || !current(credential, now)) {
            return false;
        }
        if (!vouchedByMojang(mojangKey, credential)) {
            return false;
        }
        return signedByPlayer(credential, payload, playerSignature);
    }

    public static boolean verifyAny(Collection<? extends PublicKey> mojangKeys,
                                    Credential credential, byte[] payload,
                                    byte[] playerSignature, Instant now,
                                    BiConsumer<PublicKey,
                                            GeneralSecurityException> unusable) {
        if (credential == null || now == null || payload == null
                || playerSignature == null || !current(credential, now)) {
            return false;
        }
        if (vouchingKey(mojangKeys, credential, unusable) == null) {
            return false;
        }
        return signedByPlayer(credential, payload, playerSignature);
    }

    public static boolean verifyAny(Collection<? extends PublicKey> mojangKeys,
                                    Credential credential, byte[] payload,
                                    byte[] playerSignature, Instant now) {
        return verifyAny(mojangKeys, credential, payload, playerSignature, now, null);
    }

    private static final class Contents {

        private final Credential credential;

        private final int hash;

        private Contents(Credential credential) {
            this.credential = credential;
            int hash = credential.player().hashCode();
            hash = HASH_MULTIPLIER * hash + credential.expiresAt().hashCode();
            hash = HASH_MULTIPLIER * hash + Arrays.hashCode(credential.publicKey());
            hash = HASH_MULTIPLIER * hash + Arrays.hashCode(credential.keySignature());
            this.hash = hash;
        }

        static Contents viewOf(Credential credential) {
            return new Contents(credential);
        }

        static Contents copyOf(Credential credential) {
            return new Contents(new Credential(credential.player(),
                    credential.expiresAt(), credential.publicKey().clone(),
                    credential.keySignature().clone()));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Contents that
                    && hash == that.hash
                    && credential.player().equals(that.credential.player())
                    && credential.expiresAt().equals(that.credential.expiresAt())
                    && Arrays.equals(credential.publicKey(), that.credential.publicKey())
                    && Arrays.equals(credential.keySignature(),
                            that.credential.keySignature());
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class Encoded {

        private final byte[] bytes;

        private final int hash;

        private Encoded(byte[] bytes) {
            this.bytes = bytes;
            this.hash = Arrays.hashCode(bytes);
        }

        static Encoded viewOf(byte[] bytes) {
            return new Encoded(bytes);
        }

        static Encoded copyOf(byte[] bytes) {
            return new Encoded(bytes.clone());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Encoded that
                    && hash == that.hash
                    && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class Refusals {

        private PublicKey[] slots;

        private int size;

        Refusals() {
            slots = NO_KEYS;
        }

        static Refusals seeded(PublicKey[] published) {
            Refusals growing = new Refusals();
            growing.slots = Arrays.copyOf(published, room(published.length));
            growing.size = published.length;
            return growing;
        }

        private static int room(int already) {
            return Math.min(MOJANG_KEYS_REMEMBERED,
                    already == 0 ? 1 : REFUSAL_GROWTH_FACTOR * already);
        }

        boolean isEmpty() {
            return size == 0;
        }

        void add(PublicKey key) {
            if (size >= MOJANG_KEYS_REMEMBERED || holds(slots, size, key)) {
                return;
            }
            if (size == slots.length) {
                slots = Arrays.copyOf(slots, room(slots.length));
            }
            slots[size++] = key;
        }

        PublicKey[] publish() {
            return size == slots.length ? slots : Arrays.copyOf(slots, size);
        }
    }

    private static final class Verdicts {

        private static final Verdicts NONE = new Verdicts(NO_KEYS, NO_KEYS);

        private final PublicKey[] vouched;

        private final PublicKey[] refused;

        private final PublicKey playerKey;

        private Verdicts(PublicKey[] vouched, PublicKey[] refused) {
            this(vouched, refused, null);
        }

        private Verdicts(PublicKey[] vouched, PublicKey[] refused, PublicKey playerKey) {
            this.vouched = vouched;
            this.refused = refused;
            this.playerKey = playerKey;
        }

        boolean vouchedBy(PublicKey key) {
            return holds(vouched, key);
        }

        boolean refusedBy(PublicKey key) {
            return holds(refused, key);
        }

        Verdicts and(PublicKey vouchedNow, PublicKey[] refusedNow) {
            PublicKey[] vouchedBy = vouchedNow == null ? vouched
                    : remembering(vouched, vouchedNow);
            PublicKey[] refusedBy;
            if (refusedNow.length == 0) {
                refusedBy = refused;
            } else if (refused.length == 0) {
                refusedBy = refusedNow;
            } else {
                Refusals merged = Refusals.seeded(refused);
                for (PublicKey key : refusedNow) {
                    merged.add(key);
                }
                refusedBy = merged.publish();
            }
            Verdicts combined;
            if (vouchedBy == vouched && refusedBy == refused) {
                combined = this;
            } else {
                combined = new Verdicts(vouchedBy, refusedBy, playerKey);
            }
            return combined;
        }

        Verdicts with(PublicKey playerNow) {
            return playerNow == null || playerKey != null
                    ? this
                    : new Verdicts(vouched, refused, playerNow);
        }
    }
}
