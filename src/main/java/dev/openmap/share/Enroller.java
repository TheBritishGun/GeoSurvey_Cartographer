package dev.openmap.share;

import dev.openmap.map.LabelText;
import java.io.IOException;

// The contributor's half of the session handshake, with the game taken out.
public final class Enroller {

    private static final int HTTP_OK = 200;

    private static final int HTTP_MULTIPLE_CHOICES = 300;

    private static final int HTTP_ACCEPTED = 202;

    private static final int HTTP_NOT_FOUND = 404;

    private static final int HTTP_BAD_REQUEST = 400;

    private static final int HTTP_REQUEST_TIMEOUT = 408;

    private static final int HTTP_MISDIRECTED_REQUEST = 421;

    private static final int HTTP_LOCKED = 423;

    private static final int HTTP_FAILED_DEPENDENCY = 424;

    private static final int HTTP_TOO_EARLY = 425;

    public static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final int HTTP_SERVER_ERROR = 500;

    // The collector, reduced to the two things this exchange asks of it.
    public interface Wire {

        // The challenge, or null if this collector does not offer the road.
        String challenge() throws IOException;

        // Posts the signed enrolment, and returns the status.
        int offer(byte[] enrolment) throws IOException;
    }

    // The player's Mojang session, reduced the same way.
    public interface Session {

        // The name the session service will be asked to look up.
        String name();

        // Tells Mojang this account joined that serverId.
        boolean join(String serverId) throws IOException;
    }

    // The local identity key, reduced to the one thing wanted from it.
    public interface Sealer {

        byte[] sign(byte[] payload) throws IOException;
    }

    public enum Outcome {

        // The collector recorded the key. Nothing more to do for this address.
        ENROLLED,

        // The collector will not record it however long we wait.
        REFUSED,

        // Something that could be different next time.
        RETRY,

        // This collector does not offer the road at all.
        UNROUTED
    }

    private Enroller() {
    }

    private record Memo(Attestation.Credential credential, String fingerprint) {
    }

    private static volatile Memo memoised;

    // One attempt, start to finish.
    public static Outcome once(Wire wire, Session session,
                               Attestation.Credential credential, Sealer sealer)
            throws IOException {
        String name = session.name();
        Outcome outcome;
        if (name == null) {

            // null means a timeout, an interrupted wait, or no client thread to ask.
            outcome = Outcome.RETRY;
        } else {
            name = LabelText.clean(name, LabelText.UNBOUNDED_READ,
                    LabelText.UNBOUNDED_READ, false);
            if (name.length() > Enrolment.MAX_NAME || name.isBlank()) {
                outcome = Outcome.REFUSED;
            } else {
                String challenge = wire.challenge();
                if (challenge == null) {
                    outcome = Outcome.UNROUTED;
                } else if (!SessionProof.readable(challenge)) {

                    outcome = Outcome.RETRY;
                } else if (!session.join(SessionProof.serverId(challenge,
                        fingerprint(credential)))) {
                    outcome = Outcome.RETRY;
                } else {
                    byte[] payload = Enrolment.encode(challenge, name);
                    byte[] body = new SignedBatch(credential, sealer.sign(payload), payload)
                            .encode();
                    outcome = read(wire.offer(body));
                }
            }
        }
        return outcome;
    }

    private static String fingerprint(Attestation.Credential credential) {
        Memo known = memoised;
        if (known != null && known.credential() == credential) {
            return known.fingerprint();
        }
        Memo computed = new Memo(credential, Attestation.fingerprint(credential));
        memoised = computed;
        return computed.fingerprint();
    }

    private static Outcome read(int status) {
        if (status >= HTTP_OK && status < HTTP_MULTIPLE_CHOICES) {
            return status == HTTP_ACCEPTED ? Outcome.RETRY : Outcome.ENROLLED;
        }
        if (status == HTTP_NOT_FOUND) {
            return Outcome.RETRY;
        }

        // 429 is grouped with the 5xx block, not the 4xx block.
        if (status == HTTP_TOO_MANY_REQUESTS) {
            return Outcome.RETRY;
        }
        if (status == HTTP_REQUEST_TIMEOUT || status == HTTP_MISDIRECTED_REQUEST
                || status == HTTP_LOCKED || status == HTTP_FAILED_DEPENDENCY
                || status == HTTP_TOO_EARLY) {
            return Outcome.RETRY;
        }
        return status >= HTTP_BAD_REQUEST && status < HTTP_SERVER_ERROR
                ? Outcome.REFUSED : Outcome.RETRY;
    }
}
