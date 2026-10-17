// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import java.util.IdentityHashMap;

/** One comparison's proven equal container pairs, shared across both container kinds. */
final class ValueEquality {
    private final IdentityHashMap<DocumentValue, IdentityHashMap<DocumentValue, Boolean>> equalPairs =
            new IdentityHashMap<>();

    private ValueEquality() {
    }

    static boolean equal(DocumentValue left, DocumentValue right) {
        return new ValueEquality().compare(left, right);
    }

    private boolean compare(DocumentValue left, DocumentValue right) {
        if (left == right) return true;
        if (right == null || left.getClass() != right.getClass() || left.hashCode() != right.hashCode()) {
            return false;
        }
        if (!(left instanceof ArrayValue) && !(left instanceof ObjectValue)) {
            return left.equals(right);
        }
        var matches = equalPairs.get(left);
        if (matches != null && matches.containsKey(right)) return true;

        if (left instanceof ArrayValue array) {
            var other = ((ArrayValue) right).values();
            if (array.values().size() != other.size()) return false;
            for (int i = 0; i < other.size(); i++) {
                if (!compare(array.values().get(i), other.get(i))) return false;
            }
        } else {
            var fields = ((ObjectValue) left).fields();
            var other = ((ObjectValue) right).fields();
            if (fields.size() != other.size()) return false;
            for (var field : fields.entrySet()) {
                if (!compare(field.getValue(), other.get(field.getKey()))) return false;
            }
        }
        equalPairs.computeIfAbsent(left, ignored -> new IdentityHashMap<>()).put(right, Boolean.TRUE);
        return true;
    }
}
