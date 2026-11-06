// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.provider.spanner;

import com.google.spanner.v1.CommitRequest;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.MethodDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Observes an existing commit protobuf, never row size or write eligibility. */
final class SpannerPayloadSizeInterceptor implements ClientInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger("com.multiclouddb.size");
    private static final int PAYLOAD_WARNING_BYTES = 400 * 1024;

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void sendMessage(ReqT message) {
                if (LOG.isWarnEnabled() && message instanceof CommitRequest request) {
                    // The protobuf may compute/cache this size on its first call.
                    int bytes = request.getSerializedSize();
                    if (bytes >= PAYLOAD_WARNING_BYTES) {
                        LOG.warn("size.payload-advisory provider=spanner operation=commit "
                                        + "metric=commit-request-protobuf bytes={} thresholdBytes={} "
                                        + "notStoredRowSize=true advisoryOnly=true",
                                bytes, PAYLOAD_WARNING_BYTES);
                    }
                }
                super.sendMessage(message);
            }
        };
    }
}
