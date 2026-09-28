package dev.openmap.json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// getAsLong, getAsString and similar accessors throw UnsupportedOperationException here.
public final class JsonArray implements JsonElement, Iterable<JsonElement> {

    private final List<JsonElement> items;

    public JsonArray() {
        items = new ArrayList<>();
    }

    public JsonArray(int capacity) {
        items = new ArrayList<>(capacity);
    }

    @Override
    public JsonArray getAsJsonArray() {
        return this;
    }

    // A Java null becomes JsonNull.
    public void add(JsonElement value) {
        items.add(value == null ? JsonNull.INSTANCE : value);
    }

    public void add(String value) {
        items.add(value == null ? JsonNull.INSTANCE : JsonPrimitive.ofString(value));
    }

    public void add(Number value) {
        items.add(value == null ? JsonNull.INSTANCE : JsonPrimitive.ofNumber(value));
    }

    public void add(long value) {
        items.add(JsonPrimitive.ofNumber(value));
    }

    public void add(double value) {
        items.add(JsonPrimitive.ofNumber(value));
    }

    public void add(boolean value) {
        items.add(JsonPrimitive.ofBoolean(value));
    }

    // Throws IndexOutOfBoundsException past the end.
    public JsonElement get(int index) {
        return items.get(index);
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public String toString() {
        return JsonText.shown(this);
    }

    void writeTo(JsonText.Emitter out) throws IOException {
        out.beginArray();
        int size = items.size();
        for (int i = 0; i < size; i++) {
            JsonText.write(items.get(i), out);
        }
        out.endArray();
    }

    @Override
    public Iterator<JsonElement> iterator() {
        return items.iterator();
    }
}
