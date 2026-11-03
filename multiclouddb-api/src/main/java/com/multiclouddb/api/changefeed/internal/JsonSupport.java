// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/** Cursor JSON callback invocation without exposing parser exceptions or trees. */
public final class JsonSupport {
    private JsonSupport() {}

    /**
     * Invoke a neutral-value JSON writer without propagating application exception details.
     * @param value neutral value to encode
     * @param serializer application JSON writer
     * @return non-null JSON text
     * @throws IOException on a declared processing failure
     * @throws IllegalStateException on another callback failure or a null result
     */
    public static String write(Object value, Function<Object, String> serializer) throws IOException {
        Objects.requireNonNull(serializer, "JSON serializer is required");
        String result;
        try {
            result = serializer.apply(value);
        } catch (UncheckedIOException failure) {
            throw new IOException("JSON serialization failed.");
        } catch (RuntimeException failure) {
            throw contractFailure();
        }
        if (result == null) throw contractFailure();
        return result;
    }

    /**
     * Read an Object root and detach its validated neutral containers.
     * @param json JSON text
     * @param deserializer application reader receiving Object.class
     * @return detached neutral value, possibly null
     * @throws IOException on a declared processing failure or leading BOM
     * @throws IllegalStateException on a callback contract failure
     */
    public static Object read(String json, BiFunction<String, Class<?>, Object> deserializer)
            throws IOException {
        return detached(invoke(json, Object.class, deserializer));
    }

    private static Object invoke(String json, Class<?> target,
                                 BiFunction<String, Class<?>, Object> deserializer) throws IOException {
        Objects.requireNonNull(deserializer, "JSON deserializer is required");
        // The released String-based parser rejects a leading BOM, not BOMs inside strings.
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') throw new IOException("Unexpected JSON BOM.");
        try {
            return deserializer.apply(json, target);
        } catch (UncheckedIOException failure) {
            throw new IOException("JSON parsing failed.");
        } catch (RuntimeException failure) {
            throw contractFailure();
        }
    }

    private record Copy(Object source, Consumer<Object> accept, boolean exit) {}

    private static Object detached(Object root) {
        Object[] result = new Object[1];
        var active = new IdentityHashMap<Object, Boolean>();
        var pending = new ArrayDeque<Copy>();
        pending.push(new Copy(root, value -> result[0] = value, false));
        while (!pending.isEmpty()) {
            Copy step = pending.pop();
            Object value = step.source();
            if (step.exit()) {
                active.remove(value);
            } else if (value instanceof Map<?, ?> map) {
                if (active.put(value, true) != null) throw contractFailure();
                var copy = new LinkedHashMap<String, Object>();
                step.accept().accept(copy);
                pending.push(new Copy(value, ignored -> {}, true));
                var entries = new ArrayList<>(map.entrySet());
                for (var entry : entries) {
                    if (!(entry.getKey() instanceof String key)) throw contractFailure();
                    copy.put(key, null);
                }
                for (int i = entries.size() - 1; i >= 0; i--) {
                    var entry = entries.get(i);
                    String key = String.class.cast(entry.getKey());
                    pending.push(new Copy(entry.getValue(), item -> copy.put(key, item), false));
                }
            } else if (value instanceof List<?> list) {
                if (active.put(value, true) != null) throw contractFailure();
                var copy = new ArrayList<Object>(java.util.Collections.nCopies(list.size(), null));
                step.accept().accept(copy);
                pending.push(new Copy(value, ignored -> {}, true));
                var iterator = list.listIterator(list.size());
                while (iterator.hasPrevious()) {
                    int index = iterator.previousIndex();
                    pending.push(new Copy(iterator.previous(), item -> copy.set(index, item), false));
                }
            } else if (value == null || value instanceof String || value instanceof Boolean
                    || value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof Float || value instanceof Double
                    || value instanceof BigInteger || value instanceof BigDecimal) {
                // Read-side overflow/number policy belongs to the compatible reader, not write validation.
                step.accept().accept(value);
            } else {
                throw contractFailure();
            }
        }
        return result[0];
    }

    private static IllegalStateException contractFailure() {
        return new IllegalStateException("Application JSON callback violated its contract.");
    }
}
