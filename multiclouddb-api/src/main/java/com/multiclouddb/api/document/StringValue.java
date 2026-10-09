// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import java.util.Objects;

/** An immutable, non-null string value. */
public record StringValue(String value) implements DocumentValue {
    public StringValue {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return "StringValue[length=" + value.length() + "]";
    }
}
