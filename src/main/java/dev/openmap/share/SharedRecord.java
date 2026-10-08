package dev.openmap.share;

import dev.openmap.draw.MarkerColour;
import dev.openmap.json.JsonArray;
import dev.openmap.json.JsonElement;
import dev.openmap.json.JsonObject;
import dev.openmap.json.NumberGrammar;
import dev.openmap.map.LabelText;
import dev.openmap.symbol.SymbolIcon;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record SharedRecord(Kind kind, String id, String ownerId, String owner, long revised, Item item) {

    public static final int MAX_CORNERS = 4096;

    public static final int MIN_CORNERS = 3;

    private static final int MAX_LABEL = 64;

    public static final int MAX_ID = 128;

    public static final int MAX_NAME = 16;

    private static final int MAX_WORLD = 128;

    private static final int MAX_COLOUR = 32;

    public static final int MAX_COORDINATE = 30_000_000;

    public static final long MIN_REVISED = 1L;

    private static final String SERVER_HEADER = "X-GeoSurvey-Server";

    private static final int ENCODED_BYTE_CHARS = 3;

    private static final int NIBBLE_BITS = 4;

    private static final int NIBBLE_MASK = 0x0F;

    private static final int BYTE_MASK = 0xFF;

    private static final int HASH_MULTIPLIER = 31;

    private static final char[] UPPER_HEX = "0123456789ABCDEF".toCharArray();

    private static final int[] NO_INTS = new int[0];

    private static final int NOT_AN_ARRAY = -1;

    public enum Kind {
        CLAIM("claim"),
        MARKER("marker");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public sealed interface Item permits Removed, Area, Point {
    }

    // Which rules an item's fields were checked against: a new record's, or those of the build that stored it.
    private enum Road {
        LIVE,
        STORED
    }

    public record Removed() implements Item {
    }

    public static final class Area implements Item {

        private final String label;
        private final String world;
        private final int[] xs;
        private final int[] zs;
        private final String colour;
        private final int line;
        private final int fill;

        public Area(String label, String world, int[] xs, int[] zs, String colour, int line, int fill) {
            this(Road.LIVE, label, world, xs, zs, colour, line, fill);
        }

        public int[] xs() {
            return xs.clone();
        }

        public int[] zs() {
            return zs.clone();
        }

        public String label() {
            return label;
        }

        public String world() {
            return world;
        }

        public String colour() {
            return colour;
        }

        public int line() {
            return line;
        }

        public int fill() {
            return fill;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Area area
                    && line == area.line
                    && fill == area.fill
                    && xs.length == area.xs.length
                    && label.equals(area.label)
                    && world.equals(area.world)
                    && Arrays.equals(xs, area.xs)
                    && Arrays.equals(zs, area.zs)
                    && colour.equals(area.colour);
        }

        @Override
        public int hashCode() {
            int hash = label.hashCode();
            hash = HASH_MULTIPLIER * hash + world.hashCode();
            hash = HASH_MULTIPLIER * hash + Arrays.hashCode(xs);
            hash = HASH_MULTIPLIER * hash + Arrays.hashCode(zs);
            hash = HASH_MULTIPLIER * hash + colour.hashCode();
            hash = HASH_MULTIPLIER * hash + line;
            return HASH_MULTIPLIER * hash + fill;
        }

        @Override
        public String toString() {
            return "Area[label=" + label + ", world=" + world + ", xs=" + Arrays.toString(xs)
                    + ", zs=" + Arrays.toString(zs) + ", colour=" + colour + ", line=" + line
                    + ", fill=" + fill + "]";
        }

        private static Area stored(String label, String world, int[] xs, int[] zs, String colour, int line,
                int fill) {
            return new Area(Road.STORED, label, world, xs, zs, colour, line, fill);
        }

        private Area(Road road, String label, String world, int[] xs, int[] zs, String colour, int line,
                int fill) {
            if (road == Road.LIVE) {
                checkLive(label, world);
            } else {
                checkStoredLabel(label);
                checkStoredWorld(world);
            }
            if (xs == null || zs == null) {
                throw new IllegalArgumentException("corners are null");
            }
            if (xs.length != zs.length) {
                throw new IllegalArgumentException("corner arrays differ in length");
            }
            if (!isCornerCount(xs.length)) {
                throw new IllegalArgumentException("corner count is out of range");
            }
            for (int coordinate : xs) {
                checkCoordinate(coordinate);
            }
            for (int coordinate : zs) {
                checkCoordinate(coordinate);
            }
            if (road == Road.LIVE) {
                checkColour(colour);
            } else {
                checkStoredColour(colour);
            }
            this.label = label;
            this.world = world;
            // The stored road keeps the arrays it is given; the live road copies them.
            if (road == Road.STORED) {
                this.xs = xs;
                this.zs = zs;
            } else {
                this.xs = xs.clone();
                this.zs = zs.clone();
            }
            this.colour = colour;
            this.line = line;
            this.fill = fill;
        }
    }

    public static final class Point implements Item {

        private final String label;
        private final String world;
        private final int x;
        private final int z;
        private final String icon;

        public Point(String label, String world, int x, int z, String icon) {
            this(Road.LIVE, label, world, x, z, icon);
        }

        public String label() {
            return label;
        }

        public String world() {
            return world;
        }

        public int x() {
            return x;
        }

        public int z() {
            return z;
        }

        public String icon() {
            return icon;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Point point
                    && label.equals(point.label)
                    && world.equals(point.world)
                    && x == point.x
                    && z == point.z
                    && icon.equals(point.icon);
        }

        @Override
        public int hashCode() {
            int hash = label.hashCode();
            hash = HASH_MULTIPLIER * hash + world.hashCode();
            hash = HASH_MULTIPLIER * hash + x;
            hash = HASH_MULTIPLIER * hash + z;
            return HASH_MULTIPLIER * hash + icon.hashCode();
        }

        @Override
        public String toString() {
            return "Point[label=" + label + ", world=" + world + ", x=" + x + ", z=" + z
                    + ", icon=" + icon + "]";
        }

        private static Point stored(String label, String world, int x, int z, String icon) {
            return new Point(Road.STORED, label, world, x, z, icon);
        }

        private Point(Road road, String label, String world, int x, int z, String icon) {
            if (road == Road.LIVE) {
                checkLive(label, world);
            } else {
                checkStoredLabel(label);
                checkStoredWorld(world);
            }
            checkCoordinate(x);
            checkCoordinate(z);
            if (icon == null) {
                throw new IllegalArgumentException("icon is null");
            }
            try {
                SymbolIcon.valueOf(icon);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("icon name is unknown", unknown);
            }
            this.label = label;
            this.world = world;
            this.x = x;
            this.z = z;
            this.icon = icon;
        }
    }

    public SharedRecord {
        if (kind == null) {
            throw new IllegalArgumentException("kind is null");
        }
        checkId(id);
        checkOwnerId(ownerId);
        checkOwner(ownerId, owner);
        if (revised < MIN_REVISED) {
            throw new IllegalArgumentException("revised is below one");
        }
        if (item == null) {
            throw new IllegalArgumentException("item is null");
        }
        if (kind == Kind.CLAIM && !(item instanceof Area || item instanceof Removed)) {
            throw new IllegalArgumentException("a claim needs an area or tombstone");
        }
        if (kind == Kind.MARKER && !(item instanceof Point || item instanceof Removed)) {
            throw new IllegalArgumentException("a marker needs a point or tombstone");
        }
    }

    public boolean removed() {
        return item instanceof Removed;
    }

    public String json() {
        return document().toString();
    }

    public static String serverHeader() {
        return SERVER_HEADER;
    }

    // The server's UTF-8 bytes, percent-encoded as RFC 3986 does, in upper case.
    public static String serverHeaderValue(String server) {
        byte[] bytes = server.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * ENCODED_BYTE_CHARS);
        for (byte each : bytes) {
            int value = each & BYTE_MASK;
            if (unreserved(value)) {
                encoded.append((char) value);
            } else {
                encoded.append('%').append(UPPER_HEX[value >>> NIBBLE_BITS]).append(UPPER_HEX[value & NIBBLE_MASK]);
            }
        }
        return encoded.toString();
    }

    private static boolean unreserved(int value) {
        return ((value >= 'A') && (value <= 'Z')) || ((value >= 'a') && (value <= 'z'))
                || ((value >= '0') && (value <= '9')) || (value == '-') || (value == '.') || (value == '_')
                || (value == '~');
    }

    // The item a stored document names, as the rules of the build that wrote it allow.
    public static Item itemFromStored(Kind kind, JsonObject fields) throws IOException {
        String label = storedLabel(text(fields, "label"));
        Item item;
        if (kind == Kind.CLAIM) {
            item = storedArea(label, fields);
        } else {
            item = Point.stored(label, text(fields, "world"),
                    integer(fields.get("x"), "x"), integer(fields.get("z"), "z"), text(fields, "icon"));
        }
        return item;
    }

    // Corner arrays of a size the area refuses are read in full but not kept.
    private static Item storedArea(String label, JsonObject fields) throws IOException {
        String world = text(fields, "world");
        JsonElement xsElement = fields.get("xs");
        JsonElement zsElement = fields.get("zs");
        int xsSize = sizeOf(xsElement);
        int zsSize = sizeOf(zsElement);
        boolean fits = (xsSize == zsSize) && isCornerCount(xsSize);
        int[] xs = ints(xsElement, "xs", fits);
        int[] zs = ints(zsElement, "zs", fits);
        String colour = text(fields, "colour");
        int line = integer(fields.get("line"), "line");
        int fill = integer(fields.get("fill"), "fill");
        if (!fits) {
            checkStoredWorld(world);
            String refusal = (xsSize != zsSize) ? "corner arrays differ in length"
                    : "corner count is out of range";
            throw new IllegalArgumentException(refusal);
        }
        return Area.stored(label, world, xs, zs, colour, line, fill);
    }

    private static int sizeOf(JsonElement element) {
        return (element instanceof JsonArray array) ? array.size() : NOT_AN_ARRAY;
    }

    private JsonObject document() {
        JsonObject object = new JsonObject();
        object.addProperty("version", 1L);
        object.addProperty("kind", kind.wire());
        object.addProperty("id", id);
        object.addProperty("ownerId", ownerId);
        object.addProperty("owner", owner);
        object.addProperty("revised", revised);
        object.addProperty("removed", removed());
        if (item instanceof Area area) {
            object.addProperty("label", area.label());
            object.addProperty("world", area.world());
            object.add("xs", arrayOf(area.xs));
            object.add("zs", arrayOf(area.zs));
            object.addProperty("colour", area.colour());
            object.addProperty("line", area.line());
            object.addProperty("fill", area.fill());
        } else if (item instanceof Point point) {
            object.addProperty("label", point.label());
            object.addProperty("world", point.world());
            object.addProperty("x", point.x());
            object.addProperty("z", point.z());
            object.addProperty("icon", point.icon());
        }
        return object;
    }

    private static JsonArray arrayOf(int[] values) {
        JsonArray array = new JsonArray(values.length);
        for (int value : values) {
            array.add(value);
        }
        return array;
    }

    // The array's integers; where keep is false each is read and none is kept.
    private static int[] ints(JsonElement element, String name, boolean keep) throws IOException {
        if (!(element instanceof JsonArray array)) {
            throw new IOException(name + " is not an array");
        }
        int[] values;
        if (keep) {
            values = new int[array.size()];
            for (int at = 0; at < values.length; at++) {
                values[at] = integer(array.get(at), name);
            }
        } else {
            for (int at = 0; at < array.size(); at++) {
                integer(array.get(at), name);
            }
            values = NO_INTS;
        }
        return values;
    }

    private static String text(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException(name + " is not a string");
        }
        return element.getAsString();
    }

    private static int integer(JsonElement element, String name) throws IOException {
        if (!isNumber(element)) {
            throw new IOException(name + " is not a whole number");
        }
        Long found = NumberGrammar.decimalLong(element.getAsString());
        if (found == null) {
            throw new IOException(name + " is not a whole number");
        }
        long value = found.longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException(name + " is out of range");
        }
        return (int) value;
    }

    static boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && !element.getAsJsonPrimitive().isBoolean()
                && element.getAsJsonPrimitive().toString().charAt(0) != '"';
    }

    private static void checkId(String value) {
        String fault = idFault(value);
        if (fault != null) {
            throw new IllegalArgumentException(fault);
        }
    }

    public static boolean isId(String value) {
        return idFault(value) == null;
    }

    private static String idFault(String value) {
        String fault;
        if ((value == null) || (value.length() == 0) || (value.length() > MAX_ID)) {
            fault = "id length is out of range";
        } else if (!idCharacters(value)) {
            fault = "id has an invalid character";
        } else {
            fault = null;
        }
        return fault;
    }

    private static boolean idCharacters(String value) {
        boolean allowed = true;
        for (int at = 0; allowed && (at < value.length()); at++) {
            char character = value.charAt(at);
            allowed = ((character >= 'A') && (character <= 'Z')) || ((character >= 'a') && (character <= 'z'))
                    || ((character >= '0') && (character <= '9')) || (character == '.') || (character == '_')
                    || (character == ':') || (character == '-');
        }
        return allowed;
    }

    public static boolean isName(String name) {
        boolean allowed = (name != null) && (name.length() > 0) && (name.length() <= MAX_NAME);
        for (int at = 0; allowed && (at < name.length()); at++) {
            char character = name.charAt(at);
            allowed = ((character >= 'A') && (character <= 'Z')) || ((character >= 'a') && (character <= 'z'))
                    || ((character >= '0') && (character <= '9')) || (character == '_');
        }
        return allowed;
    }

    private static void checkOwnerId(String value) {
        if (!isNameOwnerId(value)) {
            throw new IllegalArgumentException("ownerId is not a lowercase player name");
        }
    }

    private static boolean isNameOwnerId(String value) {
        boolean allowed = (value != null) && (value.length() > 0) && (value.length() <= MAX_NAME);
        for (int at = 0; allowed && (at < value.length()); at++) {
            char character = value.charAt(at);
            allowed = ((character >= 'a') && (character <= 'z'))
                    || ((character >= '0') && (character <= '9')) || (character == '_');
        }
        return allowed;
    }

    private static void checkOwner(String ownerId, String value) {
        if (!isName(value) || !value.equalsIgnoreCase(ownerId)) {
            throw new IllegalArgumentException("owner does not match ownerId");
        }
    }

    private static void checkLive(String label, String world) {
        if (!isLabel(label)) {
            throw new IllegalArgumentException("label is not clean and bounded");
        }
        checkWorld(world);
    }

    public static boolean isLabel(String label) {
        return (label != null) && (label.length() > 0) && (label.length() <= MAX_LABEL)
                && labelFor(label).equals(label);
    }

    // The label a record carries for a name: cleaned, and bounded to MAX_LABEL.
    public static String labelFor(String name) {
        return LabelText.clean(name, MAX_LABEL, LabelText.UNBOUNDED_READ, false);
    }

    // A stored label reads back cleaned as today's rules clean it, which may leave nothing of it.
    private static String storedLabel(String label) {
        checkStoredLabel(label);
        return labelFor(label);
    }

    private static void checkStoredLabel(String label) {
        if ((label == null) || (label.length() > MAX_LABEL)) {
            throw new IllegalArgumentException("label is not clean and bounded");
        }
    }

    private static void checkColour(String colour) {
        if (colour == null || colour.length() == 0 || colour.length() > MAX_COLOUR) {
            throw new IllegalArgumentException("colour length is out of range");
        }
        try {
            MarkerColour.valueOf(colour);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("colour name is unknown", unknown);
        }
    }

    private static void checkStoredColour(String colour) {
        if (colour == null || colour.length() == 0 || colour.length() > MAX_COLOUR) {
            throw new IllegalArgumentException("colour length is out of range");
        }
        if (!storedColourCharacters(colour)) {
            throw new IllegalArgumentException("colour name is unknown");
        }
    }

    // An older build's colour alphabet: upper-case letters and underscore, which holds every name.
    private static boolean storedColourCharacters(String colour) {
        boolean allowed = true;
        for (int at = 0; allowed && (at < colour.length()); at++) {
            char character = colour.charAt(at);
            allowed = (character >= 'A' && character <= 'Z') || character == '_';
        }
        return allowed;
    }

    private static void checkStoredWorld(String world) {
        String fault = storedWorldFault(world);
        if (fault != null) {
            throw new IllegalArgumentException(fault);
        }
    }

    private static String storedWorldFault(String world) {
        String fault;
        if ((world == null) || (world.length() == 0) || (world.length() > MAX_WORLD)) {
            fault = "world length is out of range";
        } else {
            fault = storedWorldShapeFault(world);
        }
        return fault;
    }

    private static String storedWorldShapeFault(String world) {
        int colons = 0;
        boolean refused = false;
        for (int at = 0; !refused && (at < world.length()); at++) {
            char character = world.charAt(at);
            if (character == ':') {
                colons++;
            } else {
                refused = !isNamespaceChar(character) && (character != '/');
            }
        }
        String fault;
        if (refused) {
            fault = "world has an invalid character";
        } else if (colons != 1) {
            fault = "world must have one colon";
        } else {
            fault = null;
        }
        return fault;
    }

    private static void checkWorld(String world) {
        String fault = worldFault(world);
        if (fault != null) {
            throw new IllegalArgumentException(fault);
        }
    }

    public static boolean isWorld(String world) {
        return worldFault(world) == null;
    }

    private static String worldFault(String world) {
        String fault;
        if ((world == null) || (world.length() == 0) || (world.length() > MAX_WORLD)) {
            fault = "world length is out of range";
        } else {
            int colon = world.indexOf(':');
            if ((colon < 0) || (world.indexOf(':', colon + 1) >= 0)) {
                fault = "world must have one colon";
            } else {
                String namespace = namespaceFault(world, 0, colon);
                fault = (namespace == null) ? pathFault(world, colon + 1, world.length()) : namespace;
            }
        }
        return fault;
    }

    // A namespace holds no slash; a path may.
    private static String namespaceFault(String world, int from, int to) {
        String fault;
        if (from == to) {
            fault = "world has an empty namespace";
        } else if (!inAlphabet(world, from, to, false)) {
            fault = "world has an invalid character";
        } else {
            fault = null;
        }
        return fault;
    }

    private static String pathFault(String world, int from, int to) {
        String fault;
        if (from == to) {
            fault = "world has an empty path";
        } else if (!inAlphabet(world, from, to, true)) {
            fault = "world has an invalid character";
        } else {
            fault = null;
        }
        return fault;
    }

    private static boolean inAlphabet(String world, int from, int to, boolean slash) {
        boolean allowed = true;
        for (int at = from; allowed && (at < to); at++) {
            char character = world.charAt(at);
            allowed = isNamespaceChar(character) || (slash && (character == '/'));
        }
        return allowed;
    }

    private static boolean isNamespaceChar(char character) {
        return (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                || character == '_' || character == '.' || character == '-';
    }

    private static void checkCoordinate(int value) {
        if ((value < -MAX_COORDINATE) || (value > MAX_COORDINATE)) {
            throw new IllegalArgumentException("coordinate is out of range");
        }
    }

    public static boolean isCoordinate(double value) {
        return (Double.compare(value, Math.rint(value)) == 0) && (value >= -MAX_COORDINATE)
                && (value <= MAX_COORDINATE);
    }

    public static boolean isCornerCount(int corners) {
        return (corners >= MIN_CORNERS) && (corners <= MAX_CORNERS);
    }
}
