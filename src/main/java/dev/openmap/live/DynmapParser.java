package dev.openmap.live;

import dev.openmap.json.JsonArray;
import dev.openmap.json.JsonElement;
import dev.openmap.json.JsonObject;
import dev.openmap.json.JsonParser;
import dev.openmap.json.NumberGrammar;
import dev.openmap.mgrs.Bounds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DynmapParser {

    private static final int CIRCLE_CORNERS = 64;

    private static final double DEFAULT_OPACITY = 0.8;

    private static final double DEFAULT_FILL_OPACITY = 0.35;

    private DynmapParser() {
    }

    public static LiveSnapshot parseMarkers(String body, String world) {
        JsonObject root = asObject(body);
        if (root == null) {
            return LiveSnapshot.EMPTY;
        }
        JsonObject sets = object(root, "sets");
        if (sets == null) {
            return LiveSnapshot.EMPTY;
        }
        List<LiveSnapshot.Area> areas = new ArrayList<>();
        for (String setName : sets.keySet()) {
            JsonElement value = sets.get(setName);
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject body0 = value.getAsJsonObject();
            readAreas(body0, setName, world, areas);
            readCircles(body0, setName, world, areas);
        }
        return new LiveSnapshot(List.of(), List.of(), areas, 0L);
    }

    private static void readAreas(JsonObject set, String setName, String world,
                                  List<LiveSnapshot.Area> out) {
        JsonObject areas = object(set, "areas");
        if (areas == null) {
            return;
        }
        for (String areaId : areas.keySet()) {
            JsonElement value = areas.get(areaId);
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject area = value.getAsJsonObject();
            JsonArray xValues = array(area, "x");
            JsonArray zValues = array(area, "z");
            if (xValues == null || zValues == null) {
                continue;
            }
            double[][] pairs = xValues.size() == 2 && zValues.size() == 2
                    ? rectangle(xValues, zValues)
                    : corners(xValues, zValues);
            double[] xs = pairs[0];
            if (xs.length < LiveSnapshot.Area.MIN_CORNERS) {
                continue;
            }
            double[] zs = pairs[1];
            String outline = string(area, "color", "#FF0000");
            int line = colour(outline, number(area, "opacity", DEFAULT_OPACITY));
            int fill = colour(string(area, "fillcolor", outline),
                    number(area, "fillopacity", DEFAULT_FILL_OPACITY));
            out.add(new LiveSnapshot.Area(areaId,
                    string(area, "label", areaId), setName, world,
                    xs, zs, line, fill));
        }
    }

    private static final double[][] NO_CORNERS = {new double[0], new double[0]};

    private static final int RECTANGLE_CORNERS = 4;

    private static double[][] rectangle(JsonArray xValues, JsonArray zValues) {
        double[] xs = new double[RECTANGLE_CORNERS];
        double[] zs = new double[RECTANGLE_CORNERS];
        if (!readCorner(xValues.get(0), zValues.get(0), xs, zs, 0)
                || !readCorner(xValues.get(1), zValues.get(1), xs, zs, 1)) {
            return NO_CORNERS;
        }
        xs[2] = xs[1];
        xs[3] = xs[0];
        double farZ = zs[1];
        zs[1] = zs[0];
        zs[2] = farZ;
        zs[3] = farZ;
        return new double[][] {xs, zs};
    }

    private static void readCircles(JsonObject set, String setName, String world,
                                    List<LiveSnapshot.Area> out) {
        JsonObject circles = object(set, "circles");
        if (circles == null) {
            return;
        }
        for (String circleId : circles.keySet()) {
            JsonElement value = circles.get(circleId);
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject circle = value.getAsJsonObject();
            double xr = number(circle, "xr", 0);
            double zr = number(circle, "zr", 0);
            if (!(xr > 0) || !(zr > 0)) {
                continue;
            }
            double cx = number(circle, "x", Double.NaN);
            double cz = number(circle, "z", Double.NaN);
            if (!Double.isFinite(cx) || !Double.isFinite(cz)) {
                continue;
            }
            String outline = string(circle, "color", "#FF0000");
            int line = colour(outline, number(circle, "opacity", DEFAULT_OPACITY));
            int fill = colour(string(circle, "fillcolor", outline),
                    number(circle, "fillopacity", DEFAULT_FILL_OPACITY));
            double[] xs = new double[CIRCLE_CORNERS];
            double[] zs = new double[CIRCLE_CORNERS];
            double[] unitCosine = UNIT_RING[0];
            double[] unitSine = UNIT_RING[1];
            for (int i = 0; i < CIRCLE_CORNERS; i++) {
                xs[i] = cx + xr * unitCosine[i];
                zs[i] = cz + zr * unitSine[i];
            }
            out.add(new LiveSnapshot.Area(circleId,
                    string(circle, "label", circleId), setName, world,
                    xs, zs, line, fill));
        }
    }

    private static final double[][] UNIT_RING = unitRing();

    private static double[][] unitRing() {
        double[] cosines = new double[CIRCLE_CORNERS];
        double[] sines = new double[CIRCLE_CORNERS];
        for (int i = 0; i < CIRCLE_CORNERS; i++) {
            double angle = (2.0 * Math.PI * i) / CIRCLE_CORNERS;
            cosines[i] = Math.cos(angle);
            sines[i] = Math.sin(angle);
        }
        return new double[][] {cosines, sines};
    }

    public static int colour(String hex, double opacity) {
        int rgb = NumberGrammar.webColour(hex);
        if (rgb < 0) {
            rgb = 0x808080;
        }
        int alpha = (int) Math.round(Bounds.clamp(opacity, 0.0, 1.0) * 255.0);
        return (alpha << 24) | rgb;
    }

    private static final char BYTE_ORDER_MARK = 0xFEFF;

    private static JsonObject asObject(String body) {
        if (body == null || body.isBlank() || !isObjectRoot(body)) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static boolean isObjectRoot(String body) {
        int len = body.length();
        int at = body.charAt(0) == BYTE_ORDER_MARK ? 1 : 0;
        while (at < len && isRootWhitespace(body.charAt(at))) {
            at++;
        }
        return at < len && body.charAt(at) == '{';
    }

    private static boolean isRootWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement found = parent.get(name);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String name) {
        JsonElement found = parent.get(name);
        return found != null && found.isJsonArray() ? found.getAsJsonArray() : null;
    }

    private static String string(JsonObject parent, String name, String fallback) {
        JsonElement found = parent.get(name);
        if (found == null || !found.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return found.getAsString();
        } catch (RuntimeException wrongType) {
            return fallback;
        }
    }

    private static double number(JsonObject parent, String name, double fallback) {
        JsonElement found = parent.get(name);
        if (found == null || !found.isJsonPrimitive()) {
            return fallback;
        }
        dev.openmap.json.JsonPrimitive primitive = found.getAsJsonPrimitive();
        String text = primitive.getAsString();
        if ((primitive.isString() || primitive.isBoolean()) && !NumberGrammar.isJavaDouble(text)) {
            return fallback;
        }
        double parsed = Double.parseDouble(text);
        return Double.isFinite(parsed) ? parsed : fallback;
    }

    private static double[][] corners(JsonArray xs, JsonArray zs) {
        int paired = Math.min(xs.size(), zs.size());
        double[] xBuf = new double[paired];
        double[] zBuf = new double[paired];
        int kept = 0;
        for (int i = 0; i < paired; i++) {
            if (readCorner(xs.get(i), zs.get(i), xBuf, zBuf, kept)) {
                kept++;
            }
        }
        return kept == paired
                ? new double[][] {xBuf, zBuf}
                : new double[][] {Arrays.copyOf(xBuf, kept), Arrays.copyOf(zBuf, kept)};
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
