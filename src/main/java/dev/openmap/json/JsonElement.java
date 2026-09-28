package dev.openmap.json;

// A number or string that cannot parse: NumberFormatException.
// getAsString on an object, array or null: UnsupportedOperationException.
// getAsJsonObject/getAsJsonArray on the wrong kind: IllegalStateException.
// JsonObject.getAsJsonArray(name) on the wrong kind: ClassCastException.
public sealed interface JsonElement permits JsonObject, JsonArray, JsonPrimitive, JsonNull {

    default boolean isJsonObject() {
        return this instanceof JsonObject;
    }

    default boolean isJsonArray() {
        return this instanceof JsonArray;
    }

    default boolean isJsonPrimitive() {
        return this instanceof JsonPrimitive;
    }

    default boolean isJsonNull() {
        return this instanceof JsonNull;
    }

    default JsonObject getAsJsonObject() {
        throw new IllegalStateException("not a JSON object: " + JsonText.describe(this));
    }

    default JsonArray getAsJsonArray() {
        throw new IllegalStateException("not a JSON array: " + JsonText.describe(this));
    }

    default JsonPrimitive getAsJsonPrimitive() {
        throw new IllegalStateException("not a JSON primitive: " + JsonText.describe(this));
    }

    // For a number, the literal as written, not a reformatting.
    default String getAsString() {
        throw new UnsupportedOperationException("not a JSON primitive: " + JsonText.describe(this));
    }

    default double getAsDouble() {
        throw new UnsupportedOperationException("not a JSON primitive: " + JsonText.describe(this));
    }

    default long getAsLong() {
        throw new UnsupportedOperationException("not a JSON primitive: " + JsonText.describe(this));
    }

    default int getAsInt() {
        throw new UnsupportedOperationException("not a JSON primitive: " + JsonText.describe(this));
    }

    default boolean getAsBoolean() {
        throw new UnsupportedOperationException("not a JSON primitive: " + JsonText.describe(this));
    }

    default String toJson(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0");
        }
        StringBuilder out = new StringBuilder(capacity);
        JsonText.write(this, out, false, false);
        return out.toString();
    }
}
