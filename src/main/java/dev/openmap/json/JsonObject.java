package dev.openmap.json;

import java.io.IOException;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

// In document order. Duplicate keys: the last one wins.
public final class JsonObject implements JsonElement {

    private static final int SMALL_CAPACITY = 8;

    private static final int INITIAL_CAPACITY = 4;

    private static final int GROWTH_FACTOR = 2;

    private static final String[] EMPTY_NAMES = new String[0];

    private static final JsonElement[] EMPTY_VALUES = new JsonElement[0];

    private String[] names;

    private JsonElement[] values;

    private int size;

    private int changes;

    private Map<String, JsonElement> promoted;

    private Set<String> keys;

    private Set<Map.Entry<String, JsonElement>> entries;

    public JsonObject() {
        this(0);
    }

    public JsonObject(int expectedMembers) {
        if (expectedMembers < 0) {
            throw new IllegalArgumentException("expectedMembers < 0");
        }
        if (expectedMembers > SMALL_CAPACITY) {
            promoted = LinkedHashMap.newLinkedHashMap(expectedMembers);
            names = EMPTY_NAMES;
            values = EMPTY_VALUES;
        } else if (expectedMembers == 0) {
            names = EMPTY_NAMES;
            values = EMPTY_VALUES;
        } else {
            names = new String[expectedMembers];
            values = new JsonElement[expectedMembers];
        }
    }

    @Override
    public JsonObject getAsJsonObject() {
        return this;
    }

    // A Java null becomes JsonNull.
    public void add(String name, JsonElement value) {
        JsonElement stored = value == null ? JsonNull.INSTANCE : value;
        if (promoted != null) {
            promoted.put(name, stored);
        } else {
            int index = indexOf(name);
            if (index >= 0) {
                values[index] = stored;
            } else if (size == SMALL_CAPACITY) {
                promote();
                promoted.put(name, stored);
            } else {
                if (size == names.length) {
                    grow();
                }
                names[size] = name;
                values[size] = stored;
                size++;
                changes++;
            }
        }
    }

    public void addProperty(String name, String value) {
        add(name, value == null ? JsonNull.INSTANCE : JsonPrimitive.ofString(value));
    }

    public void addProperty(String name, Number value) {
        add(name, value == null ? JsonNull.INSTANCE : JsonPrimitive.ofNumber(value));
    }

    public void addProperty(String name, long value) {
        add(name, JsonPrimitive.ofNumberToken(Long.toString(value)));
    }

    public void addProperty(String name, double value) {
        add(name, JsonPrimitive.ofNumberToken(Double.toString(value)));
    }

    public void addProperty(String name, boolean value) {
        add(name, JsonPrimitive.ofBoolean(value));
    }

    public void addProperty(String name, Boolean value) {
        add(name, value == null ? JsonNull.INSTANCE : JsonPrimitive.ofBoolean(value));
    }

    // Java null if absent; JsonNull if the member is JSON null.
    public JsonElement get(String name) {
        if (promoted != null) {
            return promoted.get(name);
        }
        int index = indexOf(name);
        return index < 0 ? null : values[index];
    }

    // True even when the member's value is JSON null.
    public boolean has(String name) {
        return promoted != null ? promoted.containsKey(name) : indexOf(name) >= 0;
    }

    // The removed member, or null if there was none.
    public JsonElement remove(String name) {
        if (promoted != null) {
            return promoted.remove(name);
        }
        int index = indexOf(name);
        return index < 0 ? null : removeAt(index);
    }

    public int size() {
        return promoted != null ? promoted.size() : size;
    }

    public Set<String> keySet() {
        if (keys == null) {
            keys = new KeySet();
        }
        return keys;
    }

    public Set<Map.Entry<String, JsonElement>> entrySet() {
        if (entries == null) {
            entries = new EntrySet();
        }
        return entries;
    }

    // Null when absent; ClassCastException when present as a different kind.
    public JsonObject getAsJsonObject(String name) {
        JsonElement value = get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new ClassCastException("member \"" + name + "\" is not an object: "
                + JsonText.describe(value));
    }

