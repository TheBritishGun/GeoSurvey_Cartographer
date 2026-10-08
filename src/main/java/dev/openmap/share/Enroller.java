package dev.openmap.share;

import dev.openmap.map.LabelText;
import java.io.IOException;

// The contributor's side of the handshake.
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

    private static final int HTTP_UNAUTHORIZED = 401;

    // Challenge and answer pairs per attempt.
    private static final int MAX_PAIRS = 3;

    // The collector's 401 text.
    private static final String NOT_ISSUED = "not an answer to a challenge this collector issued";

    // The collector's 401 text.
    private static final String SESSION_DENIED = "the session service did not confirm that";

    // The collector's side of the handshake.
    public interface Wire {

        // Null if the road is not offered.
        String challenge() throws IOException;

        // Returns the HTTP status.
        int offer(byte[] enrolment) throws IOException;

        // The last offer's reply text; empty if unread.
        default String offerReply() {
            return "";
        }
    }

    // The player's Mojang session.
    public interface Session {

        // Null on timeout, interrupt, or missing client thread.
        String name();

        // Tells Mojang this account joined.
        boolean join(String serverId) throws IOException;
    }

    // The local identity key.
    public interface Sealer {

        byte[] sign(byte[] payload) throws IOException;
    }

    public enum Outcome {

        ENROLLED,

        // Waiting will not help.
        REFUSED,

        RETRY,

        // This collector does not offer the road.
        UNROUTED
    }

    private Enroller() {
    }

    private record Memo(Attestation.Credential credential, String fingerprint) {
    }

    private static volatile Memo memoised;

    public static Outcome once(Wire wire, Session session,
                               Attestation.Credential credential, Sealer sealer)
            throws IOException {
        String name = session.name();
        Outcome outcome;
        if (name == null) {

            outcome = Outcome.RETRY;
        } else {
            name = LabelText.clean(name, LabelText.UNBOUNDED_READ,
                    LabelText.UNBOUNDED_READ, false);
            if (name.length() > Enrolment.MAX_NAME || name.isBlank()) {
                outcome = Outcome.REFUSED;
            } else {
                outcome = null;
                int sent = 0;
                while (outcome == null) {
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
                        int status = wire.offer(body);
                        sent++;
                        if (notIssued(status, wire)) {
                            outcome = sent < MAX_PAIRS ? null : Outcome.RETRY;
                        } else {
                            outcome = read(status);
                        }
                    }
                }
            }
        }
        return outcome;
    }

    public static boolean deniedBySession(String offerReply) {
        return SESSION_DENIED.equals(offerReply);
    }

    private static boolean notIssued(int status, Wire wire) {
        return status == HTTP_UNAUTHORIZED && NOT_ISSUED.equals(wire.offerReply());
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
