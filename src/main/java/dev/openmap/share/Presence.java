package dev.openmap.share;

import dev.openmap.map.LabelText;
import java.io.IOException;
import java.util.Objects;

public record Presence(
        String server,
        String dimension,
        String by,
        String name,
        long sent,
        int x,
        int z,
        int ticks,
        boolean storm,
        boolean thunder) {

    public static final int MAGIC = 0x43525031;

    public static final int MAX_NAME = 256;

    public static final int MAX_BYTES = 2048;

    public static final int DAY_TICKS = 24000;

    static final int MAX_UTF8_BYTES_PER_CHAR = 3;

    private static final int FIXED_BYTES = 34;

    public static final class Parts {

        public String server;

        public String dimension;

        public String by;

        public String name;

        public long sent;

        public int x;

        public int z;

        public int ticks;

        public boolean storm;

        public boolean thunder;
    }

    private static final ThreadLocal<Parts> OWN_PARTS = ThreadLocal.withInitial(Parts::new);

    public Presence {
        by = orEmpty(by);
        name = orEmpty(name);
        check(server, dimension, by, name);
        ticks = onTheDay(ticks);
    }

    private static void check(String server, String dimension, String by, String name) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(dimension, "dimension");
        identifier(server, "server");
        identifier(dimension, "dimension");
        clean(by, "contributor id");
        bounded(by, "contributor id", MAX_NAME);
        bounded(name, "contributor name", RosterReport.MAX_PLAYER_NAME);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int onTheDay(int ticks) {
        return ticks >= 0 && ticks < DAY_TICKS ? ticks : Math.floorMod(ticks, DAY_TICKS);
    }

    private static String identifier(String value, String what) {
        clean(value, what);
        bounded(value, what, MAX_NAME);
        if (value.isBlank()) {
            throw new IllegalArgumentException(what + " is blank");
        }
        return value;
    }

    private static void bounded(String value, String what, int maximum) {
        if (value.length() > maximum) {
            throw new IllegalArgumentException(
                    what + " is " + value.length() + " characters");
        }
    }

    static int encodedLength(String server, String dimension, String by, String name) {
        return FIXED_BYTES + WireWrite.modifiedUtfLength(server)
                + WireWrite.modifiedUtfLength(dimension) + WireWrite.modifiedUtfLength(by)
                + WireWrite.modifiedUtfLength(name);
    }

    public static byte[] encodeInto(byte[] reuse, Parts parts) throws IOException {
        String contributorId = orEmpty(parts.by);
        String contributorName = orEmpty(parts.name);
        check(parts.server, parts.dimension, contributorId, contributorName);
        int size = encodedLength(parts.server, parts.dimension, contributorId, contributorName);
        if (size > MAX_BYTES) {
            throw new IOException("a presence report is " + size
                    + " bytes, over the " + MAX_BYTES + " ceiling");
        }
        byte[] message = reuse != null && reuse.length == size ? reuse : new byte[size];
        int at = WireWrite.putInt(message, 0, MAGIC);
        at = WireWrite.writeUtf(message, at, parts.server, WireWrite.modifiedUtfLength(parts.server));
        at = WireWrite.writeUtf(message, at, parts.dimension,
                WireWrite.modifiedUtfLength(parts.dimension));
        at = WireWrite.writeUtf(message, at, contributorId,
                WireWrite.modifiedUtfLength(contributorId));
        at = WireWrite.writeUtf(message, at, contributorName,
                WireWrite.modifiedUtfLength(contributorName));
        at = WireWrite.putLong(message, at, parts.sent);
        at = WireWrite.putInt(message, at, parts.x);
        at = WireWrite.putInt(message, at, parts.z);
        at = WireWrite.putInt(message, at, onTheDay(parts.ticks));
        message[at++] = (byte) (parts.storm ? 1 : 0);
        message[at] = (byte) (parts.thunder ? 1 : 0);
        return message;
    }

    public byte[] encode() throws IOException {
        return encodeInto(null);
    }

    public byte[] encodeInto(byte[] reuse) throws IOException {
        Parts parts = OWN_PARTS.get();
        parts.server = server;
        parts.dimension = dimension;
        parts.by = by;
        parts.name = name;
        parts.sent = sent;
        parts.x = x;
        parts.z = z;
        parts.ticks = ticks;
        parts.storm = storm;
        parts.thunder = thunder;
        return encodeInto(reuse, parts);
    }

    public static Presence decode(byte[] message) throws IOException {
        Objects.requireNonNull(message, "message");
        if (message.length > MAX_BYTES) {
            throw new IOException("presence is " + message.length + " bytes");
        }
        WireCursor body = new WireCursor(message);
        Presence decoded;
        try {
            if (body.readInt() != MAGIC) {
                throw new IOException("not a presence report");
            }
            String server = identifier(body.readUtf("server",
                    MAX_UTF8_BYTES_PER_CHAR * MAX_NAME), "server");
            String dimension = identifier(body.readUtf("dimension",
                    MAX_UTF8_BYTES_PER_CHAR * MAX_NAME), "dimension");
            String by = body.readUtf("by", MAX_UTF8_BYTES_PER_CHAR * MAX_NAME);
            clean(by, "contributor id");
            bounded(by, "contributor id", MAX_NAME);
            String name = drawn(body.readUtf("name",
                    MAX_UTF8_BYTES_PER_CHAR * RosterReport.MAX_PLAYER_NAME));
            bounded(name, "contributor name", RosterReport.MAX_PLAYER_NAME);
            long sent = body.readLong();
            int x = body.readInt();
            int z = body.readInt();
            int ticks = body.readInt();
            boolean storm = body.readBoolean();
            boolean thunder = body.readBoolean();
            int left = body.remaining();
            if (left != 0) {
                throw new IOException(left
                        + " bytes after the report."
                        + " Refusing it.");
            }
            decoded = new Presence(server, dimension, by, name, sent, x, z, ticks,
                    storm, thunder);
        } catch (IllegalArgumentException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
        return decoded;
    }

    private static void clean(String value, String what) {
        if (!value.equals(LabelText.clean(value, LabelText.UNBOUNDED_READ,
                LabelText.UNBOUNDED_READ, false))) {
            throw new IllegalArgumentException(what + " has a character this reader"
                    + " rejects.");
        }
    }

    private static String drawn(String value) {
        return RosterReport.cleanedName(value);
    }

}
