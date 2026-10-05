// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.*;
import com.multiclouddb.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpannerTtlMetadataTest {
    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "items");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");
    private static final OperationOptions OPT_IN = OperationOptions.builder()
            .ttlSeconds(3600).includeMetadata(true).build();

    private Spanner sdk;
    private ResultSet rs;
    private final List<Mutation> writes = new ArrayList<>();
    private MockedStatic<SpannerOptions> factory;
    private SpannerProviderClient client;

    @BeforeEach
    void setUp() {
        SpannerOptions.Builder builder = mock(SpannerOptions.Builder.class, RETURNS_SELF);
        SpannerOptions options = mock(SpannerOptions.class);
        sdk = mock(Spanner.class);
        DatabaseClient db = mock(DatabaseClient.class);
        ReadContext read = mock(ReadContext.class);
        rs = mock(ResultSet.class);
        when(builder.build()).thenReturn(options);
        when(options.getService()).thenReturn(sdk);
        when(sdk.getDatabaseClient(any(DatabaseId.class))).thenReturn(db);
        when(db.singleUse()).thenReturn(read);
        when(read.executeQuery(any(Statement.class))).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getColumnCount()).thenReturn(0);
        doAnswer(invocation -> {
            Iterable<Mutation> mutations = invocation.getArgument(0);
            mutations.forEach(writes::add);
            return null;
        }).when(db).write(any());
        factory = mockStatic(SpannerOptions.class);
        factory.when(SpannerOptions::newBuilder).thenReturn(builder);
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection("projectId", "project").connection("instanceId", "instance")
                .connection("databaseId", "db").build();
        client = new SpannerProviderClient(config);
    }

    @AfterEach
    void tearDown() {
        try {
            if (client != null) {
                client.close();
                verify(sdk).close();
            }
        } finally {
            if (factory != null) {
                factory.close();
            }
        }
    }

    // Current-behavior regression, not FR-057 conformance: the unsupported-TTL gate is unimplemented.
    @Test
    void unsupportedTtlDoesNotAlterExpectedCreateMutation() {
        assertFalse(client.capabilities().isSupported(Capability.ROW_LEVEL_TTL));
        client.create(ADDRESS, KEY, Map.of("value", "x"), OPT_IN);
        client.create(ADDRESS, KEY, Map.of("value", "x"), OperationOptions.defaults());
        assertEquals(2, writes.size());
        assertEquals(writes.get(0), writes.get(1), "Unsupported TTL must not alter the mutation");
        Mutation mutation = writes.get(0);
        assertEquals("items", mutation.getTable());
        assertEquals(Mutation.Op.INSERT, mutation.getOperation());
        Map<String, Value> fields = new LinkedHashMap<>();
        var values = mutation.getValues().iterator();
        for (String column : mutation.getColumns()) {
            fields.put(column, values.next());
        }
        assertEquals(Value.string("pk"), fields.get("partitionKey"));
        assertEquals(Value.string("sk"), fields.get("sortKey"));
        assertEquals(Value.string("x"), fields.get("value"));
    }

    @Test
    void metadataOptInReturnsEmptyShellWhileDefaultRemainsNull() {
        assertFalse(client.capabilities().isSupported(Capability.WRITE_TIMESTAMP));
        assertNull(client.read(ADDRESS, KEY, null).metadata());
        DocumentMetadata metadata = client.read(ADDRESS, KEY, OPT_IN).metadata();
        assertNotNull(metadata, "Main returns an empty metadata shell on opt-in");
        assertNull(metadata.lastModified());
        assertNull(metadata.ttlExpiry());
        assertNull(metadata.version());
        when(rs.next()).thenReturn(false);
        assertNull(client.read(ADDRESS, KEY, OPT_IN));
    }
}