    // Null when absent; ClassCastException when present as a different kind.
    public JsonArray getAsJsonArray(String name) {
        JsonElement value = get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof JsonArray array) {
            return array;
        }
        throw new ClassCastException("member \"" + name + "\" is not an array: "
                + JsonText.describe(value));
    }

    @Override
    public String toString() {
        return JsonText.shown(this);
    }

    void writeTo(JsonText.Emitter out) throws IOException {
        out.beginObject();
        if (promoted != null) {
            for (Map.Entry<String, JsonElement> member : promoted.entrySet()) {
                out.name(member.getKey());
                JsonText.write(member.getValue(), out);
            }
        } else {
            for (int index = 0; index < size; index++) {
                out.name(names[index]);
                JsonText.write(values[index], out);
            }
        }
        out.endObject();
    }

    private int indexOf(Object name) {
        int found = -1;
        for (int i = 0; i < size && found < 0; i++) {
            if (Objects.equals(names[i], name)) {
                found = i;
            }
        }
        return found;
    }

    private void grow() {
        int capacity = names.length == 0 ? INITIAL_CAPACITY : names.length * GROWTH_FACTOR;
        names = Arrays.copyOf(names, capacity);
        values = Arrays.copyOf(values, capacity);
    }

    private void promote() {
        Map<String, JsonElement> map = LinkedHashMap.newLinkedHashMap(SMALL_CAPACITY + 1);
        for (int i = 0; i < size; i++) {
            map.put(names[i], values[i]);
        }
        promoted = map;
        names = EMPTY_NAMES;
        values = EMPTY_VALUES;
        size = 0;
        changes++;
    }

    private JsonElement removeAt(int index) {
        JsonElement removed = values[index];
        int moved = size - index - 1;
        if (moved > 0) {
            System.arraycopy(names, index + 1, names, index, moved);
            System.arraycopy(values, index + 1, values, index, moved);
        }
        size--;
        names[size] = null;
        values[size] = null;
        changes++;
        return removed;
    }

    private void clear() {
        if (promoted != null) {
            promoted.clear();
            return;
        }
        if (size > 0) {
            Arrays.fill(names, 0, size, null);
            Arrays.fill(values, 0, size, null);
            size = 0;
            changes++;
        }
    }

    private final class Cursor {
        private int expectedChanges = changes;
        private int next;
        private int last = -1;

        private boolean hasNext() {
            checkChanges();
            return next < size;
        }

        private int advance() {
            checkChanges();
            if (next >= size) {
                throw new NoSuchElementException();
            }
            last = next++;
            return last;
        }

        private void remove() {
            checkChanges();
            if (last < 0) {
                throw new IllegalStateException();
            }
            removeAt(last);
            next = last;
            last = -1;
            expectedChanges = changes;
        }

        private void checkChanges() {
            if (expectedChanges != changes) {
                throw new ConcurrentModificationException();
            }
        }
    }

    private final class KeySet extends AbstractSet<String> {

        private final class KeySetIterator implements Iterator<String> {
            private final Cursor cursor = new Cursor();

            @Override
            public boolean hasNext() {
                return cursor.hasNext();
            }

            @Override
            public String next() {
                return names[cursor.advance()];
            }

            @Override
            public void remove() {
                cursor.remove();
            }
        }

        @Override
        public Iterator<String> iterator() {
            if (promoted != null) {
                return promoted.keySet().iterator();
            }
            return new KeySetIterator();
        }

        @Override
        public int size() {
            return JsonObject.this.size();
        }

        @Override
        public boolean contains(Object name) {
            return promoted != null ? promoted.containsKey(name) : indexOf(name) >= 0;
        }

        @Override
        public boolean remove(Object name) {
            if (promoted != null) {
                return promoted.keySet().remove(name);
            }
            int index = indexOf(name);
            if (index < 0) {
                return false;
            }
            removeAt(index);
            return true;
        }

        @Override
        public void clear() {
            JsonObject.this.clear();
        }
    }

    private final class EntrySet extends AbstractSet<Map.Entry<String, JsonElement>> {

        private final class EntrySetIterator implements Iterator<Map.Entry<String, JsonElement>> {
            private final Cursor cursor = new Cursor();

            @Override
            public boolean hasNext() {
                return cursor.hasNext();
            }

            @Override
            public Map.Entry<String, JsonElement> next() {
                return new Entry(names[cursor.advance()]);
            }

            @Override
            public void remove() {
                cursor.remove();
            }
        }

        @Override
        public Iterator<Map.Entry<String, JsonElement>> iterator() {
            if (promoted != null) {
                return promoted.entrySet().iterator();
            }
            return new EntrySetIterator();
        }

        @Override
        public int size() {
            return JsonObject.this.size();
        }

        @Override
        public boolean contains(Object candidate) {
            if (promoted != null) {
                return promoted.entrySet().contains(candidate);
            }
            if (!(candidate instanceof Map.Entry<?, ?> entry)) {
                return false;
            }
            int index = indexOf(entry.getKey());
            return index >= 0 && Objects.equals(values[index], entry.getValue());
        }

        @Override
        public boolean remove(Object candidate) {
            if (promoted != null) {
                return promoted.entrySet().remove(candidate);
            }
            if (!(candidate instanceof Map.Entry<?, ?> entry)) {
                return false;
            }
            int index = indexOf(entry.getKey());
            if (index < 0 || !Objects.equals(values[index], entry.getValue())) {
                return false;
            }
            removeAt(index);
            return true;
        }

        @Override
        public void clear() {
            JsonObject.this.clear();
        }
    }

    private final class Entry implements Map.Entry<String, JsonElement> {

        private final String name;

        private Entry(String name) {
            this.name = name;
        }

        @Override
        public String getKey() {
            return name;
        }

        @Override
        public JsonElement getValue() {
            return get(name);
        }

        @Override
        public JsonElement setValue(JsonElement value) {
            JsonElement previous = get(name);
            add(name, value);
            return previous;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Map.Entry<?, ?> entry)) {
                return false;
            }
            return Objects.equals(name, entry.getKey())
                    && Objects.equals(getValue(), entry.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(name) ^ Objects.hashCode(getValue());
        }
    }
}
