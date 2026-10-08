package dev.openmap.live;

import dev.openmap.json.JsonArray;
import dev.openmap.json.JsonElement;
import dev.openmap.json.JsonObject;
import dev.openmap.json.NumberGrammar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OpenMapParser {

    public static final String CLAIM_LAYER = "claims";

    private static final int LINE_GREY = 0xCC808080;

    private static final int FILL_GREY = 0x59808080;

    private static final String DIMENSION_SEPARATOR = " / ";

    private static final long MAX_UNSIGNED_INT = 0xFFFFFFFFL;

    public record NodeCopy(List<LiveSnapshot.Area> areas, List<Greeting> greetings) {

        public static final NodeCopy EMPTY = new NodeCopy(List.of(), List.of());

        public NodeCopy {
            areas = Collections.unmodifiableList(areas);
            greetings = Collections.unmodifiableList(greetings);
        }
    }

    private OpenMapParser() {
    }

    private static boolean sameWorldBare(String stated, boolean worldBlank, String wantedBare) {
        if (worldBlank || stated == null || stated.isBlank()) {
            return false;
        }
        return bareWorld(stated).equalsIgnoreCase(wantedBare);
    }

    public static List<Greeting> greetings(String body, String world) {
        JsonObject root = Json.object(body);
        if (root == null) {
            return new ArrayList<>();
        }
        boolean worldBlank = world == null || world.isBlank();
        return greetingsFor(root, worldBlank, bareWorld(world));
    }

    public static NodeCopy nodeCopy(String body, String world) {
        JsonObject root = Json.object(body);
        if (root == null) {
            return NodeCopy.EMPTY;
        }
        boolean worldBlank = world == null || world.isBlank();
        String wantedBare = bareWorld(world);
        return new NodeCopy(areasForWorld(root, worldBlank, wantedBare),
                greetingsFor(root, worldBlank, wantedBare));
    }

    private static List<Greeting> greetingsFor(JsonObject root, boolean worldBlank,
            String wantedBare) {
        List<Greeting> found = new ArrayList<>();
        JsonArray greetings = Json.arr(root, "greetings");
        if (greetings == null) {
            return found;
        }
        for (JsonElement element : greetings) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String entryWorld = Json.str(entry, "world", "");
            if (!sameWorldBare(entryWorld, worldBlank, wantedBare)) {
                continue;
            }
            found.add(new Greeting(
                    Json.str(entry, "id", ""),
                    entryWorld,
                    Json.str(entry, "greeting", ""),
                    Json.str(entry, "greetingsub", ""),
                    Json.str(entry, "farewell", ""),
                    Json.str(entry, "farewellsub", "")));
        }
        return found;
    }

    public static boolean sameWorld(String stated, String world) {
        if (stated == null || stated.isBlank() || world == null || world.isBlank()) {
            return false;
        }
        return bareWorld(stated).equalsIgnoreCase(bareWorld(world));
    }

    public static String bareWorld(String world) {
        String text = world == null ? "" : world.trim();
        if (text.startsWith("ResourceKey[") && text.endsWith("]")) {
            int split = text.lastIndexOf(DIMENSION_SEPARATOR);
            if (split >= 0) {
                return text.substring(split + DIMENSION_SEPARATOR.length(),
                        text.length() - 1).trim();
            }
        }
        return text;
    }

    private static List<LiveSnapshot.Area> areasForWorld(JsonObject root, boolean worldBlank,
            String wantedBare) {
        List<LiveSnapshot.Area> found = new ArrayList<>();
        JsonArray areas = Json.arr(root, "areas");
        if (areas == null) {
            return found;
        }
        int count = areas.size();
        for (int i = 0; i < count; i++) {
            JsonElement element = areas.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String entryWorld = Json.str(entry, "world", "");
            if (!sameWorldBare(entryWorld, worldBlank, wantedBare)) {
                continue;
            }
            LiveSnapshot.Area area = areaFromEntry(entry, entryWorld);
            if (area != null) {
                found.add(area);
            }
        }
        return found;
    }

    private static LiveSnapshot.Area areaFromEntry(JsonObject entry, String world) {
        String id = Json.str(entry, "id", "");
        if (id.isBlank()) {
            return null;
        }
        JsonArray xs = Json.arr(entry, "xs");
        JsonArray zs = Json.arr(entry, "zs");
        if (xs == null || zs == null
                || Math.min(xs.size(), zs.size()) < LiveSnapshot.Area.MIN_CORNERS) {
            return null;
        }
        double[][] ring = ring(xs, zs);
        if (ring[0].length < LiveSnapshot.Area.MIN_CORNERS) {
            return null;
        }
        return new LiveSnapshot.Area(id, Json.str(entry, "label", id),
                Json.str(entry, "set", CLAIM_LAYER), world, ring[0], ring[1],
                packed(entry, "line", LINE_GREY), packed(entry, "fill", FILL_GREY));
    }

    private static int packed(JsonObject entry, String name, int fallback) {
        double value = Json.num(entry, name, Double.NaN);
        if (Double.isNaN(value) || value < Integer.MIN_VALUE
                || value > MAX_UNSIGNED_INT) {
            return fallback;
        }
        return (int) (long) value;
    }

    private static double[][] ring(JsonArray xs, JsonArray zs) {
        int paired = Math.min(xs.size(), zs.size());
        double[] outX = new double[paired];
        double[] outZ = new double[paired];
        int kept = 0;
        for (int i = 0; i < paired; i++) {
            if (readCorner(xs.get(i), zs.get(i), outX, outZ, kept)) {
                kept++;
            }
        }
        return kept == paired
                ? new double[][] {outX, outZ}
                : new double[][] {java.util.Arrays.copyOf(outX, kept),
                        java.util.Arrays.copyOf(outZ, kept)};
    }

    private static boolean readCorner(JsonElement xElement, JsonElement zElement,
            double[] xOut, double[] zOut, int at) {
        if (!xElement.isJsonPrimitive() || !zElement.isJsonPrimitive()) {
            return false;
        }
        dev.openmap.json.JsonPrimitive xPrimitive = xElement.getAsJsonPrimitive();
        dev.openmap.json.JsonPrimitive zPrimitive = zElement.getAsJsonPrimitive();
        String xText = xPrimitive.getAsString();
        String zText = zPrimitive.getAsString();
        if ((xPrimitive.isString() || xPrimitive.isBoolean()) && !NumberGrammar.isJavaDouble(xText)) {
            return false;
        }
        if ((zPrimitive.isString() || zPrimitive.isBoolean()) && !NumberGrammar.isJavaDouble(zText)) {
            return false;
        }
        double x = Double.parseDouble(xText);
        double z = Double.parseDouble(zText);
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            return false;
        }
        xOut[at] = x;
        zOut[at] = z;
        return true;
    }

}
