package dev.openmap.share;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

public record SignedBatch(Attestation.Credential credential, byte[] signature,
                          byte[] batch) {

    public SignedBatch {
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(batch, "batch");
    }

    public static final int MAGIC = 0x43525331;

    private static final int MAX_SIGNATURE = 1024;

    private static final int MAX_KEY = 4096;

    private static final long GRACE_MILLIS = Attestation.GRACE.toMillis();

    private static final long MILLIS_PER_SECOND = 1_000L;

    private static final int HASH_MULTIPLIER = 31;

    public byte[] encode() throws IOException {
        byte[] message = new byte[encodedLength(credential, signature, batch.length)];
        writeEnvelope(message, credential, signature, batch, batch.length);
        return message;
    }

    public static byte[] encodeInto(byte[] reuse, Attestation.Credential credential,
            byte[] signature, byte[] batch) {
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(batch, "batch");
        return encodeInto(reuse, credential, signature, batch, batch.length);
    }

    public static byte[] encodeInto(byte[] reuse, Attestation.Credential credential,
            byte[] signature, byte[] batch, int batchLength) {
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(batch, "batch");
        Objects.checkFromIndexSize(0, batchLength, batch.length);
        int total = encodedLength(credential, signature, batchLength);
        byte[] message = reuse != null && reuse.length == total ? reuse : new byte[total];
        writeEnvelope(message, credential, signature, batch, batchLength);
        return message;
    }

    public static int encodeTo(byte[] room, Attestation.Credential credential,
            byte[] signature, byte[] batch, int batchLength) {
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(batch, "batch");
        Objects.checkFromIndexSize(0, batchLength, batch.length);
        int total = encodedLength(credential, signature, batchLength);
        Objects.checkFromIndexSize(0, total, room.length);
        writeEnvelope(room, credential, signature, batch, batchLength);
        return total;
    }

    public static SignedBatch decode(byte[] message, int maxBatch) throws IOException {
        WireCursor body = new WireCursor(message);
        if (body.readInt() != MAGIC) {
            throw new IOException("not a signed batch");
        }
        UUID player = new UUID(body.readLong(), body.readLong());
        Instant expires = Instant.ofEpochMilli(body.readLong());
        byte[] key = body.readBytes(MAX_KEY, "public key");
        byte[] keySignature = body.readBytes(MAX_SIGNATURE, "key signature");
        byte[] signature = body.readBytes(MAX_SIGNATURE, "signature");
        byte[] batch = body.readBytes(maxBatch, "batch");
        int left = body.remaining();
        if (left != 0) {
            throw new IOException(left
                    + " bytes after the signed batch."
                    + " Refusing it.");
        }
        return new SignedBatch(
                new Attestation.Credential(player, expires, key, keySignature),
                signature, batch);
    }

    public Batch verified(PublicKey mojangKey, Instant now) throws IOException {
        if (mojangKey == null || now == null) {
            return null;
        }
        long at = now.toEpochMilli();
        SignedBatch held = accepted(Collections.singletonList(mojangKey), at, null, null);
        return held == null ? null : held.attested(at);
    }

    public Batch verifiedByAnyOf(Collection<? extends PublicKey> mojangKeys, Instant now,
            BiConsumer<PublicKey, GeneralSecurityException> unusable)
            throws IOException {
        return verifiedByAnyOf(mojangKeys, now, unusable, null);
    }

    public Presence verifiedPresenceByAnyOf(Collection<? extends PublicKey> mojangKeys,
            Instant now, BiConsumer<PublicKey, GeneralSecurityException> unusable)
            throws IOException {
        return verifiedPresenceByAnyOf(mojangKeys, now, unusable, null);
    }

    public RosterReport verifiedRosterByAnyOf(Collection<? extends PublicKey> mojangKeys,
            Instant now, BiConsumer<PublicKey, GeneralSecurityException> unusable)
            throws IOException {
        return verifiedRosterByAnyOf(mojangKeys, now, unusable, null);
    }

    public Batch verifiedByAnyOf(Collection<? extends PublicKey> mojangKeys, Instant now,
            BiConsumer<PublicKey, GeneralSecurityException> unusable,
            Predicate<Attestation.Credential> operatorTrusts) throws IOException {
        if (now == null) {
            return null;
        }
        long at = now.toEpochMilli();
        SignedBatch held = accepted(mojangKeys, at, unusable, operatorTrusts);
        return held == null ? null : held.attested(at);
    }

    public Presence verifiedPresenceByAnyOf(Collection<? extends PublicKey> mojangKeys,
            Instant now, BiConsumer<PublicKey, GeneralSecurityException> unusable,
            Predicate<Attestation.Credential> operatorTrusts) throws IOException {
        if (now == null) {
            return null;
        }
        long at = now.toEpochMilli();
        SignedBatch held = accepted(mojangKeys, at, unusable, operatorTrusts);
        return held == null ? null : held.attestedPresence(at);
    }

    // The envelope opened as a roster rather than a position.
    public RosterReport verifiedRosterByAnyOf(Collection<? extends PublicKey> mojangKeys,
            Instant now, BiConsumer<PublicKey, GeneralSecurityException> unusable,
            Predicate<Attestation.Credential> operatorTrusts) throws IOException {
        if (now == null) {
            return null;
        }
        long at = now.toEpochMilli();
        SignedBatch held = accepted(mojangKeys, at, unusable, operatorTrusts);
        return held == null ? null : held.attestedRoster(at);
    }

    private SignedBatch accepted(Collection<? extends PublicKey> mojangKeys,
            long at, BiConsumer<PublicKey, GeneralSecurityException> unusable,
            Predicate<Attestation.Credential> operatorTrusts) {
        if (!Attestation.current(credential, at)) {
            return null;
        }
        Attestation.Credential held = new Attestation.Credential(credential.player(),
                credential.expiresAt(), credential.publicKey().clone(),
                credential.keySignature().clone());
        Attestation.Seen seen = new Attestation.Seen();
        boolean mojangFirst = operatorTrusts == null
                || sizedForMojang(mojangKeys, held.keySignature().length);
        boolean mojangVouched;
        boolean operatorVouched;
        if (mojangFirst) {
            mojangVouched = mojangVouches(mojangKeys, held, unusable, seen);
            operatorVouched = mojangVouched ? false : operatorVouches(operatorTrusts, held);
        } else {
            operatorVouched = operatorVouches(operatorTrusts, held);
            mojangVouched = operatorVouched && unusable == null
                    ? false
                    : mojangVouches(mojangKeys, held, unusable, seen);
        }
        boolean vouchedFor = mojangVouched || operatorVouched;
        SignedBatch signed = null;
        if (vouchedFor) {
            SignedBatch candidate = new SignedBatch(held, signature.clone(), batch.clone());
            if (Attestation.signedByPlayer(held, candidate.batch, candidate.signature, seen)) {
                signed = candidate;
            }
        }
        return signed;
    }

    private static boolean mojangVouches(Collection<? extends PublicKey> mojangKeys,
            Attestation.Credential held,
            BiConsumer<PublicKey, GeneralSecurityException> unusable,
            Attestation.Seen seen) {
        return Attestation.vouchingKey(mojangKeys, held, unusable, seen) != null;
    }

    private static boolean operatorVouches(
            Predicate<Attestation.Credential> operatorTrusts,
            Attestation.Credential held) {
        return operatorTrusts != null && operatorTrusts.test(held);
    }

    private static boolean sizedForMojang(Collection<? extends PublicKey> mojangKeys,
            int signatureBytes) {
        boolean sized = false;
        if (mojangKeys != null) {
            Iterator<? extends PublicKey> keys = mojangKeys.iterator();
            while (!sized && keys.hasNext()) {
                PublicKey key = keys.next();
                if (key instanceof RSAPublicKey rsa) {
                    BigInteger modulus = rsa.getModulus();
                    if (modulus != null
                            && (modulus.bitLength() + Byte.SIZE - 1) / Byte.SIZE
                            == signatureBytes) {
                        sized = true;
                    }
                }
            }
        }
        return sized;
    }

    private Batch attested(long at) throws IOException {
        long skew = skewBound(at);
        long stale = at - GRACE_MILLIS;
        UUID player = credential.player();
        return Batch.decode(batch, (by, sent) -> matches(player, by)
                && withinBounds(sent, skew, stale), skew);
    }

    // The envelope opened as an answer to "prove you stood here".
    public WorldProof verifiedProofByAnyOf(Collection<? extends PublicKey> mojangKeys,
            Instant now, BiConsumer<PublicKey, GeneralSecurityException> unusable,
            Predicate<Attestation.Credential> operatorTrusts) throws IOException {
        if (now == null) {
            return null;
        }
        long at = now.toEpochMilli();
        SignedBatch held = accepted(mojangKeys, at, unusable, operatorTrusts);
        return held == null ? null : held.attestedProof(at);
    }

    // A proof, with a window measured in hours rather than minutes.
    private WorldProof attestedProof(long at) throws IOException {
        WorldProof inside = WorldProof.decode(batch);
        if (!matches(credential.player(), inside.by())) {
            return null;
        }
        long skew = skewBound(at);
        long stale = at - GRACE_MILLIS;
        if (!withinBounds(inside.sent(), skew, stale)) {
            return null;
        }
        return inside;
    }

    private Presence attestedPresence(long at) throws IOException {
        Presence inside = Presence.decode(batch);
        return withinPresenceWindow(at, inside.by(), inside.sent()) ? inside : null;
    }

    private RosterReport attestedRoster(long at) throws IOException {
        RosterReport inside = RosterReport.decode(batch);
        return withinPresenceWindow(at, inside.by(), inside.sent()) ? inside : null;
    }

    private boolean withinPresenceWindow(long at, String by, long sent) {
        if (!matches(credential.player(), by)) {
            return false;
        }
        return inPresenceWindow(at, sent);
    }

    // Package-private: StandingAsk.current reads this constant directly.
    static final long PRESENCE_WINDOW_SECONDS = 300;

    static long skewBound(long at) {
        return at + PRESENCE_WINDOW_SECONDS * MILLIS_PER_SECOND;
    }

    static boolean withinBounds(long sent, long skew, long stale) {
        return sent <= skew && sent >= stale;
    }

    static boolean inPresenceWindow(long at, long sent) {
        return withinBounds(sent, skewBound(at), at - PRESENCE_WINDOW_SECONDS * MILLIS_PER_SECOND);
    }

    private static boolean matches(UUID player, String by) {
        return WorldProof.matchesAccountText(player, by);
    }

    public UUID player() {
        return credential.player();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SignedBatch that
                && Arrays.equals(signature, that.signature)
                && Arrays.equals(batch, that.batch)
                && credential.equals(that.credential);
    }

    @Override
    public int hashCode() {
        int hash = credential.hashCode();
        hash = HASH_MULTIPLIER * hash + Arrays.hashCode(signature);
        return HASH_MULTIPLIER * hash + Arrays.hashCode(batch);
    }

    public static int encodedLength(Attestation.Credential credential, byte[] signature,
            int batchLength) {
        int total = Integer.BYTES + Long.BYTES + Long.BYTES + Long.BYTES;
        total = Math.addExact(total, Math.addExact(Integer.BYTES, credential.publicKey().length));
        total = Math.addExact(total, Math.addExact(Integer.BYTES, credential.keySignature().length));
        total = Math.addExact(total, Math.addExact(Integer.BYTES, signature.length));
        return Math.addExact(total, Math.addExact(Integer.BYTES, batchLength));
    }

    private static void writeEnvelope(byte[] message, Attestation.Credential credential,
            byte[] signature, byte[] batch, int batchLength) {
        int at = WireWrite.putInt(message, 0, MAGIC);
        at = WireWrite.putLong(message, at, credential.player().getMostSignificantBits());
        at = WireWrite.putLong(message, at, credential.player().getLeastSignificantBits());
        at = WireWrite.putLong(message, at, credential.expiresAt().toEpochMilli());
        at = write(message, at, credential.publicKey(), credential.publicKey().length);
        at = write(message, at, credential.keySignature(), credential.keySignature().length);
        at = write(message, at, signature, signature.length);
        write(message, at, batch, batchLength);
    }

    private static int write(byte[] message, int at, byte[] value, int length) {
        at = WireWrite.putInt(message, at, length);
        System.arraycopy(value, 0, message, at, length);
        return at + length;
    }
}
