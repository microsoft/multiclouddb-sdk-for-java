// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.provider.spanner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.spanner.v1.CommitRequest;
import com.google.spanner.v1.CommitResponse;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import io.grpc.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpannerPayloadSizeInterceptorTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.multiclouddb.size");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level oldLevel;

    @BeforeEach
    void capture() {
        oldLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(oldLevel);
        appender.stop();
    }

    private static <T> MethodDescriptor.Marshaller<T> unusedMarshaller() {
        return new MethodDescriptor.Marshaller<>() {
            public InputStream stream(T value) { throw new AssertionError("Extra serialization"); }
            public T parse(InputStream input) { throw new AssertionError("Unexpected parsing"); }
        };
    }

    private static MethodDescriptor<CommitRequest, CommitResponse> method() {
        return MethodDescriptor.<CommitRequest, CommitResponse>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("google.spanner.v1.Spanner/Commit")
                .setRequestMarshaller(unusedMarshaller())
                .setResponseMarshaller(unusedMarshaller()).build();
    }

    // Synthetic protobuf fixture, not a valid service session or a native size limit.
    private static CommitRequest payload(int bytes) {
        return CommitRequest.newBuilder().setSession("s".repeat(bytes - 4)).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void boundaryWarningsForwardTheSameRequestWithoutReserializingOrRejecting() {
        var method = method();
        Channel channel = mock(Channel.class);
        ClientCall<CommitRequest, CommitResponse> delegate = mock(ClientCall.class);
        when(channel.newCall(method, CallOptions.DEFAULT)).thenReturn(delegate);
        var call = new SpannerPayloadSizeInterceptor().interceptCall(method, CallOptions.DEFAULT, channel);
        for (int bytes : new int[]{409599, 409600, 409601}) {
            appender.list.clear();
            clearInvocations(delegate);
            var request = payload(bytes);
            assertDoesNotThrow(() -> call.sendMessage(request));
            assertEquals(bytes, request.getSerializedSize());
            verify(delegate).sendMessage(same(request));
            verifyNoMoreInteractions(delegate);
            assertEquals(bytes >= 409600 ? 1 : 0, appender.list.size());
            if (bytes >= 409600) {
                assertEquals("size.payload-advisory provider=spanner operation=commit "
                                + "metric=commit-request-protobuf bytes=" + bytes
                                + " thresholdBytes=409600 notStoredRowSize=true advisoryOnly=true",
                        appender.list.get(0).getFormattedMessage());
                assertNull(appender.list.get(0).getThrowableProxy());
            }
        }
        verify(channel, times(1)).newCall(method, CallOptions.DEFAULT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeCompletionAndErrorsAreForwardedWithoutSdkRetryOrWarningExceptions() {
        var method = method();
        Channel channel = mock(Channel.class);
        ClientCall<CommitRequest, CommitResponse> delegate = mock(ClientCall.class);
        when(channel.newCall(method, CallOptions.DEFAULT)).thenReturn(delegate);
        ClientCall.Listener<CommitResponse> listener = mock(ClientCall.Listener.class);
        var headers = new Metadata();
        var received = new java.util.concurrent.atomic.AtomicReference<ClientCall.Listener<CommitResponse>>();
        doAnswer(invocation -> { received.set(invocation.getArgument(0)); return null; })
                .when(delegate).start(any(), any());
        doAnswer(invocation -> {
            received.get().onMessage(CommitResponse.getDefaultInstance());
            received.get().onClose(Status.OK, headers);
            return null;
        }).when(delegate).halfClose();
        var call = new SpannerPayloadSizeInterceptor().interceptCall(method, CallOptions.DEFAULT, channel);
        call.start(listener, headers);
        verify(delegate).start(same(listener), same(headers));
        call.sendMessage(payload(409600));
        call.halfClose();
        verify(listener).onMessage(CommitResponse.getDefaultInstance());
        verify(listener).onClose(Status.OK, headers);
        assertThrows(AssertionError.class, () -> assertTrue(appender.list.isEmpty()),
                "Only the test assertion fails after successful completion");
        doAnswer(invocation -> {
            received.get().onClose(Status.INVALID_ARGUMENT, headers);
            return null;
        }).when(delegate).halfClose();
        call.halfClose();
        verify(listener).onClose(Status.INVALID_ARGUMENT, headers);
        RuntimeException nativeFailure = new IllegalStateException("native transport failure");
        doThrow(nativeFailure).when(delegate).sendMessage(any());
        assertSame(nativeFailure, assertThrows(RuntimeException.class,
                () -> call.sendMessage(payload(409600))));
        verify(delegate, times(2)).sendMessage(any());
        verify(channel, times(1)).newCall(method, CallOptions.DEFAULT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledWarningLevelAndNonCommitRequestsDoNotProduceWarnings() {
        Channel channel = mock(Channel.class);
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        MethodDescriptor<Object, Object> method = MethodDescriptor.newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("test/Other")
                .setRequestMarshaller(unusedMarshaller()).setResponseMarshaller(unusedMarshaller()).build();
        when(channel.newCall(method, CallOptions.DEFAULT)).thenReturn(delegate);
        var call = new SpannerPayloadSizeInterceptor().interceptCall(method, CallOptions.DEFAULT, channel);
        Object other = new Object() {
            @Override public String toString() { throw new AssertionError("secret"); }
        };
        call.sendMessage(other);
        logger.setLevel(Level.ERROR);
        var request = spy(payload(409600));
        call.sendMessage(request);
        assertTrue(appender.list.isEmpty());
        verify(delegate).sendMessage(same(other));
        verify(delegate).sendMessage(same(request));
        verify(request, never()).getSerializedSize();
    }

    @Test
    void providerRegistersPublicHookWithoutChangingExistingClientConfiguration() {
        var builder = mock(SpannerOptions.Builder.class, RETURNS_SELF);
        var options = mock(SpannerOptions.class);
        var spanner = mock(Spanner.class);
        when(builder.build()).thenReturn(options);
        when(options.getService()).thenReturn(spanner);
        when(spanner.getDatabaseClient(any(DatabaseId.class)))
                .thenReturn(mock(com.google.cloud.spanner.DatabaseClient.class));
        try (var factory = mockStatic(SpannerOptions.class)) {
            factory.when(SpannerOptions::newBuilder).thenReturn(builder);
            try (var client = new SpannerProviderClient(MulticloudDbClientConfig.builder()
                    .provider(ProviderId.SPANNER)
                    .connection(Map.of("projectId", "project", "instanceId", "instance", "databaseId", "db"))
                    .build())) {
                var captor = org.mockito.ArgumentCaptor.forClass(com.google.api.gax.grpc.GrpcInterceptorProvider.class);
                verify(builder).setInterceptorProvider(captor.capture());
                assertEquals(1, captor.getValue().getInterceptors().size());
                assertInstanceOf(SpannerPayloadSizeInterceptor.class, captor.getValue().getInterceptors().get(0));
                verify(builder).setProjectId("project");
                verify(builder).setHeaderProvider(any());
                verify(builder, never()).setChannelProvider(any());
                verify(builder, never()).setEmulatorHost(any());
            }
        }
        verify(spanner).close();
    }
}
