// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.document.ArrayValue;
import com.multiclouddb.api.document.BooleanValue;
import com.multiclouddb.api.document.Document;
import com.multiclouddb.api.document.DocumentValue;
import com.multiclouddb.api.document.NullValue;
import com.multiclouddb.api.document.NumberValue;
import com.multiclouddb.api.document.ObjectValue;
import com.multiclouddb.api.document.StringValue;

import java.math.BigDecimal;

/** Counts the existing logical JSON byte profile without materializing JSON. */
final class DocumentJsonSize {
    private final long limit;
    private long size;

    private DocumentJsonSize(long limit) {
        this.limit = limit;
    }

    static long measure(Document document, long limit) {
        DocumentJsonSize counter = new DocumentJsonSize(limit);
        counter.value(document.root());
        return counter.size;
    }

    private void add(long bytes) {
        size += bytes;
        if (size > limit) throw new SizeExceeded(size);
    }

    private void value(DocumentValue value) {
        if (value instanceof ObjectValue object) {
            add(2);
            boolean first = true;
            for (var field : object.fields().entrySet()) {
                if (!first) add(1);
                first = false;
                string(field.getKey());
                add(1);
                value(field.getValue());
            }
        } else if (value instanceof ArrayValue array) {
            add(2);
            boolean first = true;
            for (DocumentValue element : array.values()) {
                if (!first) add(1);
                first = false;
                value(element);
            }
        } else if (value instanceof StringValue string) {
            string(string.value());
        } else if (value instanceof NumberValue number) {
            Number retained = number.value();
            // The old default mapper's Map -> tree size profile normalizes
            // BigDecimal zeros. This count does not modify the model value.
            String text = retained instanceof BigDecimal decimal
                    ? decimal.stripTrailingZeros().toString() : retained.toString();
            add(text.length());
        } else if (value instanceof BooleanValue bool) {
            add(bool.value() ? 4 : 5);
        } else if (value instanceof NullValue) {
            add(4);
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
