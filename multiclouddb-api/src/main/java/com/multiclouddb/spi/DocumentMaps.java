// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.spi;

import com.multiclouddb.api.document.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Internal provider migration support. Retains the existing plain-container
 * native-mapping path without reflection, JSON parsing or numeric coercion.
 */
public final class DocumentMaps {
    private DocumentMaps() {
    }

    /** Creates owned mutable containers for a provider's existing mapping path. */
    public static Map<String, Object> toMap(Document document) {
        return object(Objects.requireNonNull(document, "document").root());
    }

    private static Map<String, Object> object(ObjectValue value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.fields().forEach((name, child) -> result.put(name, plain(child)));
        return result;
    }

    private static Object plain(DocumentValue value) {
        if (value instanceof ObjectValue object) return object(object);
        if (value instanceof ArrayValue array) {
            List<Object> result = new ArrayList<>(array.values().size());
            array.values().forEach(child -> result.add(plain(child)));
            return result;
        }
        if (value instanceof StringValue string) return string.value();
        if (value instanceof NumberValue number) return number.value();
        if (value instanceof BooleanValue bool) return bool.value();
        return null; // The only remaining permitted kind is explicit NullValue.
    }
}
