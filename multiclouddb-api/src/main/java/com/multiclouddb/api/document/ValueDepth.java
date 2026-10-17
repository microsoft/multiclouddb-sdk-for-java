// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

final class ValueDepth {
    private ValueDepth() {
    }

    static int container(Iterable<? extends DocumentValue> values) {
        int depth = 1;
        for (DocumentValue value : values) {
            int child = value instanceof ObjectValue object ? object.depth()
                    : value instanceof ArrayValue array ? array.depth() : 0;
            depth = Math.max(depth, child + 1);
        }
        if (depth > 128) {
            throw new IllegalArgumentException("Document nesting exceeds 128 containers.");
        }
        return depth;
    }
}
