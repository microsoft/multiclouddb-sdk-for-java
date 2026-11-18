// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.*;
import com.multiclouddb.api.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpannerPointDiagnosticsTest {
    @Test
    void pointSummaryDoesNotRequireProfilingOrInventNativeSuccessMetadata() throws Exception {
        var sdk = mock(Spanner.class);
        var database = mock(DatabaseClient.class);
        var context = mock(ReadContext.class);
        var rows = mock(ResultSet.class);
        var builder = mock(SpannerOptions.Builder.class, RETURNS_SELF);
        var options = mock(SpannerOptions.class);
        when(builder.build()).thenReturn(options);
        when(options.getService()).thenReturn(sdk);
        when(sdk.getDatabaseClient(any(DatabaseId.class))).thenReturn(database);
        when(database.singleUse()).thenReturn(context);
        when(context.executeQuery(any(Statement.class))).thenReturn(rows);
        var config = MulticloudDbClientConfig.builder().provider(ProviderId.SPANNER)
                .connection(Map.of("projectId", "test", "instanceId", "instance", "databaseId", "db")).build();
        var address = new ResourceAddress("db", "items");
        var key = MulticloudDbKey.of("key");
        try (MockedStatic<SpannerOptions> nativeOptions = mockStatic(SpannerOptions.class)) {
            nativeOptions.when(SpannerOptions::newBuilder).thenReturn(builder);
            try (var client = MulticloudDbClientFactory.create(config)) {
                var write = client.create(address, key, Map.of("value", 1));
                assertEquals(OperationOutcome.SUCCESS, write.diagnostics().outcome());
                assertNull(write.diagnostics().consumption());
                assertNull(write.diagnostics().statusCode());
                assertNull(write.diagnostics().etag());
                var absent = client.read(address, key);
                assertNull(absent.result());
                assertEquals(OperationOutcome.NOT_FOUND, absent.diagnostics().outcome());
                assertNotNull(absent.diagnostics().duration());
                var error = SpannerExceptionFactory.newSpannerException(ErrorCode.UNAVAILABLE, "unavailable");
                when(context.executeQuery(any(Statement.class))).thenThrow(error);
                var failure = assertThrows(MulticloudDbException.class, () -> client.read(address, key));
                assertEquals(14, failure.diagnostics().statusCode());
                assertEquals(StatusCodeDomain.GRPC, failure.diagnostics().statusCodeDomain());
                assertNull(failure.diagnostics().consumption());
                verify(database).write(anyIterable());
                verify(database, times(2)).singleUse();
                verify(context, times(2)).executeQuery(any(Statement.class));
                verify(rows).next();
                verify(rows).close();
                verifyNoMoreInteractions(database, context, rows);
            }
        }
    }
}
