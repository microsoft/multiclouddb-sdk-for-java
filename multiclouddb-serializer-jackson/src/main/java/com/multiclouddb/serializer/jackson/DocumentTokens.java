// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.multiclouddb.api.document.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

final class DocumentTokens {
    private DocumentTokens() {
    }

    static void write(JsonGenerator generator, DocumentValue value) throws IOException {
        if (value instanceof ObjectValue object) {
            generator.writeStartObject();
            for (var field : object.fields().entrySet()) {
                generator.writeFieldName(field.getKey());
                write(generator, field.getValue());
            }
            generator.writeEndObject();
        } else if (value instanceof ArrayValue array) {
            generator.writeStartArray();
            for (DocumentValue item : array.values()) write(generator, item);
            generator.writeEndArray();
        } else if (value instanceof StringValue string) {
            generator.writeString(string.value());
        } else if (value instanceof BooleanValue bool) {
            generator.writeBoolean(bool.value());
        } else if (value instanceof NumberValue number) {
            Number original = number.value();
            if (original instanceof BigDecimal decimal) generator.writeNumber(decimal);
            else if (original instanceof BigInteger integer) generator.writeNumber(integer);
            else if (original instanceof Float floating) generator.writeNumber(floating);
            else if (original instanceof Double floating) generator.writeNumber(floating);
            else if (original instanceof Long integer) generator.writeNumber(integer);
            else if (original instanceof Short integer) generator.writeNumber(integer);
            else generator.writeNumber(original.intValue());
        } else {
            generator.writeNull();
        }
    }
}
