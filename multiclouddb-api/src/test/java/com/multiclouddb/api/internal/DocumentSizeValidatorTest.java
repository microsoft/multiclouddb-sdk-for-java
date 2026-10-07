// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the shared serialized and structural write-input envelope. */
class DocumentSizeValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class FailingBean {
        public String getValue() {
            throw new IllegalStateException("getter failed");
        }
    }

    @Test
    @DisplayName("MAX_BYTES is the portable 390 KiB limit")
    void maxBytesIsExact() {
        assertEquals(390 * 1024, DocumentSizeValidator.MAX_BYTES);
    }

    private static Map<String, Object> mapOfSerializedSize(int targetBytes) throws Exception {
        int overhead = MAPPER.writeValueAsBytes(Map.of("p", "")).length;
        String value = "A".repeat(targetBytes - overhead);
        Map<String, Object> map = Map.of("p", value);
        assertEquals(targetBytes, MAPPER.writeValueAsBytes(map).length,
                "test fixture must serialize to the exact target size");
        return map;
    }

    private static final class BinaryBean {
        public byte[] getPayload() {
            return new byte[] {1, 2};
        }
    }

    private static final class PojoNode {
        public PojoNode child;
    }


    @Test
    @DisplayName("self-referencing input is a typed non-retryable invalid request")
    void selfReferenceIsRejected() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("self", document);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(document, OperationNames.CREATE));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals(OperationNames.CREATE, ex.error().operation());
        assertEquals(false, ex.error().retryable());
        assertNull(ex.error().provider());
        assertEquals("document_value_cycle",
                ex.error().providerDetails().get("reason"));
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("failing value serialization is a typed invalid request")
    void failingValueSerializationIsMapped() {
        Map<String, Object> document = Map.of("value", new FailingBean());

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(document, OperationNames.CREATE));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals(OperationNames.CREATE, ex.error().operation());
        assertInstanceOf(JsonProcessingException.class, ex.getCause());
    }

    @Test
    @DisplayName("values requiring custom Jackson modules fail shared preflight")
    void customModuleValuesAreRejected() {
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("createdAt", Instant.EPOCH), OperationNames.CREATE));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals("portable_value_normalization_failed",
                ex.error().providerDetails().get("reason"));
        assertEquals(false, ex.error().retryable());
        assertNull(ex.error().provider());
    }

    @Test
    @DisplayName("null and provider-reserved complete documents are invalid")
    void nullAndReservedCompleteDocumentsAreRejected() {
        MulticloudDbException missing = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        (Map<String, Object>) null, OperationNames.CREATE));

        assertEquals("document_required",
                missing.error().providerDetails().get("reason"));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                missing.error().category());
        assertEquals(false, missing.error().retryable());
        assertNull(missing.error().provider());

        for (String field : List.of(
                "id", "PARTITIONKEY", "sortKey", "ttl", "TtlExpiry", "DaTa", "_ts")) {
            MulticloudDbException reserved = assertThrows(MulticloudDbException.class,
                    () -> DocumentSizeValidator.validate(
                            Map.of(field, "value"), OperationNames.UPSERT));
            assertEquals("reserved_document_field",
                    reserved.error().providerDetails().get("reason"));
            assertEquals(field, reserved.error().providerDetails().get("field"));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                    reserved.error().category());
            assertEquals(false, reserved.error().retryable());
            assertNull(reserved.error().provider());
        }
    }

    @Test
    @DisplayName("validated snapshots detach nested caller values")
    void validatedSnapshotsDetachNestedCallerValues() {
        String[] array = {"a", "b"};
        var tree = MAPPER.createObjectNode().put("name", "Ada");
        Map<String, Object> document = Map.of("array", array, "tree", tree);

        Map<String, Object> snapshot = DocumentSizeValidator
                .validateAndSnapshotDocument(document, OperationNames.CREATE);

        assertNotSame(array, snapshot.get("array"));
        assertNotSame(tree, snapshot.get("tree"));
        assertEquals(List.of("a", "b"), snapshot.get("array"));
        assertEquals(Map.of("name", "Ada"), snapshot.get("tree"));

        array[0] = "changed";
        tree.put("name", "changed");
        assertEquals(List.of("a", "b"), snapshot.get("array"));
        assertEquals(Map.of("name", "Ada"), snapshot.get("tree"));
    }

    @Test
    @DisplayName("self-referencing POJOs fail with the stable cycle reason")
    void selfReferencingPojoIsRejected() {
        PojoNode value = new PojoNode();
        value.child = value;

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("payload", value), OperationNames.CREATE));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals("document_value_cycle", ex.error().providerDetails().get("reason"));
        assertNull(ex.error().provider());
    }

    @Test
    @DisplayName("deep POJO graphs fail with the stable depth reason")
    void deepPojoGraphIsRejected() {
        PojoNode value = new PojoNode();
        for (int i = 0; i < PartialUpdateStructureValidator.MAX_NESTING_DEPTH + 1; i++) {
            PojoNode parent = new PojoNode();
            parent.child = value;
            value = parent;
        }

        PojoNode root = value;
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("payload", root), OperationNames.UPSERT));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals(PartialUpdateStructureValidator.DOCUMENT_DEPTH_LIMIT_REASON,
                ex.error().providerDetails().get("reason"));
        assertNull(ex.error().provider());
    }

    @Test
    @DisplayName("complete top-level names use the portable 128-character boundary")
    void completeTopLevelFieldNameBoundaryIsPortable() {
        String accepted = "a".repeat(
                DocumentSizeValidator.MAX_TOP_LEVEL_FIELD_NAME_CHARACTERS);
        String rejected = accepted + "a";

        assertDoesNotThrow(() -> DocumentSizeValidator.validate(
                Map.of(accepted, "value"), OperationNames.CREATE));
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of(rejected, "value"), OperationNames.UPSERT));

        assertEquals(DocumentSizeValidator.TOP_LEVEL_FIELD_NAME_LIMIT_REASON,
                ex.error().providerDetails().get("reason"));
        assertEquals("129", ex.error().providerDetails()
                .get("actualFieldNameCharacters"));
        assertEquals("128", ex.error().providerDetails()
                .get("maximumFieldNameCharacters"));
    }

    @Test
    @DisplayName("complete top-level names must be unique ignoring case")
    void completeTopLevelFieldNamesRejectCaseInsensitiveCollisions() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("foo", 1);
        document.put("Foo", 2);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(document, OperationNames.CREATE));

        assertEquals(DocumentSizeValidator.CASE_INSENSITIVE_FIELD_COLLISION_REASON,
                ex.error().providerDetails().get("reason"));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertNull(ex.error().provider());
    }

    @Test
    @DisplayName("MAX_BYTES passes common serialized and structural preflight")
    void exactLimitPasses() throws Exception {
        Map<String, Object> atLimit = mapOfSerializedSize(DocumentSizeValidator.MAX_BYTES);
        assertDoesNotThrow(() -> DocumentSizeValidator.validate(atLimit, OperationNames.CREATE));
    }

    @Test
    @DisplayName("one byte over MAX_BYTES fails with non-retryable INVALID_REQUEST")
    void oneOverLimitFails() throws Exception {
        Map<String, Object> overLimit = mapOfSerializedSize(DocumentSizeValidator.MAX_BYTES + 1);
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(overLimit, OperationNames.UPSERT));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals(false, ex.error().retryable());
        assertTrue(ex.error().message().contains("391 KiB"));
    }

    @Test
    @DisplayName("compact complete document over structural limit is rejected")
    void compactDocumentOverStructuralLimitFails() {
        int count = (PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES - 4) / 4 + 1;
        Map<String, Object> document = Map.of(
                "x", Collections.nCopies(count, Map.of()));

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(document, OperationNames.CREATE));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals(PartialUpdateStructureValidator.DOCUMENT_FOOTPRINT_LIMIT_REASON,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("binary values are rejected consistently for all write operations")
    void binaryValuesAreRejectedForAllWrites() {
        Map<String, Object> direct = Map.of("payload", new byte[] {1, 2});
        Map<String, Object> nested = Map.of(
                "profile", Map.of("payload", ByteBuffer.wrap(new byte[] {1, 2})));
        Map<String, Object> hiddenInPojo = Map.of("profile", new BinaryBean());

        MulticloudDbException create = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(direct, OperationNames.CREATE));
        MulticloudDbException upsert = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(nested, OperationNames.UPSERT));
        MulticloudDbException update = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validatePartialUpdate(direct, OperationNames.UPDATE));
        MulticloudDbException pojo = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(hiddenInPojo, OperationNames.UPSERT));

        for (MulticloudDbException failure : new MulticloudDbException[] {
                create, upsert, update, pojo}) {
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                    failure.error().category());
            assertEquals(PartialUpdateStructureValidator.NON_PORTABLE_BINARY_REASON,
                    failure.error().providerDetails().get("reason"));
            assertEquals(false, failure.error().retryable());
            assertNull(failure.error().provider());
        }
        assertEquals("/payload", create.error().providerDetails().get("valuePath"));
        assertEquals("/profile/payload", upsert.error().providerDetails().get("valuePath"));
        assertEquals("/profile/payload", pojo.error().providerDetails().get("valuePath"));
    }
}
