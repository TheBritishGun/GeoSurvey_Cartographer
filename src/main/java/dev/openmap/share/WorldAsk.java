package dev.openmap.share;

import dev.openmap.json.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WorldAsk {

    public static final String PATH = "/fingerprint";

    public static final int VERSION = 1;

    public static final int MAX_BODY = Directory.MAX_BODY;

    public static final int MAX_REGIONS = 32;

    // Bounded by the maximum render distance.
    public static final int MAX_REGION_SPAN = 2_048;

    private WorldAsk() {
    }

    private static final long NO_INTEGER = Long.MIN_VALUE;

    private static final int NESTED = 3;

    private static final int SCRATCH = 4;

    private static final int SCRATCH_GROWTH = 2;

    private static final int PAIR = 2;

    private static final int DECIMAL = 10;

    private static final int INT_DIGITS = Integer.toString(Integer.MAX_VALUE).length();

    private static final int UNSIGNED_BYTE_MASK = 0xFF;

    private static final int UTF8_ONE_BYTE_LIMIT = 0x80;

    private static final int UTF8_CONTINUATION_MASK = 0xC0;

    private static final int UTF8_CONTINUATION_TAG = 0x80;

    private static final int UTF8_CONTINUATION_BITS = 6;

    private static final int UTF8_CONTINUATION_PAYLOAD = 0x3F;

    private static final int UTF8_TWO_BYTE_MASK = 0xE0;

    private static final int UTF8_TWO_BYTE_TAG = 0xC0;

    private static final int UTF8_TWO_BYTE_PAYLOAD = 0x1F;

    private static final int UTF8_THREE_BYTE_MASK = 0xF0;

    private static final int UTF8_THREE_BYTE_TAG = 0xE0;

    private static final int UTF8_THREE_BYTE_PAYLOAD = 0x0F;

    private static final int UTF8_FOUR_BYTE_MASK = 0xF8;

    private static final int UTF8_FOUR_BYTE_TAG = 0xF0;

    private static final int UTF8_FOUR_BYTE_PAYLOAD = 0x07;

    private static final int UTF8_TWO_BYTES = 2;

    private static final int UTF8_THREE_BYTES = 3;

    private static final int UTF8_FOUR_BYTES = 4;

    private static final int FIRST_TWO_BYTE_POINT = 0x80;

    private static final int FIRST_THREE_BYTE_POINT = 0x800;

    private static final int FIRST_FOUR_BYTE_POINT = 0x10000;

    private static final int NOT_DECODED = -1;

    public static boolean readable(WorldPrint.Region region) {
        if (region == null) {
            return false;
        }
        long wide = (long) region.maxX() - (long) region.minX() + 1L;
        long tall = (long) region.maxZ() - (long) region.minZ() + 1L;
        return wide >= 1L && tall >= 1L
                && wide <= MAX_REGION_SPAN && tall <= MAX_REGION_SPAN;
    }

    public static boolean probesBedrock(String dimension) {
        return WorldPrint.sameDimension(FLOORED_OVERWORLD, dimension)
                || WorldPrint.sameDimension(FLOORED_NETHER, dimension);
    }

    private static final String FLOORED_OVERWORLD = "minecraft:overworld";

    private static final String FLOORED_NETHER = "minecraft:the_nether";

    public record Ask(boolean understood, List<WorldPrint.Region> regions,
                      boolean refusedRow) {
    }

    public static final class AnswerHolder {

        private final Reply said = new Reply(null, new String[MAX_REGIONS]);

        private final StringBuilder text = new StringBuilder();

        private boolean accepted = false;

        private boolean refused = false;

        private boolean listed = false;

        private int count = 0;

        private List<String> kept = List.of();

        public boolean accepted() {
            return accepted;
        }

        // The reply says accepted is false.
        public boolean refused() {
            return refused;
        }

        public boolean listed() {
            return listed;
        }

        public List<String> names() {
            if (!holds(kept)) {
                kept = frozen(said.foundNames, count);
            }
            return kept;
        }

        private boolean holds(List<String> names) {
            boolean same = names.size() == count;
            for (int i = 0; same && (i < count); i++) {
                same = said.foundNames[i].equals(names.get(i));
            }
            return same;
        }

        private void fill(CharSequence body) {
            Reply reply = said;
            reply.clear();
            boolean read = parsed(body, reply);
            accepted = read && reply.accepted;
            refused = read && reply.refusedAnswer;
            listed = accepted && reply.namesListed && (reply.seenNames <= MAX_REGIONS)
                    && !reply.refusedName;
            count = listed ? reply.keptNames : 0;
        }
    }

    public static final class AskHolder {

        private final Reply said = new Reply(new WorldPrint.Region[MAX_REGIONS]);

        private final StringBuilder text = new StringBuilder();

        private boolean understood = false;

        private boolean refusedRow = false;

        private boolean regionsCut = false;

        private List<String> refusedNames = List.of();

        private int count = 0;

        public boolean understood() {
            return understood;
        }

        public boolean refusedRow() {
            return refusedRow;
        }

        // More than MAX_REGIONS rows listed.
        public boolean regionsCut() {
            return regionsCut;
        }

        // Refused row names; "" when unreadable.
        public List<String> refusedNames() {
            return refusedNames;
        }

        public int count() {
            return count;
        }

        public boolean holds(List<WorldPrint.Region> regions) {
            if (regions.size() != count) {
                return false;
            }
            boolean same = true;
            for (int i = 0; same && (i < count); i++) {
                same = said.keptRegions[i].equals(regions.get(i));
            }
            return same;
        }

        public List<WorldPrint.Region> regions() {
            return frozen(said.keptRegions, count);
        }

        private void fill(CharSequence body) {
            Reply reply = said;
            reply.clear();
            boolean listed = read(body, reply) && reply.regionsListed;
            understood = listed;
            refusedRow = listed && reply.refusedRow;
            regionsCut = listed && reply.regionsCut;
            refusedNames = listed ? frozen(reply.refusedNames, reply.refusedCount) : List.of();
            count = listed ? reply.keptRows : 0;
            reply.clear();
        }
    }

    public static Ask ask(String body) {
        Reply said = read(body);
        if (said == null || !said.regionsListed) {
            return new Ask(false, List.of(), false);
        }
        return new Ask(true, frozen(said.keptRegions, said.keptRows), said.refusedRow);
    }

    public static void ask(String body, AskHolder into) {
        into.fill(body);
    }

    public static void ask(byte[] body, int length, AskHolder into) {
        into.fill(text(body, length, into.text));
    }

    public static void answer(byte[] body, int length, AnswerHolder into) {
        into.fill(text(body, length, into.text));
    }

    static CharSequence text(byte[] bytes, int length, StringBuilder into) {
        into.setLength(0);
        if (!decoded(bytes, length, into)) {
            into.setLength(0);
            into.append(new String(bytes, 0, length, StandardCharsets.UTF_8));
        }
        int end = into.length();
        int start = 0;
        while (start < end && into.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && into.charAt(end - 1) <= ' ') {
            end--;
        }
        into.setLength(end);
        into.delete(0, start);
        return into;
    }

    private static boolean decoded(byte[] bytes, int length, StringBuilder into) {
        boolean wellFormed = true;
        int at = 0;
        while (wellFormed && at < length) {
            int lead = bytes[at] & UNSIGNED_BYTE_MASK;
            if (lead < UTF8_ONE_BYTE_LIMIT) {
                into.append((char) lead);
                at++;
            } else {
                int size = sequenceLength(lead);
                int point = size <= length - at ? codePoint(bytes, at, size) : NOT_DECODED;
                if (point == NOT_DECODED) {
                    wellFormed = false;
                } else {
                    into.appendCodePoint(point);
                    at += size;
                }
            }
        }
        return wellFormed;
    }

    private static int sequenceLength(int lead) {
        int size;
        if ((lead & UTF8_TWO_BYTE_MASK) == UTF8_TWO_BYTE_TAG) {
            size = UTF8_TWO_BYTES;
        } else if ((lead & UTF8_THREE_BYTE_MASK) == UTF8_THREE_BYTE_TAG) {
            size = UTF8_THREE_BYTES;
        } else if ((lead & UTF8_FOUR_BYTE_MASK) == UTF8_FOUR_BYTE_TAG) {
            size = UTF8_FOUR_BYTES;
        } else {
            size = Integer.MAX_VALUE;
        }
        return size;
    }

    private static int codePoint(byte[] bytes, int at, int size) {
        int lead = bytes[at] & UNSIGNED_BYTE_MASK;
        int point;
        int first;
        if (size == UTF8_TWO_BYTES) {
            point = lead & UTF8_TWO_BYTE_PAYLOAD;
            first = FIRST_TWO_BYTE_POINT;
        } else if (size == UTF8_THREE_BYTES) {
            point = lead & UTF8_THREE_BYTE_PAYLOAD;
            first = FIRST_THREE_BYTE_POINT;
        } else {
            point = lead & UTF8_FOUR_BYTE_PAYLOAD;
            first = FIRST_FOUR_BYTE_POINT;
        }
        boolean continued = true;
        for (int next = at + 1; continued && next < at + size; next++) {
            int follow = bytes[next] & UNSIGNED_BYTE_MASK;
            continued = (follow & UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_TAG;
            point = (point << UTF8_CONTINUATION_BITS) | (follow & UTF8_CONTINUATION_PAYLOAD);
        }
        boolean scalar = continued && point >= first && point <= Character.MAX_CODE_POINT
                && (point < Character.MIN_SURROGATE || point > Character.MAX_SURROGATE);
        return scalar ? point : NOT_DECODED;
    }

    private static boolean parsed(CharSequence body, Reply said) {
        if (body == null || body.length() > MAX_BODY || blank(body)) {
            return false;
        }
        boolean read = true;
        try {
            JsonParser.parseInto(body, said);
        } catch (RuntimeException notAnAnswer) {
            read = false;
        }
        return read;
    }

    private static boolean blank(CharSequence text) {
        boolean blank = true;
        int at = 0;
        while (blank && at < text.length()) {
            int point = Character.codePointAt(text, at);
            blank = Character.isWhitespace(point);
            at += Character.charCount(point);
        }
        return blank;
    }

    public static boolean understood(String body) {
        Reply said = read(body);
        return said != null && said.regionsListed;
    }

    public static List<WorldPrint.Region> regions(String body) {
        return ask(body).regions();
    }

    private static Reply read(String body) {
        Reply said = new Reply();
        return read(body, said) ? said : null;
    }

    private static boolean read(CharSequence body, Reply said) {
        if (body == null || body.length() > MAX_BODY || blank(body)) {
            return false;
        }
        boolean read = true;
        try {
            JsonParser.parseInto(body, said);
        } catch (RuntimeException notAFingerprintDocument) {
            read = false;
        }
        boolean understood = read && said.versionSaysOne;
        return understood;
    }

    private static <T> List<T> frozen(T[] seen, int count) {
        List<T> frozen;
        if (count == 0) {
            frozen = List.of();
        } else if (count == 1) {
            frozen = List.of(seen[0]);
        } else if (count == PAIR) {
            frozen = List.of(seen[0], seen[1]);
        } else {
            frozen = filled(seen, count);
        }
        return frozen;
    }

    private static <T> List<T> filled(T[] seen, int count) {
        List<T> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(seen[i]);
        }
        return List.copyOf(out);
    }

    // Null when the row is not a region.
    private static WorldPrint.Region region(Reply row) {
        String name = row.rowName;
        if (name == null || name.length() > WorldProof.MAX_NAME || name.isBlank()) {
            return null;
        }
        String dimension = row.rowDimension;
        if (dimension == null || dimension.length() > WorldProof.MAX_NAME
                || dimension.isBlank()) {
            return null;
        }
        long minX = row.minX;
        long minZ = row.minZ;
        long maxX = row.maxX;
        long maxZ = row.maxZ;
        if (minX == NO_INTEGER || minZ == NO_INTEGER || maxX == NO_INTEGER
                || maxZ == NO_INTEGER) {
            return null;
        }
        if (maxX < minX || maxZ < minZ) {
            return null;
        }
        long scheme = row.scheme;
        if (row.schemeListed && scheme == NO_INTEGER) {
            return null;
        }
        WorldPrint.Region asks = row.regionOf(name, dimension,
                row.schemeListed ? (int) scheme : WorldPrint.SCHEME_BEDROCK);

        return readable(asks) && asks.computable() && probesBedrock(asks.dimension())
                ? asks : null;
    }

    private static long integer(String text) {
        return inIntRange(Directory.exactIntegerOf(text));
    }

    private static long integer(CharSequence document, int start, int end) {
        return inIntRange(exactIntegerIn(document, start, end));
    }

    private static long inIntRange(long exact) {
        return ((exact < Integer.MIN_VALUE) || (exact > Integer.MAX_VALUE)) ? NO_INTEGER : exact;
    }

    private static long exactIntegerIn(CharSequence document, int start, int end) {
        int digits = ((start < end) && (document.charAt(start) == '-')) ? start + 1 : start;
        boolean plain = (end > digits) && (end - digits <= INT_DIGITS);
        for (int at = digits; plain && (at < end); at++) {
            char c = document.charAt(at);
            plain = (c >= '0') && (c <= '9');
        }
        return plain ? Long.parseLong(document, start, end, DECIMAL)
                : Directory.exactIntegerOf(document.subSequence(start, end).toString());
    }

    private static final class Reply implements JsonParser.Sink {

        private enum Where {
            NOWHERE, DOCUMENT, REGION_LIST, ROW, NAME_LIST, UNREAD
        }

        private enum Member {
            NONE, VERSION, ACCEPTED, REGIONS, REMAINING
        }

        private enum Field {
            NONE, NAME, DIMENSION, MIN_X, MIN_Z, MAX_X, MAX_Z, SCHEME
        }

        private final Where[] level = new Where[NESTED];

        private Member member = Member.NONE;

        private Field field = Field.NONE;

        private int depth;

        private int seenRows;

        private int keptRows;

        private int seenNames;

        private int keptNames;

        private boolean versionSaysOne;

        private boolean accepted;

        private boolean refusedAnswer = false;

        private boolean regionsListed;

        private boolean namesListed;

        private boolean refusedRow;

        private boolean regionsCut = false;

        private boolean refusedName;

        private WorldPrint.Region[] keptRegions;

        private String[] foundNames;

        // Null until a refusal.
        private String[] refusedNames = null;

        private int refusedCount = 0;

        private String rowName;

        private String rowDimension;

        private long minX = NO_INTEGER;

        private long minZ = NO_INTEGER;

        private long maxX = NO_INTEGER;

        private long maxZ = NO_INTEGER;

        private long scheme = NO_INTEGER;

        private boolean schemeListed;

        private Reply() {
            this(null, null);
        }

        private Reply(WorldPrint.Region[] room) {
            this(room, null);
        }

        private Reply(WorldPrint.Region[] room, String[] names) {
            keptRegions = room;
            foundNames = names;
        }

        @Override
        public void beginObject() {
            switch (here()) {
                case NOWHERE -> push(Where.DOCUMENT);
                case DOCUMENT -> {
                    memberIsContainer();
                    push(Where.UNREAD);
                }
                case REGION_LIST -> beginRow();
                case NAME_LIST -> {
                    refusedName = true;
                    push(Where.UNREAD);
                }
                case ROW -> {
                    fieldIsContainer();
                    push(Where.UNREAD);
                }
                default -> push(Where.UNREAD);
            }
        }

        @Override
        public void endObject() {
            if (here() == Where.ROW) {
                keepRow();
            }
            pop();
        }

        @Override
        public void beginArray() {
            switch (here()) {
                case DOCUMENT -> beginMemberArray();
                case REGION_LIST -> {
                    seenRows++;
                    refuseRow(null);
                    push(Where.UNREAD);
                }
                case NAME_LIST -> {
                    seenNames++;
                    refusedName = true;
                    push(Where.UNREAD);
                }
                case ROW -> {
                    fieldIsContainer();
                    push(Where.UNREAD);
                }
                default -> push(Where.UNREAD);
            }
        }

        @Override
        public void endArray() {
            pop();
        }

        @Override
        public void name(String stated) {
            switch (here()) {
                case DOCUMENT -> member = memberOf(stated);
                case ROW -> field = fieldOf(stated);
                default -> {
                }
            }
        }

        @Override
        public void stringValue(String text) {
            if ((here() == Where.ROW) && readsInteger()) {
                rowInteger(NO_INTEGER);
            } else {
                scalar(text);
            }
        }

        @Override
        public void numberValue(String token) {
            if ((here() == Where.DOCUMENT) && (member == Member.VERSION)) {
                versionSaysOne = WireVersion.is(token, VERSION);
            } else if (here() == Where.ROW) {
                if (readsInteger()) {
                    rowInteger(integer(token));
                } else {
                    rowValue(null);
                }
            } else {
                scalar(token);
            }
        }

        @Override
        public void numberValue(CharSequence document, int start, int end) {
            if ((here() == Where.DOCUMENT) && (member == Member.VERSION)) {
                versionSaysOne = WireVersion.is(document.subSequence(start, end), VERSION);
            } else if (here() == Where.ROW) {
                if (readsInteger()) {
                    rowInteger(integer(document, start, end));
                } else {
                    rowValue(null);
                }
            } else {
                scalar(document.subSequence(start, end).toString());
            }
        }

        @Override
        public void booleanValue(boolean flag) {
            scalar(Boolean.toString(flag));
        }

        @Override
        public void nullValue() {
            scalar(null);
        }

        private void scalar(String text) {
            switch (here()) {
                case DOCUMENT -> memberValue(text);
                case REGION_LIST -> {
                    seenRows++;
                    refuseRow(null);
                }
                case NAME_LIST -> nameValue(text);
                case ROW -> rowValue(text);
                default -> {
                }
            }
        }

        private void beginRow() {
            seenRows++;
            if (seenRows > MAX_REGIONS) {
                refuseRow(null);
                push(Where.UNREAD);
            } else {
                startRow();
                push(Where.ROW);
            }
        }

        private void startRow() {
            rowName = null;
            rowDimension = null;
            minX = NO_INTEGER;
            minZ = NO_INTEGER;
            maxX = NO_INTEGER;
            maxZ = NO_INTEGER;
            scheme = NO_INTEGER;
            schemeListed = false;
        }

        private void clear() {
            String[] names = foundNames;
            if (names != null) {
                Arrays.fill(names, 0, keptNames, null);
            }
            dropRefused();
            member = Member.NONE;
            field = Field.NONE;
            depth = 0;
            seenRows = 0;
            keptRows = 0;
            seenNames = 0;
            keptNames = 0;
            versionSaysOne = false;
            accepted = false;
            refusedAnswer = false;
            regionsListed = false;
            namesListed = false;
            refusedRow = false;
            regionsCut = false;
            refusedName = false;
            startRow();
        }

        private void keepRow() {
            WorldPrint.Region region = region(this);
            if (region == null) {
                refuseRow(rowName);
            } else {
                store(region);
            }
        }

        private void refuseRow(String stated) {
            refusedRow = true;
            if (seenRows > MAX_REGIONS) {
                regionsCut = true;
            } else {
                String[] room = refusedNames;
                if (room == null) {
                    room = new String[MAX_REGIONS];
                    refusedNames = room;
                }
                boolean named = stated != null && !stated.isBlank()
                        && stated.length() <= WorldProof.MAX_NAME;
                room[refusedCount++] = named ? stated : "";
            }
        }

        private void dropRefused() {
            String[] room = refusedNames;
            if (room != null) {
                Arrays.fill(room, 0, refusedCount, null);
            }
            refusedCount = 0;
        }

        private WorldPrint.Region regionOf(String name, String dimension, int kind) {
            int west = (int) minX;
            int north = (int) minZ;
            int east = (int) maxX;
            int south = (int) maxZ;
            WorldPrint.Region slot = heldSlot();
            boolean same = (slot != null) && slot.name().equals(name)
                    && slot.dimension().equals(dimension) && (slot.minX() == west)
                    && (slot.minZ() == north) && (slot.maxX() == east) && (slot.maxZ() == south)
                    && (slot.scheme() == kind);
            return same ? slot
                    : new WorldPrint.Region(name, dimension, west, north, east, south, kind);
        }

        private WorldPrint.Region heldSlot() {
            WorldPrint.Region[] room = keptRegions;
            if ((room == null) || (keptRows >= room.length)) {
                return null;
            }
            return room[keptRows];
        }

        private void store(WorldPrint.Region region) {
            WorldPrint.Region[] room = keptRegions;
            if (room == null) {
                room = new WorldPrint.Region[SCRATCH];
                keptRegions = room;
            }
            if (keptRows == room.length) {
                room = Arrays.copyOf(room, grown(room.length));
                keptRegions = room;
            }
            room[keptRows++] = region;
        }

        private void store(String name) {
            if (keptNames >= MAX_REGIONS) {
                return;
            }
            String[] room = foundNames;
            if (room == null) {
                room = new String[SCRATCH];
                foundNames = room;
            }
            if (keptNames == room.length) {
                room = Arrays.copyOf(room, grown(room.length));
                foundNames = room;
            }
            room[keptNames++] = name;
        }

        private static int grown(int length) {
            return Math.min(MAX_REGIONS, length * SCRATCH_GROWTH);
        }

        private void nameValue(String text) {
            seenNames++;
            if (text != null) {
                store(text);
            } else {
                refusedName = true;
            }
        }

        private void memberValue(String text) {
            switch (member) {
                case VERSION -> versionSaysOne = false;
                case ACCEPTED -> {
                    accepted = Boolean.parseBoolean(text);
                    refusedAnswer = text != null && !accepted;
                }
                case REGIONS -> regionsListed = false;
                case REMAINING -> namesListed = false;
                default -> {
                }
            }
        }

        private void memberIsContainer() {
            switch (member) {
                case VERSION -> versionSaysOne = false;
                case ACCEPTED -> {
                    accepted = false;
                    refusedAnswer = false;
                }
                case REGIONS -> regionsListed = false;
                case REMAINING -> namesListed = false;
                default -> {
                }
            }
        }

        private void beginMemberArray() {
            switch (member) {
                case VERSION -> {
                    versionSaysOne = false;
                    push(Where.UNREAD);
                }
                case ACCEPTED -> {
                    accepted = false;
                    refusedAnswer = false;
                    push(Where.UNREAD);
                }
                case REGIONS -> {
                    regionsListed = true;
                    seenRows = 0;
                    keptRows = 0;
                    refusedRow = false;
                    regionsCut = false;
                    dropRefused();
                    push(Where.REGION_LIST);
                }
                case REMAINING -> {
                    namesListed = true;
                    seenNames = 0;
                    keptNames = 0;
                    refusedName = false;
                    push(Where.NAME_LIST);
                }
                default -> push(Where.UNREAD);
            }
        }

        private void rowValue(String text) {
            switch (field) {
                case NAME -> rowName = text;
                case DIMENSION -> rowDimension = text;
                case MIN_X, MIN_Z, MAX_X, MAX_Z, SCHEME -> rowInteger(integer(text));
                default -> {
                }
            }
        }

        private boolean readsInteger() {
            return switch (field) {
                case MIN_X, MIN_Z, MAX_X, MAX_Z, SCHEME -> true;
                case NONE, NAME, DIMENSION -> false;
            };
        }

        private void rowInteger(long exact) {
            switch (field) {
                case MIN_X -> minX = exact;
                case MIN_Z -> minZ = exact;
                case MAX_X -> maxX = exact;
                case MAX_Z -> maxZ = exact;
                case SCHEME -> {
                    schemeListed = true;
                    scheme = exact;
                }
                case NONE, NAME, DIMENSION -> {
                }
            }
        }

        private void fieldIsContainer() {
            switch (field) {
                case NAME -> rowName = null;
                case DIMENSION -> rowDimension = null;
                case MIN_X -> minX = NO_INTEGER;
                case MIN_Z -> minZ = NO_INTEGER;
                case MAX_X -> maxX = NO_INTEGER;
                case MAX_Z -> maxZ = NO_INTEGER;
                case SCHEME -> {
                    schemeListed = true;
                    scheme = NO_INTEGER;
                }
                default -> {
                }
            }
        }

        private Where here() {
            if (depth == 0) {
                return Where.NOWHERE;
            }
            return depth <= level.length ? level[depth - 1] : Where.UNREAD;
        }

        private void push(Where where) {
            if (depth < level.length) {
                level[depth] = where;
            }
            depth++;
        }

        private void pop() {
            depth--;
        }

        private static Member memberOf(String stated) {
            return switch (stated) {
                case "version" -> Member.VERSION;
                case "accepted" -> Member.ACCEPTED;
                case "regions" -> Member.REGIONS;
                case "remaining" -> Member.REMAINING;
                default -> Member.NONE;
            };
        }

        private static Field fieldOf(String stated) {
            return switch (stated) {
                case "name" -> Field.NAME;
                case "dimension" -> Field.DIMENSION;
                case "minX" -> Field.MIN_X;
                case "minZ" -> Field.MIN_Z;
                case "maxX" -> Field.MAX_X;
                case "maxZ" -> Field.MAX_Z;
                case "scheme" -> Field.SCHEME;
                default -> Field.NONE;
            };
        }
    }
}
