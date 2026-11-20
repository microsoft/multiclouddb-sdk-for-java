// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceConsumption;
import com.multiclouddb.api.StatusCodeDomain;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

final class DynamoDiagnosticsMapper {
    private DynamoDiagnosticsMapper() { }

    static OperationDiagnostics from(AwsResponse response, String operation, ConsumedCapacity capacity) {
        SdkHttpResponse http = response.sdkHttpResponse();
        var builder = metadata(operation, http == null ? 0 : http.statusCode(), requestId(response));
        if (capacity != null && capacity.capacityUnits() != null) {
            builder.consumption(new ResourceConsumption(capacity.capacityUnits(),
                    ResourceConsumption.Unit.DYNAMODB_CAPACITY_UNIT, ResourceConsumption.Coverage.UNKNOWN));
        }
        return builder.build();
    }

    static OperationDiagnostics from(DynamoDbException error, String operation) {
        SdkHttpResponse http = error.awsErrorDetails() == null ? null : error.awsErrorDetails().sdkHttpResponse();
        return metadata(operation, error.statusCode(), requestId(error.requestId(), http)).build();
    }

    static String requestId(AwsResponse response) {
        String id = response.responseMetadata() == null ? null : response.responseMetadata().requestId();
        return requestId(id, response.sdkHttpResponse());
    }

    private static String requestId(String id, SdkHttpResponse http) {
        if (unavailable(id) && http != null) {
            id = http.firstMatchingHeader(DynamoConstants.HEADER_REQUEST_ID).orElse(null);
        }
        return unavailable(id) ? null : id;
    }

    private static boolean unavailable(String id) {
        return id == null || id.isBlank() || "UNKNOWN".equals(id);
    }

    private static OperationDiagnostics.Builder metadata(String operation, int status, String requestId) {
        var builder = OperationDiagnostics.builder(ProviderId.DYNAMO, operation).requestId(requestId);
        if (status > 0) {
            builder.statusCode(status).statusCodeDomain(StatusCodeDomain.HTTP);
        }
        return builder;
    }
}
