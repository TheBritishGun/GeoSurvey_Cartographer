package dev.openmap.share;

import dev.openmap.map.LabelText;
import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// Who one client can see, as that client sees them.
public record RosterReport(
        String server,
        String dimension,
        String by,
        long sent,
        List<Entry> players) {

    // CRR1.
    public static final int MAGIC = 0x43525231;

    public static final int MAX_NAME = Presence.MAX_NAME;

    // Four times the 16-character Minecraft account name limit.
    public static final int MAX_PLAYER_NAME = 64;

    // Matches Roster.MAX_PLAYERS on the collector.
    public static final int MAX_PLAYERS = 512;

    // Longest a report may take on the wire.
    public static final int MAX_BYTES = 32768;

    private static final int SPECTATOR_BIT = 1;

    private static final int LOCATED_BIT = 2;

    private static final int BYTE_BYTES = 1;

    private static final int INT_BYTES = 4;

    private static final int LONG_BYTES = 8;

    private static final int UUID_BYTES = 16;

    private static final int UTF_LENGTH_BYTES = 2;

    private static final int HEADER_TEXT_FIELDS = 3;

    private static final int HEADER_FIXED_BYTES = INT_BYTES + LONG_BYTES + INT_BYTES
            + UTF_LENGTH_BYTES * HEADER_TEXT_FIELDS;

    private static final int MODIFIED_UTF_MAX_BYTES_PER_CHARACTER =
            Presence.MAX_UTF8_BYTES_PER_CHAR;

    private static final int MODIFIED_UTF_NUL_BYTES = 2;

    private static final int MODIFIED_UTF_TWO_BYTE_START = 0x0080;

    private static final int MODIFIED_UTF_THREE_BYTE_START = 0x0800;

    private static final int FOLD_HIGH_SHIFT = 32;

    private static final int FOLD_LOW_SHIFT = 20;

    private static final int INITIAL_DEDUPE_SLOTS = 16;

    private static final int DEDUPE_SLOT_MULTIPLIER = 2;

    private static final int ENTRY_BYTES_CEILING = UUID_BYTES + UTF_LENGTH_BYTES
            + MODIFIED_UTF_MAX_BYTES_PER_CHARACTER * MAX_PLAYER_NAME + BYTE_BYTES + LONG_BYTES;

    private static final ThreadLocal<SeenPlayers> DEDUPE_TABLE =
            ThreadLocal.withInitial(SeenPlayers::new);

    // x and z are meaningless unless located().
    public record Entry(UUID player, String name, boolean spectator,
                        boolean located, int x, int z) {

        public Entry {
            Objects.requireNonNull(player, "player");
            name = name == null ? "" : name;
            if (name.length() > MAX_PLAYER_NAME) {
                throw new IllegalArgumentException(
                        "player name is " + name.length() + " characters");
            }
            if (name.isBlank()) {
                throw new IllegalArgumentException("a roster entry with no name");
            }
            if (!located) {
                x = 0;
                z = 0;
            }
        }

        public static Entry at(UUID player, String name, boolean spectator,
                               int x, int z) {
            return new Entry(player, name, spectator, true, x, z);
        }

        public static Entry somewhere(UUID player, String name, boolean spectator) {
            return new Entry(player, name, spectator, false, 0, 0);
        }
    }

    public RosterReport {
        by = by == null ? "" : by;
        check(server, dimension, by, players);

        List<Entry> held = players instanceof Owned owned
                ? owned.held
                : new ArrayList<>(players);
        if (held.size() > MAX_PLAYERS) {
            throw new IllegalArgumentException(
                    held.size() + " players exceeds the " + MAX_PLAYERS + " limit");
        }
        if (!(players instanceof Owned owned && owned.deduped)) {
            distinct(held);
        }
        players = Collections.unmodifiableList(held);
    }

    private static void check(String server, String dimension, String by,
                              List<Entry> players) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(players, "players");
        requireClean(server, "server");
        require(server, "server");
        requireClean(dimension, "dimension");
        require(dimension, "dimension");
        requireClean(by, "contributor id");
        if (by.length() > MAX_NAME) {
            throw new IllegalArgumentException(
                    "contributor id is " + by.length() + " characters");
        }
        if (players.size() > MAX_PLAYERS) {
            throw new IllegalArgumentException(
                    players.size() + " players exceeds the " + MAX_PLAYERS + " limit");
        }
    }

    private static void distinct(List<Entry> held) {
        int size = held.size();
        SeenPlayers once = DEDUPE_TABLE.get();
        once.startOver();
        for (int which = 0; which < size; which++) {
            Entry entry = held.get(which);
            Objects.requireNonNull(entry, "entry");
            UUID player = entry.player();
            if (!once.accepts(player.getMostSignificantBits(),
                    player.getLeastSignificantBits())) {
                throw new IllegalArgumentException(
                        "the same player twice in one roster: " + entry.player());
            }
        }
    }

    public static RosterReport adopting(String server, String dimension, String by, long sent,
                                        List<Entry> owned) {
        Objects.requireNonNull(owned, "players");
        return new RosterReport(server, dimension, by, sent,
                new Owned(owned, false));
    }

    // Like adopting, but the caller already confirmed every entry names a different player.
    static RosterReport adoptingDeduped(String server, String dimension, String by, long sent,
                                        List<Entry> owned) {
        Objects.requireNonNull(owned, "players");
        return new RosterReport(server, dimension, by, sent,
                new Owned(owned, true));
    }

    public static List<Entry> withinByteCap(String server, String dimension, String by,
                                            long sent, List<Entry> players) {
        int entries = players.size();
        if (HEADER_FIXED_BYTES + (long) MODIFIED_UTF_MAX_BYTES_PER_CHARACTER
                * (server.length() + dimension.length() + by.length())
                + (long) ENTRY_BYTES_CEILING * entries <= MAX_BYTES) {
            return players;
        }
        int bytes = headerBytes(server, dimension, by);
        List<Entry> kept = null;
        for (int at = 0; at < entries; at++) {
            int each = entryBytes(players.get(at));
            if (bytes + each > MAX_BYTES) {
                kept = new ArrayList<>(players.subList(0, at));
                break;
            }
            bytes += each;
        }
        return kept == null ? players : kept;
    }

    private static int headerBytes(String server, String dimension, String by) {
        return INT_BYTES + utfBytes(server) + utfBytes(dimension) + utfBytes(by)
                + LONG_BYTES + INT_BYTES;
    }

    private static int entryBytes(Entry entry) {
        return UUID_BYTES + utfBytes(entry.name()) + BYTE_BYTES
                + (entry.located() ? LONG_BYTES : 0);
    }

    private static int utfBytes(String value) {
        return UTF_LENGTH_BYTES + nameBytes(value);
    }

    private static int nameBytes(String value) {
        int bytes = 0;
        for (int at = 0; at < value.length(); at++) {
            char character = value.charAt(at);
            bytes += character < MODIFIED_UTF_TWO_BYTE_START
                    ? (character == 0 ? MODIFIED_UTF_NUL_BYTES : BYTE_BYTES)
                    : character < MODIFIED_UTF_THREE_BYTE_START ? MODIFIED_UTF_NUL_BYTES
                    : MODIFIED_UTF_MAX_BYTES_PER_CHARACTER;
        }
        return bytes;
    }

    private static void require(String value, String what) {
        if (value.length() > MAX_NAME) {
            throw new IllegalArgumentException(
                    what + " is " + value.length() + " characters");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(what + " is blank");
        }
    }

    public static int encodeTo(byte[] room, String server, String dimension, String by,
                               long sent, List<Entry> players) throws IOException {
        Objects.requireNonNull(room, "room");
        String contributor = by == null ? "" : by;
        int total = checkedLength(server, dimension, contributor, players);
        Objects.checkFromIndexSize(0, total, room.length);
        write(room, server, dimension, contributor, sent, players);
        return total;
    }

    private static int checkedLength(String server, String dimension, String contributor,
                                     List<Entry> players) throws IOException {
        check(server, dimension, contributor, players);
        distinct(players);
        int total = headerBytes(server, dimension, contributor);
        int size = players.size();
        for (int which = 0; which < size; which++) {
            total += entryBytes(players.get(which));
        }
        if (total > MAX_BYTES) {
            throw new IOException("a roster of " + players.size() + " players is "
                    + total + " bytes, over the " + MAX_BYTES + " ceiling");
        }
        return total;
    }

    private static void write(byte[] message, String server, String dimension,
                              String contributor, long sent, List<Entry> players) {
        int size = players.size();
        int at = WireWrite.putInt(message, 0, MAGIC);
        at = WireWrite.putUtf(message, at, server);
        at = WireWrite.putUtf(message, at, dimension);
        at = WireWrite.putUtf(message, at, contributor);
        at = WireWrite.putLong(message, at, sent);
        at = WireWrite.putInt(message, at, size);
        for (int which = 0; which < size; which++) {
            Entry entry = players.get(which);
            at = WireWrite.putLong(message, at, entry.player().getMostSignificantBits());
            at = WireWrite.putLong(message, at, entry.player().getLeastSignificantBits());
            at = WireWrite.putUtf(message, at, entry.name());
            boolean located = entry.located();
            int flags = 0;
            if (entry.spectator()) {
                flags |= SPECTATOR_BIT;
            }
            if (located) {
                flags |= LOCATED_BIT;
            }
            message[at++] = (byte) flags;
            if (located) {
                at = WireWrite.putInt(message, at, entry.x());
                at = WireWrite.putInt(message, at, entry.z());
            }
        }
    }

    public static String cleanedName(String raw) {
        return LabelText.clean(raw, LabelText.UNBOUNDED_READ, LabelText.UNBOUNDED_READ, false);
    }

    public static RosterReport decode(byte[] message) throws IOException {
        Objects.requireNonNull(message, "message");
        if (message.length > MAX_BYTES) {
            throw new IOException("roster is " + message.length + " bytes");
        }
        WireCursor body = new WireCursor(message);
        RosterReport decoded;
        try {
            if (body.readInt() != MAGIC) {
                throw new IOException("not a roster report");
            }
            String server = name(body.readUtf("server", Presence.MAX_UTF8_BYTES_PER_CHAR * MAX_NAME), "server");
            String dimension = name(body.readUtf("dimension", Presence.MAX_UTF8_BYTES_PER_CHAR * MAX_NAME),
                    "dimension");
            String by = body.readUtf("by", Presence.MAX_UTF8_BYTES_PER_CHAR * MAX_NAME);
            requireClean(by, "contributor id");
            if (by.length() > MAX_NAME) {
                throw new IOException("contributor id is " + by.length()
                        + " characters");
            }
            long sent = body.readLong();
            int count = body.readInt();
            if (count < 0 || count > MAX_PLAYERS) {
                throw new IOException("roster claims " + count + " players");
            }
            List<Entry> players = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                UUID player = new UUID(body.readLong(), body.readLong());
                String raw = body.readUtf("player", Presence.MAX_UTF8_BYTES_PER_CHAR * MAX_PLAYER_NAME);
                String seen = cleanedName(raw);
                if (seen.length() > MAX_PLAYER_NAME) {
                    throw new IOException("player " + i + " is named with "
                            + seen.length() + " characters");
                }
                int flags = body.readUnsignedByte();
                if ((flags & ~(SPECTATOR_BIT | LOCATED_BIT)) != 0) {
                    throw new IOException("player " + i + " carries flags this"
                            + " reader refuses.");
                }
                boolean spectator = (flags & SPECTATOR_BIT) != 0;
                boolean located = (flags & LOCATED_BIT) != 0;
                int x = located ? body.readInt() : 0;
                int z = located ? body.readInt() : 0;
                if (!seen.isBlank()) {
                    players.add(located
                            ? Entry.at(player, seen, spectator, x, z)
                            : Entry.somewhere(player, seen, spectator));
                }
            }
            int left = body.remaining();
            if (left != 0) {
                throw new IOException(left
                        + " bytes after the roster."
                        + " Refusing it.");
            }
            decoded = adopting(server, dimension, by, sent, players);
        } catch (IllegalArgumentException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
        return decoded;
    }

    private static void requireClean(String value, String what) {
        if (!value.equals(LabelText.clean(value, LabelText.UNBOUNDED_READ,
                LabelText.UNBOUNDED_READ, false))) {
            throw new IllegalArgumentException(what + " has a character this reader"
                    + " rejects.");
        }
    }

    private static String name(String value, String what) throws IOException {
        requireClean(value, what);
        if (value.length() > MAX_NAME) {
            throw new IOException(what + " is " + value.length() + " characters");
        }
        if (value.isBlank()) {
            throw new IOException(what + " is blank");
        }
        return value;
    }

    private static final class SeenPlayers {

        private static final int SLOTS = slotsFor(MAX_PLAYERS);

        private static final int MASK = SLOTS - 1;

        private static final long GOLDEN = 0x9E3779B97F4A7C15L;

        private final long[] high = new long[SLOTS];

        private final long[] low = new long[SLOTS];

        private final int[] stamp = new int[SLOTS];

        private int generation;

        void startOver() {
            generation++;
            if (generation == 0) {
                java.util.Arrays.fill(stamp, 0);
                generation = 1;
            }
        }

        boolean accepts(long msb, long lsb) {
            int at = ((int) fold(msb, lsb)) & MASK;
            boolean accepted = true;
            while (stamp[at] == generation && accepted) {
                if (high[at] == msb && low[at] == lsb) {
                    accepted = false;
                } else {
                    at = (at + 1) & MASK;
                }
            }
            if (accepted) {
                stamp[at] = generation;
                high[at] = msb;
                low[at] = lsb;
            }
            return accepted;
        }

        private static long fold(long msb, long lsb) {
            long mixed = msb * GOLDEN + (lsb ^ (lsb >>> FOLD_HIGH_SHIFT));
            return mixed ^ (mixed >>> FOLD_LOW_SHIFT);
        }

        private static int slotsFor(int players) {
            int slots = INITIAL_DEDUPE_SLOTS;
            while (slots < players * DEDUPE_SLOT_MULTIPLIER) {
                slots = slots << 1;
            }
            return slots;
        }
    }

    private static final class Owned extends AbstractList<Entry> {

        private final List<Entry> held;

        private final boolean deduped;

        Owned(List<Entry> held, boolean deduped) {
            this.held = held;
            this.deduped = deduped;
        }

        @Override
        public Entry get(int at) {
            return held.get(at);
        }

        @Override
        public int size() {
            return held.size();
        }
    }
}
