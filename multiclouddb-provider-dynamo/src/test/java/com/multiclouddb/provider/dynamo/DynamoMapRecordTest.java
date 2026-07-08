// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DynamoChangeFeedReader#mapRecord(Record)} and
 * {@link DynamoChangeFeedReader} {@code attrToString} helper — the defensive
 * post-image / key-attribute mapping introduced by Finding #14 of the
 * portability review.
 *
 * <h3>Background</h3>
 * The previous implementation of {@code attrToString} returned {@code ""} for
 * missing / unknown-type values. That collapsed two semantically-distinct
 * conditions:
 * <ul>
 *   <li><em>"attribute absent"</em> — the record's stream image does not carry
 *       the SDK's expected partition-key field (e.g. a misconfigured stream
 *       specification, or a table whose key shape diverges from the SDK's
 *       {@code partitionKey} / {@code sortKey} convention).</li>
 *   <li><em>"attribute present but blank"</em> — the application wrote an
 *       empty string as the partition-key value (legal in DynamoDB).</li>
 * </ul>
 * The first case silently minted {@code MulticloudDbKey.of("")} for every
 * event, leaving downstream consumers unable to distinguish records. The
 * portability-review patch flips this to a hard diagnostic:
 * {@link MulticloudDbErrorCategory#PROVIDER_ERROR} with
 * {@code providerDetails.reason="missing_partition_key"} so an operator sees
 * the misconfiguration immediately rather than ingesting a stream of
 * indistinguishable events.
 *
 * <p>These tests pin the contract for {@link #attrToString} and
 * {@link #mapRecord} via reflection — both are package-private internals
 * deliberately kept off the public reader surface.
 */
class DynamoMapRecordTest {

    private static final DynamoChangeFeedReader READER =
            new DynamoChangeFeedReader(ProviderId.DYNAMO, null);

    // ── attrToString contract ───────────────────────────────────────────────

    @Test
    @DisplayName("attrToString(null) returns null — distinguishes absent attribute from empty string")
    void attrToString_null_returnsNull() throws Exception {
        assertNull(invokeAttrToString(null),
                "attrToString must return null for a missing AttributeValue so "
                        + "callers can distinguish 'absent' from 'present but blank'");
    }

    @Test
    @DisplayName("attrToString(AttributeValue.s(\"value\")) returns the string")
    void attrToString_stringValue_returnsString() throws Exception {
        AttributeValue av = AttributeValue.builder().s("hello").build();
        assertEquals("hello", invokeAttrToString(av));
    }

    @Test
    @DisplayName("attrToString(AttributeValue.s(\"\")) returns the empty string (not null)")
    void attrToString_emptyString_returnsEmptyString() throws Exception {
        // An explicitly-written empty string is a legal DynamoDB value and must
        // round-trip as "" — only true absence collapses to null.
        AttributeValue av = AttributeValue.builder().s("").build();
        assertEquals("", invokeAttrToString(av));
    }

    @Test
    @DisplayName("attrToString(AttributeValue.n(\"42\")) returns the numeric string")
    void attrToString_numericValue_returnsNumberString() throws Exception {
        AttributeValue av = AttributeValue.builder().n("42").build();
        assertEquals("42", invokeAttrToString(av));
    }

    @Test
    @DisplayName("attrToString(AttributeValue with neither s nor n) returns null")
    void attrToString_otherType_returnsNull() throws Exception {
        // BOOL / B / L / M etc. are not string-convertible by this helper —
        // the reader only consumes S and N for key attributes, so others
        // collapse to null (same signal as "absent").
        AttributeValue av = AttributeValue.builder().bool(true).build();
        assertNull(invokeAttrToString(av),
                "non-S/N AttributeValue types must return null — the helper is "
                        + "scoped to S/N key attributes only");
    }

    // ── mapRecord blank-pk defense (Finding #14) ────────────────────────────

    @Test
    @DisplayName("mapRecord with missing partitionKey attribute throws PROVIDER_ERROR(reason=missing_partition_key)")
    void mapRecord_missingPartitionKey_throws() throws Exception {
        // A stream record whose keys map omits ATTR_PARTITION_KEY entirely —
        // the most common shape produced by a misconfigured table whose
        // primary key is not named "partitionKey".
        Record rec = Record.builder()
                .eventID("evt-001")
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .keys(Map.of("someOtherKey", AttributeValue.builder().s("v").build()))
                        .build())
                .build();

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> invokeMapRecord(rec));
        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, ex.error().category(),
                "missing partition-key must surface as PROVIDER_ERROR, never silently "
                        + "mint MulticloudDbKey.of(\"\")");
        assertEquals("missing_partition_key", ex.error().providerDetails().get("reason"),
                "providerDetails.reason MUST be the wire-stable 'missing_partition_key' "
                        + "string so log-based alerting can fingerprint this condition");
        assertEquals("evt-001", ex.error().providerDetails().get("eventID"),
                "the offending eventID MUST be surfaced for operator triage");
        assertEquals(ProviderId.DYNAMO, ex.error().provider());
        assertEquals("readChanges", ex.error().operation());
        assertFalse(ex.error().retryable(),
                "a misconfigured table is a deploy-time error, never retryable");
    }

    @Test
    @DisplayName("mapRecord with empty-string partitionKey value throws (collapsed-to-null path)")
    void mapRecord_emptyStringPartitionKey_throws() throws Exception {
        // partitionKey present but ""; the pk-or-empty defense applies.
        Map<String, AttributeValue> keys = new HashMap<>();
        keys.put("partitionKey", AttributeValue.builder().s("").build());
        Record rec = Record.builder()
                .eventID("evt-empty")
                .eventName(OperationType.MODIFY)
                .dynamodb(StreamRecord.builder().keys(keys).build())
                .build();

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> invokeMapRecord(rec));
        assertEquals("missing_partition_key", ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("mapRecord with valid partitionKey only returns ChangeEvent (CREATE) with single-key MulticloudDbKey")
    void mapRecord_partitionKeyOnly_succeeds() throws Exception {
        Record rec = Record.builder()
                .eventID("evt-pk-only")
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .keys(Map.of("partitionKey", AttributeValue.builder().s("pk-1").build()))
                        .build())
                .build();

        ChangeEvent ev = invokeMapRecord(rec);
        assertNotNull(ev);
        assertEquals(ChangeType.CREATE, ev.type(), "INSERT must map to CREATE");
        assertEquals("pk-1", ev.key().partitionKey());
        assertNull(ev.key().sortKey(), "single-attribute key must carry null sort key");
        assertEquals("evt-pk-only", ev.providerEventId());
    }

    @Test
    @DisplayName("mapRecord with partition + sort key returns ChangeEvent with two-key MulticloudDbKey")
    void mapRecord_partitionAndSortKey_succeeds() throws Exception {
        Record rec = Record.builder()
                .eventID("evt-pk-sk")
                .eventName(OperationType.REMOVE)
                .dynamodb(StreamRecord.builder()
                        .keys(Map.of(
                                "partitionKey", AttributeValue.builder().s("pk-2").build(),
                                "sortKey", AttributeValue.builder().s("sk-2").build()))
                        .build())
                .build();

        ChangeEvent ev = invokeMapRecord(rec);
        assertEquals(ChangeType.DELETE, ev.type(), "REMOVE must map to DELETE");
        assertEquals("pk-2", ev.key().partitionKey());
        assertEquals("sk-2", ev.key().sortKey());
    }

    @Test
    @DisplayName("mapRecord with sort key but no partition key STILL throws (pk gate takes precedence)")
    void mapRecord_sortKeyButNoPartitionKey_throws() throws Exception {
        // A pathological record whose stream image carries only the sort key:
        // the pk-defense gate fires before sk is consulted.
        Record rec = Record.builder()
                .eventID("evt-sk-only")
                .eventName(OperationType.MODIFY)
                .dynamodb(StreamRecord.builder()
                        .keys(Map.of("sortKey", AttributeValue.builder().s("sk-only").build()))
                        .build())
                .build();

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> invokeMapRecord(rec));
        assertEquals("missing_partition_key", ex.error().providerDetails().get("reason"));
    }

    // ── Reflection helpers ──────────────────────────────────────────────────

    private static String invokeAttrToString(AttributeValue v) throws Exception {
        Method m = DynamoChangeFeedReader.class.getDeclaredMethod("attrToString", AttributeValue.class);
        m.setAccessible(true);
        return (String) m.invoke(null, v);
    }

    private static ChangeEvent invokeMapRecord(Record rec) throws Exception {
        Method m = DynamoChangeFeedReader.class.getDeclaredMethod("mapRecord", Record.class);
        m.setAccessible(true);
        try {
            return (ChangeEvent) m.invoke(READER, rec);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            throw ite;
        }
    }
}
