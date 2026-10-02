// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.models.CosmosPatchOperations;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, package-private planner that converts a validated partial-update field map
 * into one direct Cosmos {@code patchItem} containing at most
 * {@link #MAX_FIELDS_PER_PATCH} {@code set} operations. Each user field maps to exactly one
 * {@code set} operation whose path is the raw field name encoded as a single RFC 6901 JSON
 * Pointer segment. No update-TTL assignment is added, and shared preflight rejects key and
 * system fields.
 */
final class CosmosPartialUpdatePlanner {

    /** Maximum {@code set} operations per Cosmos patch request. */
    static final int MAX_FIELDS_PER_PATCH = 10;
    static final String FIELD_COUNT_LIMIT_REASON = "partial_update_field_count_limit";

    private CosmosPartialUpdatePlanner() {
    }

    /**
     * Encodes a raw top-level field name as exactly one RFC 6901 JSON Pointer segment:
     * {@code ~} becomes {@code ~0}, then {@code /} becomes {@code ~1}.
     */
    static String escapePath(String rawName) {
        String segment = rawName.replace("~", "~0").replace("/", "~1");
        return "/" + segment;
    }

    /**
     * Builds a deterministic single-patch plan for validated fields.
     *
     * @throws MulticloudDbException non-retryable {@code INVALID_REQUEST} if the provider SPI
     * is called directly with more than ten fields
     */
    static Plan plan(Map<String, Object> fields) {
        if (fields.size() > MAX_FIELDS_PER_PATCH) {
            throw fieldCountError(fields.size());
        }

        List<Map.Entry<String, Object>> entries = new ArrayList<>(fields.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        CosmosPatchOperations operations = CosmosPatchOperations.create();
        for (Map.Entry<String, Object> entry : entries) {
            operations.set(escapePath(entry.getKey()), entry.getValue());
        }
        return new Plan(operations, fields.size());
    }

    private static MulticloudDbException fieldCountError(int actualFields) {
        Map<String, String> details = Map.of(
                "reason", FIELD_COUNT_LIMIT_REASON,
                "actualFields", String.valueOf(actualFields),
                "maximumFields", String.valueOf(MAX_FIELDS_PER_PATCH));
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST,
                "Partial update accepts at most " + MAX_FIELDS_PER_PATCH
                        + " fields per call; received " + actualFields + ".",
                ProviderId.COSMOS, OperationNames.UPDATE, false, details));
    }

    /** Immutable single-patch partial-update plan. */
    static final class Plan {
        private final CosmosPatchOperations operations;
        private final int setCount;

        Plan(CosmosPatchOperations operations, int setCount) {
            this.operations = operations;
            this.setCount = setCount;
        }

        CosmosPatchOperations operations() { return operations; }
        int setCount() { return setCount; }
    }
}
