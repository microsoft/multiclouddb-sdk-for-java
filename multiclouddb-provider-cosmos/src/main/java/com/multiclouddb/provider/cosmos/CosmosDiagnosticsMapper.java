// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemResponse;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceConsumption;
import com.multiclouddb.api.StatusCodeDomain;

import java.util.Map;

final class CosmosDiagnosticsMapper {
    private CosmosDiagnosticsMapper() { }

    static OperationDiagnostics from(CosmosItemResponse<?> response, String operation) {
        return metadata(operation, response.getStatusCode(), response.getActivityId(),
                response.getResponseHeaders(), response.getRequestCharge())
                .etag(response.getETag()).sessionToken(response.getSessionToken()).build();
    }

    static OperationDiagnostics from(CosmosException error, String operation) {
        return metadata(operation, error.getStatusCode(), error.getActivityId(),
                error.getResponseHeaders(), error.getRequestCharge()).build();
    }

    private static OperationDiagnostics.Builder metadata(String operation, int status, String requestId,
                                                          Map<String, String> headers, double charge) {
        var builder = OperationDiagnostics.builder(ProviderId.COSMOS, operation).requestId(requestId);
        if (status > 0) {
            builder.statusCode(status).statusCodeDomain(StatusCodeDomain.HTTP);
        }
        if (headers != null) {
            for (var entry : headers.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if ("etag".equalsIgnoreCase(name)) {
                    builder.etag(value);
                } else if ("x-ms-session-token".equalsIgnoreCase(name)) {
                    builder.sessionToken(value);
                } else if ("x-ms-request-charge".equalsIgnoreCase(name)
                        && value != null && !value.isBlank()) {
                    // The primitive getter also returns zero when no charge header exists.
                    builder.consumption(new ResourceConsumption(charge,
                            ResourceConsumption.Unit.COSMOS_REQUEST_UNIT, ResourceConsumption.Coverage.UNKNOWN));
                }
            }
        }
        return builder;
    }
}
