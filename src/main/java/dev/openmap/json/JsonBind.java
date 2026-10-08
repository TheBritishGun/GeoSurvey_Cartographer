package dev.openmap.json;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class JsonBind {

    private static final Map<Class<?>, Descriptor> FIELDS = new ConcurrentHashMap<>();

    private static volatile Descriptor lastDescriptor;

    private static final Map<Class<?>, Constructor<?>> CONSTRUCTORS = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Map<String, Object>> ENUM_CONSTANTS =
            new ConcurrentHashMap<>();

    private static final Map<Type, Spec> KINDS = new ConcurrentHashMap<>();

    private static final Map<Class<?>, WriteTag> WRITE_TAGS = new ConcurrentHashMap<>();

    private static final Object[] NO_ARGS = new Object[0];

    private static final Class<?>[] NO_PARAMS = new Class<?>[0];

    private static final AtomicLong KIND_RESOLUTIONS = new AtomicLong();

    private static final AtomicLong TO_DOUBLE_CALLS = new AtomicLong();

    private static final AtomicLong CONSTRUCTOR_LOOKUPS = new AtomicLong();

    private static final AtomicLong WRITE_TAG_RESOLUTIONS = new AtomicLong();

    private static final AtomicLong DESCRIPTOR_PROBES = new AtomicLong();

    private static final AtomicLong TYPE_CHAIN_READS = new AtomicLong();

    private static final JsonBind PRETTY = new JsonBind(true, false);

    private static final JsonBind PRETTY_WITH_NULLS = new JsonBind(true, true);

    private static final JsonBind COMPACT = new JsonBind(false, false);

    private final boolean pretty;

    private final boolean writeNulls;

    private JsonBind(boolean pretty, boolean writeNulls) {
        this.pretty = pretty;
        this.writeNulls = writeNulls;
    }

    // Omits null fields.
    public static JsonBind pretty() {
        return PRETTY;
    }

    public static JsonBind prettyWithNulls() {
        return PRETTY_WITH_NULLS;
    }

    // Omits null fields.
    public static JsonBind compact() {
        return COMPACT;
    }

    // Null for an empty document or a literal null.
    public <T> T fromJson(Reader reader, Class<T> type) {
        Binder binder = new Binder(KINDS.computeIfAbsent(type, JsonBind::specOf));
        JsonParser.parseInto(reader, binder);
        Object bound = binder.bound;
        return bound == null ? null : cast(type, bound);
    }

    public <T> T fromJson(JsonElement document, Class<T> type) {
        if (document == null || document.isJsonNull()) {
            return null;
        }
        return cast(type, read(document, type));
    }

    private static <T> T cast(Class<T> type, Object read) {
        @SuppressWarnings("unchecked")
        Class<T> boxed = (Class<T>) boxOf(type);
        return boxed.cast(read);
    }

    private static Class<?> boxOf(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        Class<?> boxed = type;
        if (type == int.class) {
            boxed = Integer.class;
        } else if (type == long.class) {
            boxed = Long.class;
        } else if (type == double.class) {
            boxed = Double.class;
        } else if (type == float.class) {
            boxed = Float.class;
        } else if (type == boolean.class) {
            boxed = Boolean.class;
        } else if (type == byte.class) {
            boxed = Byte.class;
        } else if (type == short.class) {
            boxed = Short.class;
        } else if (type == char.class) {
            boxed = Character.class;
        } else {
            boxed = type;
        }
        return boxed;
    }

    public <T> List<T> fromJsonList(Reader reader, Class<T> element) {
        @SuppressWarnings("unchecked")
        Class<T> boxed = (Class<T>) boxOf(element);
        List<Object> items = readArray(reader, element);
        List<T> out = null;
        if (items != null) {
            int size = items.size();
            for (int at = 0; at < size; at++) {
                items.set(at, boxed.cast(items.get(at)));
            }
            @SuppressWarnings("unchecked")
            List<T> typed = (List<T>) items;
            out = typed;
        }
        return out;
    }

    // -1 for an empty document or a literal null.
    public <T> int fromJsonStream(Reader reader, Class<T> element, Consumer<T> each) {
        @SuppressWarnings("unchecked")
        Class<T> boxed = (Class<T>) boxOf(element);
        Binder binder = new Binder(KINDS.computeIfAbsent(element, JsonBind::specOf),
                bound -> each.accept(boxed.cast(bound)));
        JsonParser.parseInto(reader, binder);
        return binder.arrayPresent ? binder.seen : -1;
    }

    private static List<Object> readArray(Reader reader, Type element) {
        Binder binder = new Binder(KINDS.computeIfAbsent(element, JsonBind::specOf), null);
        JsonParser.parseInto(reader, binder);
        return binder.items;
    }

    // Accepts an object or a List.
    public void toJson(Object value, Appendable out) throws IOException {
        JsonText.Emitter text = new JsonText.Emitter(out, pretty, true);
        try {
            write(value, text);
        } catch (JsonText.TooDeep tooDeep) {
            throw new IOException(tooDeep.getMessage());
        }
        text.finish();
    }

    public void toJson(JsonElement tree, Appendable out) throws IOException {
        JsonText.Emitter text = new JsonText.Emitter(out, pretty, false);
        try {
            JsonText.write(tree, text);
        } catch (JsonText.TooDeep tooDeep) {
            throw new IOException(tooDeep.getMessage());
        }
        text.finish();
    }

    public JsonElement toJsonTree(Object value) {
        Tree tree = new Tree();
        write(value, tree);
        return tree.root;
    }


    private static Object read(JsonElement value, Type type) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        TYPE_CHAIN_READS.incrementAndGet();
        return bind(value, KINDS.computeIfAbsent(type, JsonBind::specOf));
    }

    private static Object readAs(JsonElement value, Spec shape) {
        TYPE_CHAIN_READS.incrementAndGet();
        return bind(value, shape);
    }

    private static Spec specOf(Type type) {
        KIND_RESOLUTIONS.incrementAndGet();
        if (type instanceof ParameterizedType parameterized) {
            if (!(parameterized.getRawType() instanceof Class<?> raw)) {
                return new Spec(Kind.REFUSE, null, null,
                        "unsupported generic field type: " + type);
            }
            Type[] arguments = parameterized.getActualTypeArguments();
            Spec spec;
            if (List.class.isAssignableFrom(raw)) {
                spec = new Spec(Kind.LIST, raw, arguments[0], null);
            } else if (Map.class.isAssignableFrom(raw)) {
                if (arguments[0] != String.class) {
                    spec = new Spec(Kind.REFUSE, raw, null,
                            "unsupported map key type: " + arguments[0]);
                } else {
                    spec = new Spec(Kind.MAP, raw, arguments[1], null);
                }
            } else {
                spec = new Spec(Kind.REFUSE, raw, null,
                        "unsupported generic field type: " + type);
            }
            return spec;
        }
        if (!(type instanceof Class<?> raw)) {
            return new Spec(Kind.REFUSE, null, null, "unsupported field type: " + type);
        }
        return new Spec(kindOf(raw), raw, null, null);
    }

    private static Object bind(JsonElement value, Spec shape) {
        Class<?> raw = shape.raw;
        return switch (shape.kind) {
        case LIST -> readList(value, shape.argument);
        case MAP -> readMap(value, shape.argument);
        case REFUSE -> throw new JsonParseException(shape.refusal);
        case STRING -> scalar(value, raw).getAsString();
        case INT -> toInt(scalar(value, raw));
        case LONG -> toLong(scalar(value, raw));
        case DOUBLE -> toDouble(scalar(value, raw));
        case FLOAT -> (float) toDouble(scalar(value, raw));
        case BOOLEAN -> scalarBoolean(value, raw).getAsBoolean();
        case ENUM -> {
            String name = scalar(value, raw).getAsString();
            Map<String, Object> constants = ENUM_CONSTANTS.get(raw);
            if (constants == null) {
                constants = ENUM_CONSTANTS.computeIfAbsent(raw, JsonBind::enumConstants);
            }
            yield constants.get(name);
        }
        case DOUBLE_ARRAY -> {
            if (!value.isJsonArray()) {
                throw new JsonParseException("expected a JSON array for " + raw);
            }
            JsonArray items = value.getAsJsonArray();
            int n = items.size();
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                JsonElement item = items.get(i);
                if (item == null || item.isJsonNull()) {
                    throw new JsonParseException("expected a double at index " + i
                            + " of a double[], found null");
                }
                out[i] = toDouble(scalar(item, double.class));
            }
            yield out;
        }
        case ARRAY -> {
            if (!value.isJsonArray()) {
                throw new JsonParseException("expected a JSON array for " + raw);
            }
            JsonArray items = value.getAsJsonArray();
            Class<?> component = raw.getComponentType();
            if (component == int.class) {
                int[] counts = new int[items.size()];
                for (int i = 0; i < counts.length; i++) {
                    counts[i] = toInt(scalar(arrayMember(items, component, raw, i), component));
                }
                yield counts;
            }
            if (component == long.class) {
                long[] spans = new long[items.size()];
                for (int i = 0; i < spans.length; i++) {
                    spans[i] = toLong(scalar(arrayMember(items, component, raw, i), component));
                }
                yield spans;
            }
            if (component == float.class) {
                float[] ratios = new float[items.size()];
                for (int i = 0; i < ratios.length; i++) {
                    ratios[i] = (float) toDouble(
                            scalar(arrayMember(items, component, raw, i), component));
                }
                yield ratios;
            }
            if (component == boolean.class) {
                boolean[] flags = new boolean[items.size()];
                for (int i = 0; i < flags.length; i++) {
                    flags[i] = scalarBoolean(
                            arrayMember(items, component, raw, i), component).getAsBoolean();
                }
                yield flags;
            }
            if (!component.isPrimitive()) {
                Object array = Array.newInstance(component, items.size());
                if (!(array instanceof Object[] members)) {
                    throw new JsonParseException("not an object array type: " + raw);
                }
                for (int i = 0; i < members.length; i++) {
                    members[i] = read(items.get(i), component);
                }
                yield members;
            }
            int n = items.size();
            boolean primitive = component.isPrimitive();
            Object out = Array.newInstance(component, n);
            for (int i = 0; i < n; i++) {
                Object item = read(items.get(i), component);
                if (item == null && primitive) {
                    throw new JsonParseException("expected a " + component.getName()
                            + " at index " + i + " of a " + raw.getSimpleName()
                            + ", found null");
                }
                Array.set(out, i, item);
            }
            yield out;
        }
        case OBJECT -> readObject(value, raw);
        };
    }

    private static JsonElement arrayMember(JsonArray items, Class<?> component, Class<?> raw,
            int index) {
        JsonElement item = items.get(index);
        if (item == null || item.isJsonNull()) {
            throw new JsonParseException("expected a " + component.getName() + " at index "
                    + index + " of a " + raw.getSimpleName() + ", found null");
        }
        return item;
    }

    private static Kind kindOf(Class<?> raw) {
        Kind kind = Kind.OBJECT;
        if (raw == String.class) {
            kind = Kind.STRING;
        } else if (raw == int.class || raw == Integer.class) {
            kind = Kind.INT;
        } else if (raw == long.class || raw == Long.class) {
            kind = Kind.LONG;
        } else if (raw == double.class || raw == Double.class) {
            kind = Kind.DOUBLE;
        } else if (raw == float.class || raw == Float.class) {
            kind = Kind.FLOAT;
        } else if (raw == boolean.class || raw == Boolean.class) {
            kind = Kind.BOOLEAN;
        } else if (raw.isEnum()) {
            kind = Kind.ENUM;
        } else if (raw == double[].class) {
            kind = Kind.DOUBLE_ARRAY;
        } else if (raw.isArray()) {
            kind = Kind.ARRAY;
        } else {
            kind = Kind.OBJECT;
        }
        return kind;
    }

    private enum Kind {
        STRING, INT, LONG, DOUBLE, FLOAT, BOOLEAN, ENUM, DOUBLE_ARRAY, ARRAY, LIST, MAP,
        REFUSE, OBJECT
    }

    private static JsonElement scalar(JsonElement value, Class<?> type) {
        if (!value.isJsonPrimitive()) {
            throw new JsonParseException("expected a JSON primitive for "
                    + type.getName() + ", found " + JsonText.describe(value));
        }
        return value;
    }

    private static JsonElement scalarBoolean(JsonElement value, Class<?> type) {
        JsonElement checked = scalar(value, type);
        if (!(checked instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw new JsonParseException("expected a JSON boolean for "
                    + type.getName() + ", found " + JsonText.describe(value));
        }
        return checked;
    }

    private static List<Object> readList(JsonElement value, Type element) {
        if (!value.isJsonArray()) {
            throw new JsonParseException("expected a JSON array");
        }
        JsonArray items = value.getAsJsonArray();
        int n = items.size();
        List<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(read(items.get(i), element));
        }
        return out;
    }

    private static Map<String, Object> readMap(JsonElement value, Type valueType) {
        if (!value.isJsonObject()) {
            throw new JsonParseException("expected a JSON object");
        }
        JsonObject members = value.getAsJsonObject();
        Map<String, Object> out = LinkedHashMap.newLinkedHashMap(members.size());
        for (Map.Entry<String, JsonElement> member : members.entrySet()) {
            out.put(member.getKey(), read(member.getValue(), valueType));
        }
        return out;
    }

    private static Object readObject(JsonElement value, Class<?> type) {
        if (!value.isJsonObject()) {
            throw new JsonParseException("expected a JSON object for " + type.getName()
                    + ", found " + JsonText.describe(value));
        }
        JsonObject members = value.getAsJsonObject();
        Object instance = instantiate(type);
        Descriptor found = descriptor(type);
        Field[] fields = found.fields;
        Spec[] specs = found.specs;
        for (int at = 0; at < fields.length; at++) {
            Field field = fields[at];
            JsonElement member = members.get(field.getName());
            // Absent leaves the field's initialiser; present-and-null overwrites it.
            if (member != null) {
                Store store = found.stores[at];
                if (store != Store.REFLECTIVE) {
                    if (!member.isJsonNull()) {
                        try {
                            storePrimitive(field, instance, member, specs[at], store);
                        } catch (IllegalAccessException | IllegalArgumentException cannotSet) {
                            throw new JsonParseException("cannot set " + field + " to "
                                    + boxOf(field.getType()).getName(), cannotSet);
                        }
                    }
                } else {
                    Object read = member.isJsonNull() ? null : bind(member, specs[at]);
                    if (!(read == null && field.getType().isPrimitive())) {
                        try {
                            field.set(instance, read);
                        } catch (IllegalAccessException | IllegalArgumentException cannotSet) {
                            throw new JsonParseException("cannot set " + field + " to "
                                    + (read == null ? "null" : read.getClass().getName()),
                                    cannotSet);
                        }
                    }
                }
            }
        }
        return instance;
    }

    private static void storePrimitive(Field field, Object instance, JsonElement member,
            Spec shape, Store store) throws IllegalAccessException {
        if (store == Store.INT) {
            field.setInt(instance, toInt(scalar(member, shape.raw)));
        } else if (store == Store.LONG) {
            field.setLong(instance, toLong(scalar(member, shape.raw)));
        } else if (store == Store.DOUBLE) {
            field.setDouble(instance, toDouble(scalar(member, shape.raw)));
        } else if (store == Store.FLOAT) {
            field.setFloat(instance, (float) toDouble(scalar(member, shape.raw)));
        } else {
            field.setBoolean(instance, scalarBoolean(member, shape.raw).getAsBoolean());
        }
    }

    private static Object instantiate(Class<?> type) {
        Constructor<?> constructor = CONSTRUCTORS.computeIfAbsent(type,
                JsonBind::openConstructor);
        Object instance;
        try {
            instance = constructor.newInstance(NO_ARGS);
        } catch (ReflectiveOperationException | RuntimeException cannotBuild) {
            throw new JsonParseException(type.getName()
                    + " needs a no-argument constructor",
                    cannotBuild);
        }
        return instance;
    }

    private static Constructor<?> openConstructor(Class<?> type) {
        CONSTRUCTOR_LOOKUPS.incrementAndGet();
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor(NO_PARAMS);
            constructor.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException cannotBuild) {
            throw new JsonParseException(type.getName()
                    + " needs a no-argument constructor",
                    cannotBuild);
        }
        return constructor;
    }


    // 3.0 reads as 3 and "7" as 7; 3.5 and values outside int range are refused.
    private static int toInt(JsonElement value) {
        long asLong = toLong(value);
        if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
            throw new JsonParseException("not an int: " + value.getAsString());
        }
        return (int) asLong;
    }

    private static long toLong(JsonElement value) {
        String text = value.getAsString();
        long result = NumberGrammar.decimalLongOr(text, Long.MIN_VALUE);
        if (result != Long.MIN_VALUE) {
            return result;
        }
        boolean finiteJavaDouble = NumberGrammar.isJavaDouble(text)
                && !NumberGrammar.isNonFiniteJavaDouble(text);
        Long exact = NumberGrammar.exactLong(text);
        if (exact != null) {
            return exact;
        }
        RuntimeException refusal = finiteJavaDouble
                ? new ArithmeticException("long overflow")
                : new NumberFormatException("not a whole number: " + text);
        throw new JsonParseException("not a whole number: " + text, refusal);
    }

    private static double toDouble(JsonElement value) {
        TO_DOUBLE_CALLS.incrementAndGet();
        double number;
        try {
            number = value.getAsDouble();
        } catch (NumberFormatException notANumber) {
            throw new JsonParseException("not a number: " + value.getAsString(),
                    notANumber);
        }
        return number;
    }


    private <E extends Exception> void write(Object value, JsonText.Tokens<E> out) throws E {
        out.spill();
        if (value == null) {
            out.nullValue();
        } else {
            Class<?> type = value.getClass();
            switch (WRITE_TAGS.computeIfAbsent(type, JsonBind::writeTag)) {
        case STRING -> {
            if (value instanceof String asText) {
                out.value(asText);
            } else {
                throw new JsonParseException("not a string value: " + type.getName());
            }
        }
        case BOOLEAN -> {
            if (value instanceof Boolean asFlag) {
                out.value(asFlag.booleanValue());
            } else {
                throw new JsonParseException("not a boolean value: " + type.getName());
            }
        }
        case NUMBER -> {
            // A Float is not widened to double.
            if (value instanceof Number asNumber) {
                out.value(asNumber);
            } else {
                throw new JsonParseException("not a number value: " + type.getName());
            }
        }
        case ENUM -> {
            if (value instanceof Enum<?> asConstant) {
                out.value(asConstant.name());
            } else {
                throw new JsonParseException("not an enum value: " + type.getName());
            }
        }
        case LIST -> {
            if (value instanceof List<?> asList) {
                out.beginArray();
                for (Object item : asList) {
                    write(item, out);
                }
                out.endArray();
            } else {
                throw new JsonParseException("not a list value: " + type.getName());
            }
        }
        case REFUSED_COLLECTION -> throw new JsonParseException("unsupported collection type: "
                + type.getName());
        case REFUSED_CHARACTER -> throw new JsonParseException("unsupported field type: "
                + type.getName());
        case MAP -> {
            if (value instanceof Map<?, ?> asMap) {
                boolean namesCanCollide = false;
                for (Object key : asMap.keySet()) {
                    if (!String.class.isInstance(key)) {
                        namesCanCollide = true;
                        break;
                    }
                }
                Map<String, Object> collapsed;
                if (namesCanCollide) {
                    collapsed = LinkedHashMap.newLinkedHashMap(asMap.size());
                    for (Map.Entry<?, ?> member : asMap.entrySet()) {
                        collapsed.put(String.valueOf(member.getKey()), member.getValue());
                    }
                } else {
                    collapsed = null;
                }
                out.beginObject();
                if (collapsed != null) {
                    for (Map.Entry<String, Object> member : collapsed.entrySet()) {
                        out.name(member.getKey());
                        write(member.getValue(), out);
                    }
                } else {
                    for (Map.Entry<?, ?> member : asMap.entrySet()) {
                        if (member.getKey() instanceof String asKey) {
                            out.name(asKey);
                        } else {
                            throw new JsonParseException("not a string map key: "
                                    + type.getName());
                        }
                        write(member.getValue(), out);
                    }
                }
                out.endObject();
            } else {
                throw new JsonParseException("not a map value: " + type.getName());
            }
        }
        case INT_ARRAY -> {
            if (value instanceof int[] asInts) {
                writeValues(out, asInts);
            } else {
                throw new JsonParseException("not an int array value: " + type.getName());
            }
        }
        case LONG_ARRAY -> {
            if (value instanceof long[] asLongs) {
                writeValues(out, asLongs);
            } else {
                throw new JsonParseException("not a long array value: " + type.getName());
            }
        }
        case DOUBLE_ARRAY -> {
            if (value instanceof double[] asDoubles) {
                writeValues(out, asDoubles);
            } else {
                throw new JsonParseException("not a double array value: " + type.getName());
            }
        }
        case FLOAT_ARRAY -> {
            if (value instanceof float[] asFloats) {
                writeValues(out, asFloats);
            } else {
                throw new JsonParseException("not a float array value: " + type.getName());
            }
        }
        case BOOLEAN_ARRAY -> {
            if (value instanceof boolean[] asFlags) {
                writeValues(out, asFlags);
            } else {
                throw new JsonParseException("not a boolean array value: " + type.getName());
            }
        }
        case BYTE_ARRAY -> {
            if (value instanceof byte[] asBytes) {
                writeValues(out, asBytes);
            } else {
                throw new JsonParseException("not a byte array value: " + type.getName());
            }
        }
        case SHORT_ARRAY -> {
            if (value instanceof short[] asHalves) {
                writeValues(out, asHalves);
            } else {
                throw new JsonParseException("not a short array value: " + type.getName());
            }
        }
        case CHAR_ARRAY -> {
            if (value instanceof char[] asSymbols) {
                out.beginArray();
                for (char symbol : asSymbols) {
                    write(Character.valueOf(symbol), out);
                }
                out.endArray();
            } else {
                throw new JsonParseException("not a char array value: " + type.getName());
            }
        }
        case OBJECT_ARRAY -> {
            if (value instanceof Object[] asItems) {
                out.beginArray();
                for (Object item : asItems) {
                    write(item, out);
                }
                out.endArray();
            } else {
                throw new JsonParseException("not an object array value: " + type.getName());
            }
        }
        case OBJECT -> {
            Descriptor found = descriptor(type);
            Field[] fields = found.fields;
            Store[] stores = found.stores;
            out.beginObject();
            for (int at = 0; at < fields.length; at++) {
                Field field = fields[at];
                if (stores[at] != Store.REFLECTIVE) {
                    switch (stores[at]) {
                case BOOLEAN -> {
                    boolean flag;
                    try {
                        flag = field.getBoolean(value);
                    } catch (IllegalAccessException cannotRead) {
                        throw new JsonParseException("cannot read " + field, cannotRead);
                    }
                    out.name(field.getName());
                    out.value(flag);
                }
                case INT -> {
                    int whole;
                    try {
                        whole = field.getInt(value);
                    } catch (IllegalAccessException cannotRead) {
                        throw new JsonParseException("cannot read " + field, cannotRead);
                    }
                    out.name(field.getName());
                    out.value(whole);
                }
                case LONG -> {
                    long span;
                    try {
                        span = field.getLong(value);
                    } catch (IllegalAccessException cannotRead) {
                        throw new JsonParseException("cannot read " + field, cannotRead);
                    }
                    out.name(field.getName());
                    out.value(span);
                }
                case FLOAT -> {
                    float ratio;
                    try {
                        ratio = field.getFloat(value);
                    } catch (IllegalAccessException cannotRead) {
                        throw new JsonParseException("cannot read " + field, cannotRead);
                    }
                    out.name(field.getName());
                    out.value(ratio);
                }
                case DOUBLE -> {
                    double scale;
                    try {
                        scale = field.getDouble(value);
                    } catch (IllegalAccessException cannotRead) {
                        throw new JsonParseException("cannot read " + field, cannotRead);
                    }
                    out.name(field.getName());
                    out.value(scale);
                }
                    case REFLECTIVE -> {
                    }
                }
                } else {
                    Object held;
                    try {
                        held = field.get(value);
                    } catch (IllegalAccessException cannotRead) {
                        throw new JsonParseException("cannot read " + field, cannotRead);
                    }
                    if (!(held == null && !writeNulls)) {
                        out.name(field.getName());
                        write(held, out);
                    }
                }
            }
            out.endObject();
            }
        }
        }
    }

    private static <E extends Exception> void writeValues(JsonText.Tokens<E> out, int[] values)
            throws E {
        out.beginArray();
        for (int value : values) {
            out.spill();
            out.value(value);
        }
        out.endArray();
    }

    private static <E extends Exception> void writeValues(JsonText.Tokens<E> out, long[] values)
            throws E {
        out.beginArray();
        for (long value : values) {
            out.spill();
            out.value(value);
        }
        out.endArray();
    }

    private static <E extends Exception> void writeValues(JsonText.Tokens<E> out,
            double[] values) throws E {
        out.beginArray();
        for (double value : values) {
            out.spill();
            out.value(value);
        }
        out.endArray();
    }

    private static <E extends Exception> void writeValues(JsonText.Tokens<E> out, float[] values)
            throws E {
        out.beginArray();
        for (float value : values) {
            out.spill();
            out.value(value);
        }
        out.endArray();
    }

    private static <E extends Exception> void writeValues(JsonText.Tokens<E> out,
            boolean[] values) throws E {
        out.beginArray();
        for (boolean value : values) {
            out.spill();
            out.value(value);
        }
        out.endArray();
    }

    private static <E extends Exception> void writeValues(JsonText.Tokens<E> out, byte[] values)
            throws E {
        out.beginArray();
        for (byte value : values) {
            out.spill();
            out.value(value);
        }
        out.endArray();
    }

    private static <E extends Exception> void writeValues(JsonText.Tokens<E> out, short[] values)
            throws E {
        out.beginArray();
        for (short value : values) {
            out.spill();
            out.value(value);
        }
        out.endArray();
    }

    private static WriteTag writeTag(Class<?> type) {
        WRITE_TAG_RESOLUTIONS.incrementAndGet();
        WriteTag tag;
        if (type == String.class) {
            tag = WriteTag.STRING;
        } else if (type == Boolean.class) {
            tag = WriteTag.BOOLEAN;
        } else if (type == Character.class) {
            tag = WriteTag.REFUSED_CHARACTER;
        } else if (Number.class.isAssignableFrom(type)) {
            tag = WriteTag.NUMBER;
        } else if (Enum.class.isAssignableFrom(type)) {
            tag = WriteTag.ENUM;
        } else if (List.class.isAssignableFrom(type)) {
            tag = WriteTag.LIST;
        } else if (Collection.class.isAssignableFrom(type)) {
            tag = WriteTag.REFUSED_COLLECTION;
        } else if (Map.class.isAssignableFrom(type)) {
            tag = WriteTag.MAP;
        } else if (type.isArray()) {
            tag = arrayTag(type.getComponentType());
        } else {
            tag = WriteTag.OBJECT;
        }
        return tag;
    }

    private static WriteTag arrayTag(Class<?> component) {
        WriteTag tag;
        if (component == int.class) {
            tag = WriteTag.INT_ARRAY;
        } else if (component == long.class) {
            tag = WriteTag.LONG_ARRAY;
        } else if (component == double.class) {
            tag = WriteTag.DOUBLE_ARRAY;
        } else if (component == float.class) {
            tag = WriteTag.FLOAT_ARRAY;
        } else if (component == boolean.class) {
            tag = WriteTag.BOOLEAN_ARRAY;
        } else if (component == byte.class) {
            tag = WriteTag.BYTE_ARRAY;
        } else if (component == short.class) {
            tag = WriteTag.SHORT_ARRAY;
        } else if (component == char.class) {
            tag = WriteTag.CHAR_ARRAY;
        } else {
            tag = WriteTag.OBJECT_ARRAY;
        }
        return tag;
    }

    private enum WriteTag {
        STRING, BOOLEAN, REFUSED_CHARACTER, NUMBER, ENUM, LIST, REFUSED_COLLECTION, MAP,
        INT_ARRAY, LONG_ARRAY, DOUBLE_ARRAY, FLOAT_ARRAY, BOOLEAN_ARRAY, BYTE_ARRAY,
        SHORT_ARRAY, CHAR_ARRAY, OBJECT_ARRAY, OBJECT
    }

    private static Descriptor descriptor(Class<?> type) {
        Descriptor cached = lastDescriptor;
        if (cached != null && cached.type == type) {
            return cached;
        }
        DESCRIPTOR_PROBES.incrementAndGet();
        Descriptor found = FIELDS.get(type);
        if (found == null) {
            found = FIELDS.computeIfAbsent(type, JsonBind::discover);
        }
        lastDescriptor = found;
        return found;
    }

    private static Map<String, Object> enumConstants(Class<?> type) {
        Map<String, Object> constants = new LinkedHashMap<>();
        for (Object constant : type.getEnumConstants()) {
            if (constant instanceof Enum<?> named) {
                constants.put(named.name(), constant);
            } else {
                throw new JsonParseException("not an enum constant: " + type.getName());
            }
        }
        return Collections.unmodifiableMap(constants);
    }

    private static Descriptor discover(Class<?> type) {
        Field[] declared = type.getDeclaredFields();
        List<Field> out = new ArrayList<>(declared.length);
        for (Field field : declared) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
                    || field.isSynthetic()) {
                continue;
            }
            try {
                field.setAccessible(true);
            } catch (RuntimeException cannotOpen) {
                throw new JsonParseException("cannot open field " + field.getName()
                        + " of " + type.getName() + " for JSON", cannotOpen);
            }
            out.add(field);
        }
        Field[] fields = out.toArray(new Field[0]);
        Spec[] specs = new Spec[fields.length];
        Store[] stores = new Store[fields.length];
        Map<String, Integer> byName = HashMap.newHashMap(fields.length);
        for (int at = 0; at < fields.length; at++) {
            specs[at] = KINDS.computeIfAbsent(fields[at].getGenericType(),
                    JsonBind::specOf);
            stores[at] = storeOf(fields[at].getType());
            byName.put(fields[at].getName(), at);
        }
        return new Descriptor(type, fields, specs, stores, byName);
    }

    private static Store storeOf(Class<?> declared) {
        Store store;
        if (declared == int.class) {
            store = Store.INT;
        } else if (declared == long.class) {
            store = Store.LONG;
        } else if (declared == double.class) {
            store = Store.DOUBLE;
        } else if (declared == float.class) {
            store = Store.FLOAT;
        } else if (declared == boolean.class) {
            store = Store.BOOLEAN;
        } else {
            store = Store.REFLECTIVE;
        }
        return store;
    }

    private enum Store {
        REFLECTIVE, INT, LONG, DOUBLE, FLOAT, BOOLEAN
    }

    private static final class Spec {

        private final Kind kind;

        private final Class<?> raw;

        private final Type argument;

        private final String refusal;

        private Spec(Kind kind, Class<?> raw, Type argument, String refusal) {
            this.kind = kind;
            this.raw = raw;
            this.argument = argument;
            this.refusal = refusal;
        }
    }

    private static final class Descriptor {

        private final Class<?> type;

        private final Field[] fields;

        private final Spec[] specs;

        private final Store[] stores;

        // Which slot a member name fills.
        private final Map<String, Integer> byName;

        private Descriptor(Class<?> type, Field[] fields, Spec[] specs, Store[] stores,
                Map<String, Integer> byName) {
            this.type = type;
            this.fields = fields;
            this.specs = specs;
            this.stores = stores;
            this.byName = byName;
        }
    }

    // Binder(Spec) binds one value;
    // Binder(Spec, null) collects a JSON array into a list.
    // Binder(Spec, each) hands each array element to each.
    private static final class Binder implements JsonParser.Sink {

        private static final int CHUNK = 512;

        private static final int INITIAL_STACK_DEPTH = 8;

        private static final int STACK_GROWTH_FACTOR = 2;

        private static final Spec DOUBLE_ELEMENT =
                new Spec(Kind.DOUBLE, double.class, null, null);

        private static final Spec INT_ELEMENT = new Spec(Kind.INT, int.class, null, null);

        private static final Spec LONG_ELEMENT = new Spec(Kind.LONG, long.class, null, null);

        private static final Spec FLOAT_ELEMENT =
                new Spec(Kind.FLOAT, float.class, null, null);

        private static final Spec BOOLEAN_ELEMENT =
                new Spec(Kind.BOOLEAN, boolean.class, null, null);

        private final Spec rootSpec;

        private final Consumer<Object> each;

        private final boolean arrayRoot;

        private Frame[] stack = new Frame[INITIAL_STACK_DEPTH];

        private int depth;

        private int skipping;

        private boolean skipValue;

        private Object bound;

        private List<Object> items;

        private boolean arrayPresent;

        private int seen;

        Binder(Spec rootSpec) {
            this(rootSpec, null, false);
        }

        Binder(Spec elementSpec, Consumer<Object> each) {
            this(elementSpec, each, true);
        }

        private Binder(Spec rootSpec, Consumer<Object> each, boolean arrayRoot) {
            this.rootSpec = rootSpec;
            this.each = each;
            this.arrayRoot = arrayRoot;
        }

        @Override
        public void beginObject() {
            if (skipping > 0 || skipValue) {
                skipping++;
                skipValue = false;
            } else if (depth == 0 && arrayRoot) {
                throw new JsonParseException("expected a JSON array, found object");
            } else {
                countWalk();
                Spec shape = expected();
                switch (shape.kind) {
            case MAP -> {
                Frame frame = push(FrameKind.MAP_FRAME, shape);
                frame.target = new LinkedHashMap<String, Object>();
                frame.child = KINDS.computeIfAbsent(shape.argument, JsonBind::specOf);
            }
            case OBJECT -> {
                Object instance = instantiate(shape.raw);
                Descriptor found = descriptor(shape.raw);
                Frame frame = push(FrameKind.POJO, shape);
                frame.target = instance;
                frame.descriptor = found;
                frame.slot = -1;
            }
            case REFUSE -> throw new JsonParseException(shape.refusal);
            case LIST -> throw new JsonParseException("expected a JSON array");
            case ARRAY, DOUBLE_ARRAY ->
                    throw new JsonParseException("expected a JSON array for " + shape.raw);
            case STRING, INT, LONG, DOUBLE, FLOAT, BOOLEAN, ENUM ->
                    throw new JsonParseException("expected a JSON primitive for "
                            + shape.raw.getName() + ", found object");
                }
            }
        }

        @Override
        public void beginArray() {
            if (skipping > 0 || skipValue) {
                skipping++;
                skipValue = false;
            } else if (depth == 0 && arrayRoot) {
                arrayPresent = true;
                Frame frame = push(FrameKind.ROOT, rootSpec);
                frame.child = rootSpec;
                if (each == null) {
                    items = new ArrayList<>();
                    frame.target = items;
                }
            } else {
                countWalk();
                Spec shape = expected();
                switch (shape.kind) {
            case LIST -> {
                Frame frame = push(FrameKind.LIST_FRAME, shape);
                frame.target = new ArrayList<>();
                frame.child = KINDS.computeIfAbsent(shape.argument, JsonBind::specOf);
            }
            case DOUBLE_ARRAY -> {
                openArray(shape, FrameKind.DOUBLES, double.class, DOUBLE_ELEMENT);
            }
            case ARRAY -> {
                Class<?> component = shape.raw.getComponentType();
                if (component == int.class) {
                    openArray(shape, FrameKind.INTS, component, INT_ELEMENT);
                } else if (component == long.class) {
                    openArray(shape, FrameKind.LONGS, component, LONG_ELEMENT);
                } else if (component == float.class) {
                    openArray(shape, FrameKind.FLOATS, component, FLOAT_ELEMENT);
                } else if (component == boolean.class) {
                    openArray(shape, FrameKind.BOOLEANS, component, BOOLEAN_ELEMENT);
                } else {
                    Frame frame = push(FrameKind.OBJECTS, shape);
                    frame.component = component;
                    frame.child = KINDS.computeIfAbsent(component, JsonBind::specOf);
                    frame.target = new ArrayList<>();
                }
            }
            case MAP -> throw new JsonParseException("expected a JSON object");
            case REFUSE -> throw new JsonParseException(shape.refusal);
            case OBJECT -> throw new JsonParseException("expected a JSON object for "
                    + shape.raw.getName() + ", found array");
            case STRING, INT, LONG, DOUBLE, FLOAT, BOOLEAN, ENUM ->
                    throw new JsonParseException("expected a JSON primitive for "
                            + shape.raw.getName() + ", found array");
                }
            }
        }

        private void openArray(Spec shape, FrameKind kind, Class<?> component, Spec child) {
            Frame frame = push(kind, shape);
            frame.component = component;
            frame.child = child;
            if (frame.chunk == null
                    || frame.chunk.getClass().getComponentType() != component) {
                frame.chunk = Array.newInstance(component, CHUNK);
            }
        }

        private static Object room(Frame top) {
            if (top.slot == Array.getLength(top.chunk)) {
                if (top.spilled == null) {
                    top.spilled = new ArrayList<>();
                }
                top.spilled.add(top.chunk);
                top.spilledCount += top.slot;
                top.chunk = Array.newInstance(top.component, CHUNK);
                top.slot = 0;
            }
            return top.chunk;
        }

        private static Object assembled(Frame top) {
            int total = top.spilledCount + top.slot;
            Object out = Array.newInstance(top.component, total);
            int at = 0;
            if (top.spilled != null) {
                for (Object each : top.spilled) {
                    int held = Array.getLength(each);
                    System.arraycopy(each, 0, out, at, held);
                    at += held;
                }
            }
            System.arraycopy(top.chunk, 0, out, at, top.slot);
            return out;
        }

        @Override
        public void name(String name) {
            if (skipping > 0) {
                return;
            }
            Frame top = stack[depth - 1];
            if (top.kind == FrameKind.MAP_FRAME) {
                top.name = name;
                return;
            }
            Integer at = top.descriptor.byName.get(name);
            if (at == null) {

                skipValue = true;
                return;
            }
            top.slot = at.intValue();
        }

        @Override
        public void stringValue(String text) {
            value(JsonPrimitive.ofString(text));
        }

        @Override
        public void numberValue(String token) {
            value(JsonPrimitive.ofNumberToken(token));
        }

        @Override
        public void booleanValue(boolean flag) {
            value(JsonPrimitive.ofBoolean(flag));
        }

        @Override
        public void nullValue() {
            value(JsonNull.INSTANCE);
        }

        private void value(JsonElement token) {
            if (skipping > 0) {
            } else if (skipValue) {
                skipValue = false;
            } else {
                boolean absent = token.isJsonNull();
                if (depth == 0) {
                    if (arrayRoot) {
                        if (!absent) {
                            throw new JsonParseException("expected a JSON array, found "
                                    + JsonText.describe(token));
                        }
                    } else {
                        bound = absent ? null : readAs(token, rootSpec);
                    }
                } else {
                    Frame top = stack[depth - 1];
                    switch (top.kind) {
            case POJO -> {
                storeMember(top, token, absent);
            }
            case MAP_FRAME -> {
                map(top).put(top.name, absent ? null : readAs(token, top.child));
            }
            case LIST_FRAME -> {
                list(top).add(absent ? null : readAs(token, top.child));
            }
            case ROOT -> {
                hand(absent ? null : readAs(token, top.child));
            }
            case OBJECTS -> {
                Object read = absent ? null : readAs(token, top.child);
                if (read == null && top.component.isPrimitive()) {

                    throw nullSlot(top);
                }
                list(top).add(read);
                top.slot++;
            }
            case DOUBLES -> {
                if (absent) {
                    throw nullSlot(top);
                }
                Object chunk = room(top);
                if (chunk instanceof double[] asDoubles) {
                    asDoubles[top.slot++] = toDouble(scalar(token, double.class));
                } else {
                    throw new JsonParseException("not a double array chunk: "
                            + chunk.getClass().getName());
                }
            }
            case INTS -> {
                if (absent) {
                    throw nullSlot(top);
                }
                Object chunk = room(top);
                if (chunk instanceof int[] asInts) {
                    asInts[top.slot++] = toInt(scalar(token, int.class));
                } else {
                    throw new JsonParseException("not an int array chunk: "
                            + chunk.getClass().getName());
                }
            }
            case LONGS -> {
                if (absent) {
                    throw nullSlot(top);
                }
                Object chunk = room(top);
                if (chunk instanceof long[] asLongs) {
                    asLongs[top.slot++] = toLong(scalar(token, long.class));
                } else {
                    throw new JsonParseException("not a long array chunk: "
                            + chunk.getClass().getName());
                }
            }
            case FLOATS -> {
                if (absent) {
                    throw nullSlot(top);
                }
                Object chunk = room(top);
                if (chunk instanceof float[] asFloats) {
                    asFloats[top.slot++] = (float) toDouble(scalar(token, float.class));
                } else {
                    throw new JsonParseException("not a float array chunk: "
                            + chunk.getClass().getName());
                }
            }
            case BOOLEANS -> {
                if (absent) {
                    throw nullSlot(top);
                }
                Object chunk = room(top);
                if (chunk instanceof boolean[] asFlags) {
                    asFlags[top.slot++] = scalarBoolean(token, boolean.class).getAsBoolean();
                } else {
                    throw new JsonParseException("not a boolean array chunk: "
                            + chunk.getClass().getName());
                }
                    }
                }
            }
        }
        }

        @Override
        public void endObject() {
            if (skipping > 0) {
                skipping--;
                return;
            }
            accept(stack[--depth].target);
        }

        @Override
        public void endArray() {
            if (skipping > 0) {
                skipping--;
                return;
            }
            Frame top = stack[--depth];
            if (top.kind == FrameKind.ROOT) {
                return;
            }
            accept(closed(top));
        }

        private Object closed(Frame top) {
            return switch (top.kind) {
            case LIST_FRAME -> top.target;
            case DOUBLES, INTS, LONGS, FLOATS, BOOLEANS -> assembled(top);
            case OBJECTS, MAP_FRAME, POJO, ROOT -> {
                List<Object> held = list(top);
                int n = held.size();
                Object out = Array.newInstance(top.component, n);
                if (top.component.isPrimitive()) {
                    for (int at = 0; at < n; at++) {
                        Array.set(out, at, held.get(at));
                    }
                } else if (out instanceof Object[] members) {
                    for (int at = 0; at < n; at++) {
                        members[at] = held.get(at);
                    }
                } else {
                    throw new JsonParseException("not an object array chunk: "
                            + out.getClass().getName());
                }
                yield out;
            }
            };
        }

        private void storeMember(Frame top, JsonElement token, boolean absent) {
            int at = top.slot;
            Field field = top.descriptor.fields[at];
            Store store = top.descriptor.stores[at];
            if (store != Store.REFLECTIVE) {
                if (!absent) {
                    try {
                        storePrimitive(field, top.target, token, top.descriptor.specs[at],
                                store);
                    } catch (IllegalAccessException | IllegalArgumentException cannotSet) {
                        throw new JsonParseException("cannot set " + field + " to "
                                + boxOf(field.getType()).getName(), cannotSet);
                    }
                }
            } else {
                set(top.target, field, absent ? null : bind(token, top.descriptor.specs[at]));
            }
        }

        private void accept(Object value) {
            if (depth == 0) {
                bound = value;
            } else {
                Frame top = stack[depth - 1];
                switch (top.kind) {
            case POJO -> {
                set(top.target, top.descriptor.fields[top.slot], value);
            }
            case MAP_FRAME -> {
                map(top).put(top.name, value);
            }
            case LIST_FRAME -> {
                list(top).add(value);
            }
            case ROOT -> {
                hand(value);
            }
            case OBJECTS, DOUBLES, INTS, LONGS, FLOATS, BOOLEANS -> {
                list(top).add(value);
                top.slot++;
                }
            }
            }
            }

        private void hand(Object value) {
            seen++;
            if (each == null) {
                items.add(value);
            } else {
                each.accept(value);
            }
        }

        private static void set(Object instance, Field field, Object read) {
            if (read == null && field.getType().isPrimitive()) {
                return;
            }
            try {
                field.set(instance, read);
            } catch (IllegalAccessException | IllegalArgumentException cannotSet) {
                throw new JsonParseException("cannot set " + field + " to "
                        + (read == null ? "null" : read.getClass().getName()), cannotSet);
            }
        }

        private static JsonParseException nullSlot(Frame top) {
            return new JsonParseException("expected a " + top.component.getName()
                    + " at index " + (top.spilledCount + top.slot) + " of a "
                    + top.raw.getSimpleName() + ", found null");
        }

        private Spec expected() {
            if (depth == 0) {
                return rootSpec;
            }
            Frame top = stack[depth - 1];
            return top.kind == FrameKind.POJO ? top.descriptor.specs[top.slot] : top.child;
        }

        private void countWalk() {
            if (depth == 0) {
                if (!arrayRoot) {
                    TYPE_CHAIN_READS.incrementAndGet();
                }
                return;
            }
            if (countsTypeChainRead(stack[depth - 1].kind)) {
                TYPE_CHAIN_READS.incrementAndGet();
            }
        }

        private static boolean countsTypeChainRead(FrameKind kind) {
            return switch (kind) {
            case MAP_FRAME, LIST_FRAME, OBJECTS, ROOT -> true;
            case POJO, DOUBLES, INTS, LONGS, FLOATS, BOOLEANS -> false;
            };
        }

        private Frame push(FrameKind kind, Spec shape) {
            if (depth == stack.length) {
                stack = Arrays.copyOf(stack, stack.length * STACK_GROWTH_FACTOR);
            }
            Frame frame = stack[depth];
            if (frame == null) {
                frame = new Frame();
                stack[depth] = frame;
            }
            depth++;
            frame.kind = kind;
            frame.raw = shape.raw;
            frame.child = null;
            frame.target = null;
            frame.descriptor = null;
            frame.component = null;
            frame.slot = 0;
            frame.name = null;
            frame.spilled = null;
            frame.spilledCount = 0;
            return frame;
        }

        @SuppressWarnings("unchecked")
        private static List<Object> list(Frame frame) {
            return (List<Object>) frame.target;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> map(Frame frame) {
            return (Map<String, Object>) frame.target;
        }
    }

    private enum FrameKind {
        POJO, MAP_FRAME, LIST_FRAME, OBJECTS, DOUBLES, INTS, LONGS, FLOATS, BOOLEANS, ROOT
    }

    // One open container, reused across siblings.
    private static final class Frame {

        private FrameKind kind;

        private Spec child;

        private Class<?> raw;

        private Class<?> component;

        private Object target;

        private Descriptor descriptor;

        private int slot;

        private String name;

        private Object chunk;

        private List<Object> spilled;

        private int spilledCount;
    }

    private static final class Tree implements JsonText.Tokens<RuntimeException> {

        private final List<JsonElement> open = new ArrayList<>();

        private JsonElement root;

        private String name;

        @Override
        public void beginArray() {
            begin(new JsonArray());
        }

        @Override
        public void endArray() {
            open.remove(open.size() - 1);
        }

        @Override
        public void beginObject() {
            begin(new JsonObject());
        }

        @Override
        public void endObject() {
            open.remove(open.size() - 1);
        }

        @Override
        public void name(String name) {
            this.name = name;
        }

        @Override
        public void value(String text) {
            add(JsonPrimitive.ofString(text));
        }

        @Override
        public void value(Number number) {
            add(JsonPrimitive.ofNumber(number));
        }

        @Override
        public void value(boolean flag) {
            add(JsonPrimitive.ofBoolean(flag));
        }

        @Override
        public void nullValue() {
            add(JsonNull.INSTANCE);
        }

        @Override
        public void spill() {
        }

        private void begin(JsonElement container) {
            if (open.size() >= JsonParser.MAX_DEPTH) {
                throw new JsonText.TooDeep("nesting deeper than " + JsonParser.MAX_DEPTH);
            }
            add(container);
            open.add(container);
        }

        private void add(JsonElement value) {
            if (open.isEmpty()) {
                root = value;
            } else {
                JsonElement top = open.get(open.size() - 1);
                if (top instanceof JsonObject object) {
                    object.add(name, value);
                } else if (top instanceof JsonArray array) {
                    array.add(value);
                } else {
                    throw new JsonParseException("not a container: "
                            + top.getClass().getName());
                }
            }
        }
    }
}
