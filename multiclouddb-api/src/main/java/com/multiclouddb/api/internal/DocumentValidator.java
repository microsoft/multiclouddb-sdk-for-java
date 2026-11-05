// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Validates write input types and document nesting before provider delegation.
 * The root Map is depth zero; each nested Map or List adds one level, including
 * empty containers. Native SDKs/services enforce storage sizes independently.
 */
final class DocumentValidator {
    static final int MAX_NESTING_DEPTH = 32;
    private static final String NUMBER_TYPES = "Byte, Short, Integer, Long, Float, Double, BigInteger or BigDecimal";

    private DocumentValidator() {
    }

    /**
     * Validates neutral input without copying, serializing or measuring its size.
     *
     * @param document  the document to validate
     * @param operation the operation name used for error reporting
     * @throws MulticloudDbException with category {@link MulticloudDbErrorCategory#INVALID_REQUEST}
     *                               if the document is null, unsupported, cyclic or too deeply nested
     */
    static void validate(Map<String, Object> document, String operation) {
        if (document == null) {
            throw invalid("Document root is null; expected a non-null Map<String, Object>.", operation);
        }
        var active = new IdentityHashMap<Object, Boolean>();
        var stack = new ArrayDeque<Frame>();
        Object value = document;
        while (true) {
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                if (active.containsKey(value)) {
                    throw invalid("Document contains a cycle through container type " + value.getClass().getName()
                            + "; only acyclic Map/List values are allowed.", operation);
                }
                // Before entering a container, the stack contains its ancestors,
                // including the root Map. Thus the root itself enters at depth zero.
                if (stack.size() > MAX_NESTING_DEPTH) {
                    throw invalid("Document nesting depth " + stack.size() + " exceeds maximum "
                            + MAX_NESTING_DEPTH + " (root Map depth 0); container type "
                            + value.getClass().getName() + ".", operation);
                }
                active.put(value, Boolean.TRUE);
                Iterator<?> children = value instanceof Map<?, ?> map
                        ? map.entrySet().iterator() : ((List<?>) value).iterator();
                stack.push(new Frame(value, children));
            } else if (value instanceof Number number) {
                Class<?> kind = number.getClass();
                if (kind != Byte.class && kind != Short.class && kind != Integer.class
                        && kind != Long.class && kind != Float.class && kind != Double.class
                        && kind != BigInteger.class && kind != BigDecimal.class) {
                    throw invalid("Unsupported document number type " + kind.getName()
                            + "; expected exactly " + NUMBER_TYPES + ".", operation);
                }
                if (number instanceof Float f && !Float.isFinite(f)
                        || number instanceof Double d && !Double.isFinite(d)) {
                    throw invalid("Document number of type " + kind.getName() + " must be finite.", operation);
                }
            } else if (value != null && !(value instanceof String) && !(value instanceof Boolean)) {
                throw invalid("Unsupported document value type " + value.getClass().getName()
                        + "; expected Map, List, String, Boolean, null, or exactly " + NUMBER_TYPES + ".", operation);
            }
            while (!stack.isEmpty() && !stack.peek().children().hasNext()) {
                active.remove(stack.pop().container());
            }
            if (stack.isEmpty()) return;
            Frame frame = stack.peek();
            Object child = frame.children().next();
            if (frame.container() instanceof Map<?, ?>) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) child;
                if (!(entry.getKey() instanceof String)) {
                    String keyType = entry.getKey() == null ? "null" : entry.getKey().getClass().getName();
                    throw invalid("Document Map key has type " + keyType
                            + "; expected a non-null java.lang.String.", operation);
                }
                value = entry.getValue();
            } else {
                value = child;
            }
        }
    }

    private record Frame(Object container, Iterator<?> children) {}

    private static MulticloudDbException invalid(String message, String operation) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST, message, null, operation, false, null));
    }
}
