// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

/**
 * A closed, immutable JSON-like value: null, boolean, string, number, array or
 * object. There is no binary or arbitrary embedded-object kind. Java {@code null} is not a value;
 * use {@link NullValue#INSTANCE} for explicit null.
 */
public sealed interface DocumentValue permits NullValue, BooleanValue, StringValue,
        NumberValue, ArrayValue, ObjectValue {
}
