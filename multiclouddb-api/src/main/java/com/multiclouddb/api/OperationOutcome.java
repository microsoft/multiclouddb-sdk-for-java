// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

/** Logical operation result, independent of the provider's HTTP or gRPC status. */
public enum OperationOutcome {
    SUCCESS,
    NOT_FOUND,
    FAILURE
}
