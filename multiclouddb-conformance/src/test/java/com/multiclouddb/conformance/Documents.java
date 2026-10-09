// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.document.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** JDK-only migration of existing literal test fixtures, not application serialization. */
public final class Documents {
    private Documents() {
    }

    public static Document document(Map<String, Object> fields) {
        return fields == null ? null : Document.of((ObjectValue) value(fields));
    }

    private static DocumentValue value(Object input) {
        if (input == null) return NullValue.INSTANCE;
        if (input instanceof String text) return new StringValue(text);
        if (input instanceof Boolean bool) return new BooleanValue(bool);
        if (input instanceof Number number) return NumberValue.of(number);
        if (input instanceof Map<?, ?> map) {
            var fields = new LinkedHashMap<String, DocumentValue>();
            map.forEach((name, child) -> fields.put((String) name, value(child)));
            return ObjectValue.of(fields);
        }
        if (input instanceof Iterable<?> values) {
            var items = new ArrayList<DocumentValue>();
            values.forEach(child -> items.add(value(child)));
            return ArrayValue.of(items);
        }
        throw new IllegalArgumentException("Unsupported literal test fixture.");
    }
}
