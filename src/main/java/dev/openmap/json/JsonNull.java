package dev.openmap.json;

// The JSON literal null, not a Java null.
public final class JsonNull implements JsonElement {

    public static final JsonNull INSTANCE = new JsonNull();

    private JsonNull() {
    }

    @Override
    public String toString() {
        return "null";
    }

    void writeTo(JsonText.Emitter out) {
        out.nullValue();
    }

    @Override
    public int hashCode() {
        return 0;
    }
}
