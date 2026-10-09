// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

/** Explicit null, distinct from an absent field. */
public enum NullValue implements DocumentValue {
    /** The single explicit-null value. */
    INSTANCE
}
