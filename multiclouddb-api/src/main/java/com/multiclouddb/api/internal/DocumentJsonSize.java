// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Validates neutral values and counts the legacy JSON byte profile without a tree copy. */
final class DocumentJsonSize {
    // Released Map -> Jackson tree -> bytes serialization rejects depth above 1000.
    private static final int MAX_DEPTH = 1000;
    private final long limit;
    private long size;
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();

    private DocumentJsonSize(long limit) {
        this.limit = limit;
    }

    static long measure(Map<String, Object> document, long limit) {
        DocumentJsonSize counter = new DocumentJsonSize(limit);
        counter.value(document);
        while (!counter.stack.isEmpty()) {
            Frame frame = counter.stack.peek();
            if (!frame.children.hasNext()) {
                counter.active.remove(frame.container);
                counter.stack.pop();
                continue;
            }
            if (!frame.first) counter.add(1);
            frame.first = false;
            Object child = frame.children.next();
            if (frame.container instanceof Map<?, ?>) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) child;
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Document keys must be non-null strings.");
                }
                counter.string(key);
                counter.add(1);
                child = entry.getValue();
            }
            counter.value(child);
        }
        return counter.size;
    }

    private void add(long bytes) {
        size += bytes;
        if (size > limit) throw new SizeExceeded(size);
    }

    private void value(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            if (active.containsKey(value)) {
                throw new IllegalArgumentException("Document contains a cycle.");
            }
            if (stack.size() >= MAX_DEPTH) {
                throw new IllegalArgumentException("Document exceeds the existing JSON nesting limit.");
            }
            add(2);
            active.put(value, Boolean.TRUE);
            Iterator<?> children = value instanceof Map<?, ?> map
                    ? map.entrySet().iterator() : ((List<?>) value).iterator();
            stack.push(new Frame(value, children));
        } else if (value instanceof String text) {
            string(text);
        } else if (value instanceof Number number) {
            Class<?> kind = number.getClass();
            if (kind != Byte.class && kind != Short.class && kind != Integer.class
                    && kind != Long.class && kind != Float.class && kind != Double.class
                    && kind != BigInteger.class && kind != BigDecimal.class) {
                throw new IllegalArgumentException("Unsupported document number implementation.");
            }
            if (number instanceof Float f && !Float.isFinite(f)
                    || number instanceof Double d && !Double.isFinite(d)) {
                throw new IllegalArgumentException("Document numbers must be finite.");
            }
            // The old default mapper's Map -> tree size profile normalizes
            // BigDecimal zeros. This count does not modify the caller's value.
            String text = number instanceof BigDecimal decimal
                    ? decimal.stripTrailingZeros().toString() : number.toString();
            add(text.length());
        } else if (value instanceof Boolean bool) {
            add(bool ? 4 : 5);
        } else if (value == null) {
            add(4);
        } else {
            throw new IllegalArgumentException("Unsupported document value.");
        }
    }

    private static final class Frame {
        final Object container;
        final Iterator<?> children;
        boolean first = true;

        Frame(Object container, Iterator<?> children) {
            this.container = container;
            this.children = children;
        }
    }

    private void string(String text) {
        add(2);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\' || c == '\b' || c == '\f'
                    || c == '\n' || c == '\r' || c == '\t') {
                add(2);
            } else if (c < 0x20 || Character.isSurrogate(c)) {
                add(6);
            } else {
                add(c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3);
            }
        }
    }

    static final class SizeExceeded extends IllegalArgumentException {
        final long observedBytes;

        SizeExceeded(long observedBytes) {
            super("Document exceeds maximum logical JSON size.");
            this.observedBytes = observedBytes;
        }
    }

}
