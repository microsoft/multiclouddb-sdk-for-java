// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.models.CosmosBatch;
import com.azure.cosmos.models.CosmosPatchOperations;
import com.azure.cosmos.models.PartitionKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.Capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, package-private planner that converts a validated partial-update field map
 * into one Cosmos native request:
 * <ul>
 *   <li>at most {@link #MAX_FIELDS_PER_PATCH} user fields become one direct {@code patchItem}
 *       of {@code set} operations;</li>
 *   <li>wider requests become one same-item, same-partition transactional batch of
 *       at-most-ten-field patch chunks.</li>
 * </ul>
 * Each user field maps to exactly one {@code set} operation whose path is the raw field name
 * encoded as a single RFC 6901 JSON Pointer segment. No update-TTL assignment is ever added,
 * and no key/system field is patched (shared preflight rejects reserved names).
 * <p>
 * Before returning a wide plan, the planner mirrors the public SDK batch JSON shape and
 * rejects requests above 100 batch operations or 2 MiB before Cosmos I/O.
 */
final class CosmosPartialUpdatePlanner {

    /** Maximum {@code set} operations per Cosmos patch request. */
    static final int MAX_FIELDS_PER_PATCH = 10;
    /** Maximum patch operations in one transactional batch. */
    static final int MAX_BATCH_OPERATIONS = 100;
    /** Maximum serialized transactional-batch body size in bytes (2 MiB). */
    static final long MAX_BATCH_BYTES = 2_097_152L;

    static final String BATCH_LIMIT_REASON = "cosmos_transactional_batch_limit";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CosmosPartialUpdatePlanner() {
    }

    /**
     * Encodes a raw top-level field name as exactly one RFC 6901 JSON Pointer segment:
     * {@code ~} becomes {@code ~0}, then {@code /} becomes {@code ~1}, and the result is
     * prefixed with {@code /}. The transform order matters so {@code ~} in the raw name is not
     * double-escaped.
     */
    static String escapePath(String rawName) {
        String seg = rawName.replace("~", "~0").replace("/", "~1");
        return "/" + seg;
    }

    /**
     * Builds a deterministic plan for the given item identity and validated fields.
     *
     * @throws MulticloudDbException non-retryable {@code UNSUPPORTED_CAPABILITY} if a wide
     *                               request exceeds the native transactional-batch envelope;
     *                               no Cosmos call is performed.
     */
    static Plan plan(String id, PartitionKey partitionKey, Map<String, Object> fields) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(fields.entrySet());
        List<List<Map.Entry<String, Object>>> chunks = chunk(entries, MAX_FIELDS_PER_PATCH);

        List<CosmosPatchOperations> patchChunks = new ArrayList<>(chunks.size());
        for (List<Map.Entry<String, Object>> group : chunks) {
            CosmosPatchOperations ops = CosmosPatchOperations.create();
            for (Map.Entry<String, Object> e : group) {
                ops.set(escapePath(e.getKey()), e.getValue());
            }
            patchChunks.add(ops);
        }

        CosmosBatch batch = null;
        int operationCount = patchChunks.size();
        long serializedBytes = 0;
        if (operationCount > 1) {
            serializedBytes = mirrorBytes(id, chunks);
            validateBatchEnvelope(operationCount, serializedBytes);
            batch = CosmosBatch.createCosmosBatch(partitionKey);
            for (CosmosPatchOperations ops : patchChunks) {
                batch.patchItemOperation(id, ops);
            }
        }
        return new Plan(patchChunks, batch, fields.size(), operationCount, serializedBytes);
    }

    private static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(new ArrayList<>(items.subList(i, Math.min(i + size, items.size()))));
        }
        return out;
    }

    /**
     * Mirrors the complete prospective azure-cosmos transactional-batch body as a Jackson tree
     * and returns its UTF-8 byte length. The mirror includes every operation's type, item id,
     * and {@code resourceBody.operations} (each {@code op}/{@code path}/{@code value}), so the
     * measured size reflects all structural and string-escaping overhead.
     */
    static long mirrorBytes(String id, List<List<Map.Entry<String, Object>>> chunks) {
        try {
            ArrayNode batchArray = MAPPER.createArrayNode();
            for (List<Map.Entry<String, Object>> group : chunks) {
                ObjectNode op = MAPPER.createObjectNode();
                op.put("operationType", "Patch");
                op.put("id", id);
                ObjectNode resourceBody = MAPPER.createObjectNode();
                ArrayNode setOps = MAPPER.createArrayNode();
                for (Map.Entry<String, Object> e : group) {
                    ObjectNode setOp = MAPPER.createObjectNode();
                    setOp.put("op", "set");
                    setOp.put("path", escapePath(e.getKey()));
                    setOp.set("value", MAPPER.valueToTree(e.getValue()));
                    setOps.add(setOp);
                }
                resourceBody.set("operations", setOps);
                op.set("resourceBody", resourceBody);
                batchArray.add(op);
            }
            return MAPPER.writeValueAsBytes(batchArray).length;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Failed to encode the Cosmos partial-update batch.",
                    ProviderId.COSMOS, OperationNames.UPDATE, false,
                    Map.of("exceptionType", ex.getClass().getSimpleName())), ex);
        }
    }

    static void validateBatchEnvelope(int operationCount, long serializedBytes) {
        if (operationCount > MAX_BATCH_OPERATIONS || serializedBytes > MAX_BATCH_BYTES) {
            throw limitError(operationCount, serializedBytes);
        }
    }

    /**
     * Builds the non-retryable {@code UNSUPPORTED_CAPABILITY} thrown when a wide request
     * exceeds the native transactional-batch envelope. {@code actualBytes} is always present,
     * including when only the operation-count limit is exceeded.
     */
    static MulticloudDbException limitError(int operationCount, long serializedBytes) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("reason", BATCH_LIMIT_REASON);
        details.put("capability", Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD);
        details.put("actualOperations", String.valueOf(operationCount));
        details.put("maximumOperations", String.valueOf(MAX_BATCH_OPERATIONS));
        details.put("actualBytes", String.valueOf(serializedBytes));
        details.put("maximumBytes", String.valueOf(MAX_BATCH_BYTES));
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                "Cosmos transactional batch exceeds the native envelope for partial_update_extended_payload: "
                        + "operations=" + operationCount + " (max " + MAX_BATCH_OPERATIONS + "), "
                        + "serializedBytes=" + serializedBytes + " (max " + MAX_BATCH_BYTES + ").",
                ProviderId.COSMOS, OperationNames.UPDATE, false, details));
    }

    /**
     * Immutable partial-update plan. A direct plan has exactly one patch chunk and a null
     * batch; a wide plan has multiple chunks and a same-item transactional batch.
     */
    static final class Plan {
        private final List<CosmosPatchOperations> patchChunks;
        private final CosmosBatch batch;
        private final int setCount;
        private final int operationCount;
        private final long serializedBytes;

        Plan(List<CosmosPatchOperations> patchChunks, CosmosBatch batch, int setCount,
                int operationCount, long serializedBytes) {
            this.patchChunks = List.copyOf(patchChunks);
            this.batch = batch;
            this.setCount = setCount;
            this.operationCount = operationCount;
            this.serializedBytes = serializedBytes;
        }

        List<CosmosPatchOperations> patchChunks() { return patchChunks; }
        CosmosBatch batch() { return batch; }
        int setCount() { return setCount; }
        int operationCount() { return operationCount; }
        long serializedBytes() { return serializedBytes; }
        boolean isDirect() { return patchChunks.size() == 1; }
    }
}
