// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.codec.DocumentCodec;
import com.multiclouddb.api.codec.DocumentCodecException;
import com.multiclouddb.api.codec.TypeRef;
import com.multiclouddb.api.document.Document;
import com.multiclouddb.api.document.NumberValue;
import com.multiclouddb.api.document.StringValue;

import static com.multiclouddb.api.codec.DocumentCodecException.Phase.*;
import static com.multiclouddb.api.codec.DocumentCodecException.Reason.INVALID_ARGUMENT;

/** Executable customer example: this source needs only the neutral API, not Jackson. */
public final class PlainCustomerCodec implements DocumentCodec {
    public record Customer(String name, int quantity) {}

    @Override
    public <T> Document encode(T value, TypeRef<T> type) {
        if (type == null || type.type() != Customer.class || !(value instanceof Customer customer)) {
            throw new DocumentCodecException(ENCODE, INVALID_ARGUMENT);
        }
        return Document.builder()
                .put("customer_name", new StringValue(customer.name()))
                .put("quantity", NumberValue.of(customer.quantity()))
                .build();
    }

    @Override
    @SuppressWarnings("unchecked") // The exact requested Class was checked before returning its instance.
    public <T> T decode(Document document, TypeRef<T> type) {
        if (document == null || type == null || type.type() != Customer.class) {
            throw new DocumentCodecException(DECODE, INVALID_ARGUMENT);
        }
        if (!(document.get("customer_name").orElse(null) instanceof StringValue name)
                || !(document.get("quantity").orElse(null) instanceof NumberValue quantity)) {
            throw new DocumentCodecException(DECODE, INVALID_ARGUMENT);
        }
        return (T) new Customer(name.value(), quantity.decimalValue().intValueExact());
    }
}
