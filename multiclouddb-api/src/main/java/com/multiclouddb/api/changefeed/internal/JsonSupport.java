// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** Internal cursor JSON boundary for callback invocation and neutral-value validation. */
final class JsonSupport {
    private JsonSupport() {}

    /**
     * Invoke a neutral-value JSON writer without propagating application messages or causes.
     * @param value neutral value to encode
     * @param serializer application JSON writer
     * @return non-null JSON text
     * @throws IOException on a declared processing failure
     * @throws IllegalStateException on another callback failure or a null result
     */
    static String write(Object value, Function<Object, String> serializer) throws IOException {
        Objects.requireNonNull(serializer, "JSON serializer is required");
        String result;
        try {
            result = serializer.apply(value);
        } catch (UncheckedIOException failure) {
            throw new ProcessingFailure("JSON serialize failed: callback threw " + failure.getClass().getName() + ".");
        } catch (RuntimeException failure) {
            throw contractFailure("serialize", "callback threw " + failure.getClass().getName());
        }
        if (result == null) throw contractFailure("serialize", "null serializer result");
        return result;
    }

    /**
     * Read and validate a cursor callback's neutral result without copying containers.
     * The returned graph must not be modified until cursor decoding completes.
     * @param json JSON text
     * @param deserializer application reader receiving Object.class
     * @return the original validated neutral value, possibly null
     * @throws IOException on a declared processing failure or leading BOM
     * @throws IllegalStateException on a callback contract failure
     */
    static Object read(String json, BiFunction<String, Class<?>, Object> deserializer)
            throws IOException {
        return validated(invoke(json, Object.class, deserializer));
    }

    private static Object invoke(String json, Class<?> target,
                                 BiFunction<String, Class<?>, Object> deserializer) throws IOException {
        Objects.requireNonNull(deserializer, "JSON deserializer is required");
        // The released String-based parser rejects a leading BOM, not BOMs inside strings.
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
            throw new ProcessingFailure("JSON deserialize failed: leading BOM.");
        }
        try {
            return deserializer.apply(json, target);
        } catch (UncheckedIOException failure) {
            throw new ProcessingFailure("JSON deserialize failed: callback threw " + failure.getClass().getName() + ".");
        } catch (RuntimeException failure) {
            throw contractFailure("deserialize", "callback threw " + failure.getClass().getName());
        }
    }

    static String processingFailureDetail(IOException failure) {
        // Only this private final type carries an SDK-generated diagnostic.
        return failure instanceof ProcessingFailure ? failure.getMessage() : "JSON processing failed.";
    }

    private static final class ProcessingFailure extends IOException {
        private ProcessingFailure(String diagnostic) {
            super(diagnostic);
        }
    }

    private record Frame(Object container, Iterator<?> children) {}

    /** Guard only application-owned value access, not SDK semantic validation. */
    static <T> T access(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            throw contractFailure("deserialize", "result access threw " + failure.getClass().getName());
        }
    }

    private static Object validated(Object root) {
        var active = new IdentityHashMap<Object, Boolean>();
        var stack = new ArrayDeque<Frame>();
        Object value = root;
        while (true) {
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                if (active.put(value, true) != null) throw contractFailure("deserialize", "cycle");
                Object container = value;
                Iterator<?> children = access(() -> container instanceof Map<?, ?> map
                        ? map.entrySet().iterator() : ((List<?>) container).iterator());
                stack.push(new Frame(value, children));
            } else if (value == null || value instanceof String || value instanceof Boolean
                    || value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof Float || value instanceof Double
                    || value instanceof BigInteger || value instanceof BigDecimal) {
                // Read-side overflow/number policy belongs to the compatible reader, not write validation.
            } else {
                throw contractFailure("deserialize", "unsupported neutral value");
            }
            while (!stack.isEmpty() && !access(() -> stack.peek().children().hasNext())) {
                active.remove(stack.pop().container());
            }
            if (stack.isEmpty()) return root;
            Frame frame = stack.peek();
            Object child = access(() -> frame.children().next());
            if (frame.container() instanceof Map<?, ?>) {
                Map.Entry<?, ?> entry = access(() -> (Map.Entry<?, ?>) child);
                if (!(access(() -> entry.getKey()) instanceof String)) {
                    throw contractFailure("deserialize", "non-string map key");
                }
                value = access(() -> entry.getValue());
            } else {
                value = child;
            }
        }
    }

    private static IllegalStateException contractFailure(String stage, String reason) {
        return new IllegalStateException("Application JSON " + stage + " failed: " + reason + ".");
    }
}
