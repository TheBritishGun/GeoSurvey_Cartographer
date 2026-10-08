package dev.openmap.share;

import dev.openmap.map.LabelText;
import java.io.IOException;
import java.util.Objects;

// A contributor's request to record its key.
public record Enrolment(String challenge, String name) {

    public static final int MAGIC = 0x43524531;

    public static final int MAX_NAME = RosterReport.MAX_PLAYER_NAME;

    public static final int MAX_BYTES = 512;

    private static final int FIXED_BYTES = 8;

    public Enrolment {
        Objects.requireNonNull(challenge, "challenge");
        Objects.requireNonNull(name, "name");
        if (!SessionProof.readable(challenge)) {
            throw new IllegalArgumentException("the challenge is not "
                    + SessionProof.CHALLENGE_CHARACTERS + " hex characters");
        }
        name = LabelText.clean(name, LabelText.UNBOUNDED_READ,
                LabelText.UNBOUNDED_READ, false);
        if (name.length() > MAX_NAME) {
            throw new IllegalArgumentException("the player name is " + name.length()
                    + " characters");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("the player name is blank");
        }
    }

    public byte[] encode() throws IOException {
        return encode(challenge, name);
    }

    // The same bytes; the caller already checked both fields.
    static byte[] encode(String challenge, String name) throws IOException {
        int challengeLength = WireWrite.modifiedUtfLength(challenge);
        int nameLength = WireWrite.modifiedUtfLength(name);
        byte[] message = new byte[FIXED_BYTES + challengeLength + nameLength];
        int at = WireWrite.putInt(message, 0, MAGIC);
        at = WireWrite.writeUtf(message, at, challenge, challengeLength);
        WireWrite.writeUtf(message, at, name, nameLength);
        return message;
    }

    public static Enrolment decode(byte[] message) throws IOException {
        Objects.requireNonNull(message, "message");
        if (message.length > MAX_BYTES) {
            throw new IOException("enrolment is " + message.length + " bytes");
        }
        WireCursor body = new WireCursor(message);
        Enrolment enrolment;
        try {
            if (body.readInt() != MAGIC) {
                throw new IOException("not an enrolment");
            }
            String challenge = body.readUtf("challenge", SessionProof.CHALLENGE_CHARACTERS);
            String name = body.readUtf("name",
                    Presence.MAX_UTF8_BYTES_PER_CHAR * MAX_NAME);
            int left = body.remaining();
            if (left != 0) {
                throw new IOException(left + " extra bytes"
                        + " after the enrolment.");
            }
            enrolment = new Enrolment(challenge, name);
        } catch (IllegalArgumentException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
        return enrolment;
    }

}
