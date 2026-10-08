package dev.openmap.live;

import dev.openmap.json.JsonArray;
import dev.openmap.json.JsonElement;
import dev.openmap.json.JsonObject;
import dev.openmap.json.JsonParser;
import dev.openmap.json.JsonPrimitive;
import dev.openmap.json.NumberGrammar;

public final class Json {

    private static final char BYTE_ORDER_MARK = 0xFEFF;

    private Json() {
    }

    public static JsonObject object(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        if (!rootIs(body, '{')) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    public static JsonArray arr(JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement found = parent.get(name);
        return found != null && found.isJsonArray() ? found.getAsJsonArray() : null;
    }

    public static String str(JsonObject parent, String name, String fallback) {
        if (parent == null) {
            return fallback;
        }
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

    public static double num(JsonObject parent, String name, double fallback) {
        if (parent == null) {
            return fallback;
        }
        JsonElement found = parent.get(name);
        if (found == null || !found.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = found.getAsJsonPrimitive();
        String text = primitive.getAsString();
        if ((primitive.isString() || primitive.isBoolean()) && !NumberGrammar.isJavaDouble(text)) {
            return fallback;
        }
        double parsed = Double.parseDouble(text);
        return Double.isFinite(parsed) ? parsed : fallback;
    }

    private static boolean rootIs(String body, char opener) {
        int len = body.length();
        int at = body.charAt(0) == BYTE_ORDER_MARK ? 1 : 0;
        while (at < len && isParserWhitespace(body.charAt(at))) {
            at++;
        }
        return at < len && body.charAt(at) == opener;
    }

    private static boolean isParserWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
