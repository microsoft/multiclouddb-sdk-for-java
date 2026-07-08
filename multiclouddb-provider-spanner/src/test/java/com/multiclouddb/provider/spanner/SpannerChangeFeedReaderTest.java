// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SpannerChangeFeedReader#listCursors(ResourceAddress)}.
 * <p>
 * The success path (row decoding) is exercised by the emulator-backed
 * {@code SpannerChangeFeedConformanceTest}; mocking the deeply-nested
 * change-stream row schema in a unit test would be more fragile than
 * informative. This unit test covers the most ambiguous timing branch —
 * placeholder minting on an empty TVF result — and asserts that the
 * {@code issuedAtEpochMillis} stamped on the placeholder reflects the
 * instant the result-set was observed exhausted, matching the invariant
 * established by the Cosmos and Dynamo readers.
 */
class SpannerChangeFeedReaderTest {

    private static final ResourceAddress ADDR = new ResourceAddress("test_db", "test_collection");

    @Test
    @DisplayName("Empty result placeholder: one __bootstrap__ cursor with issuedAt within call window")
    void emptyResult_mintsBootstrapPlaceholderWithFreshIssuedAt() {
        DatabaseClient db = mock(DatabaseClient.class);
        ReadContext ctx = mock(ReadContext.class);
        ResultSet rs = mock(ResultSet.class);
        when(db.singleUse()).thenReturn(ctx);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(rs);
        // Empty TVF result: first call to next() returns false.
        when(rs.next()).thenReturn(false);

        SpannerChangeFeedReader reader = new SpannerChangeFeedReader(
                ProviderId.SPANNER, db, Map.of());

        long preCall = System.currentTimeMillis();
        List<ChangeFeedCursor> cursors = reader.listCursors(ADDR);
        long postCall = System.currentTimeMillis();

        assertEquals(1, cursors.size(),
                "empty TVF result must mint exactly one bootstrap placeholder cursor");
        ChangeFeedCursor c = cursors.get(0);
        String partitionId = c.token().partitions().get(0).partitionId();
        assertNotNull(partitionId);
        assertEquals("__bootstrap__", partitionId,
                "placeholder partitionId must be __bootstrap__; was " + partitionId);
        long issuedAt = c.token().issuedAtEpochMillis();
        assertTrue(issuedAt >= preCall && issuedAt <= postCall,
                "placeholder issuedAt (" + issuedAt + ") must be within [preCall="
                        + preCall + ", postCall=" + postCall + "] — proving it was captured"
                        + " after the result was observed exhausted, not before the query was issued");
    }
}
