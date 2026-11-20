// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
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
    private static final int ACTIVE = -1;
    private static final long MAX_INTEGER = 9_007_199_254_740_991L;
    private static final double MIN_NONZERO_DOUBLE = 1E-130;
    private static final String NUMBER_TYPES = "Integer, Long or Double";

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
        Iterator<?> children = document.entrySet().iterator();
        while (children.hasNext()) {
            Object value = entryValue((Map.Entry<?, ?>) children.next(), operation);
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                validateNested(document, children, value, operation);
                return;
            }
            validateScalar(value, operation);
        }
    }

    private static void validateNested(Map<String, Object> document, Iterator<?> rootChildren,
                                       Object value, String operation) {
        var states = new IdentityHashMap<Object, Integer>();
        var stack = new ArrayDeque<Frame>();
        states.put(document, ACTIVE);
        stack.push(new Frame(document, rootChildren));
        while (true) {
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                Integer height = states.get(value);
                if (height != null && height == ACTIVE) {
                    throw invalid("Document contains a cycle through container type " + value.getClass().getName()
                            + "; only acyclic Map/List values are allowed.", operation);
                }
                // A shared subtree may be reached again through a deeper path.
                int depth = stack.size() + (height == null ? 0 : height);
                if (depth > MAX_NESTING_DEPTH) {
                    throw invalid("Document nesting depth " + depth + " exceeds maximum "
                            + MAX_NESTING_DEPTH + " (root Map depth 0); container type "
                            + value.getClass().getName() + ".", operation);
                }
                if (height == null) {
                    states.put(value, ACTIVE);
                    Iterator<?> children = value instanceof Map<?, ?> map
                            ? map.entrySet().iterator() : ((List<?>) value).iterator();
                    stack.push(new Frame(value, children));
                } else if (!stack.isEmpty()) {
                    stack.peek().height = Math.max(stack.peek().height, height + 1);
                }
            } else {
                validateScalar(value, operation);
            }
            while (!stack.isEmpty() && !stack.peek().children.hasNext()) {
                Frame finished = stack.pop();
                states.put(finished.container, finished.height);
                if (!stack.isEmpty()) {
                    stack.peek().height = Math.max(stack.peek().height, finished.height + 1);
                }
            }
            if (stack.isEmpty()) return;
            Frame frame = stack.peek();
            Object child = frame.children.next();
            if (frame.container instanceof Map<?, ?>) {
                value = entryValue((Map.Entry<?, ?>) child, operation);
            } else {
                value = child;
            }
        }
    }

    private static Object entryValue(Map.Entry<?, ?> entry, String operation) {
        if (!(entry.getKey() instanceof String)) {
            String keyType = entry.getKey() == null ? "null" : entry.getKey().getClass().getName();
            throw invalid("Document Map key has type " + keyType
                    + "; expected a non-null java.lang.String.", operation);
        }
        return entry.getValue();
    }

    private static void validateScalar(Object value, String operation) {
        if (value instanceof Number number) {
            Class<?> kind = number.getClass();
            if (kind != Integer.class && kind != Long.class && kind != Double.class) {
                throw invalid("Unsupported document number type " + kind.getName()
                        + "; expected exactly " + NUMBER_TYPES + ".", operation);
            }
            if (number instanceof Long integer && (integer < -MAX_INTEGER || integer > MAX_INTEGER)) {
                throw invalid("Document Long must be between -9007199254740991 and 9007199254740991 inclusive.",
                        operation);
            }
            if (number instanceof Double floating) {
                if (!Double.isFinite(floating)) {
                    throw invalid("Document number of type " + kind.getName() + " must be finite.", operation);
                }
                double magnitude = Math.abs(floating);
                if (magnitude > MAX_INTEGER || magnitude != 0 && magnitude < MIN_NONZERO_DOUBLE) {
                    throw invalid("Document Double must be zero or have absolute value between "
                            + "1E-130 and 9007199254740991 inclusive.", operation);
                }
            }
        } else if (value != null && !(value instanceof String) && !(value instanceof Boolean)) {
            throw invalid("Unsupported document value type " + value.getClass().getName()
                    + "; expected Map, List, String, Boolean, null, or exactly " + NUMBER_TYPES + ".", operation);
        }
    }

    private static final class Frame {
        private final Object container;
        private final Iterator<?> children;
        private int height;

        private Frame(Object container, Iterator<?> children) {
            this.container = container;
            this.children = children;
        }
    }

    private static MulticloudDbException invalid(String message, String operation) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST, message, null, operation, false, null));
    }
}
