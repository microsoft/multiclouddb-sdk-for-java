package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbResponseMetadata;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamoPointDiagnosticsTest {

    @Test
    void writeDiagnosticsPreferWriteCapacityUnits() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        PutItemResponse response = mock(PutItemResponse.class);
        DynamoDbResponseMetadata metadata = mock(DynamoDbResponseMetadata.class);
        when(metadata.requestId()).thenReturn("req-1");
        when(response.responseMetadata()).thenReturn(metadata);
        when(response.consumedCapacity()).thenReturn(ConsumedCapacity.builder()
                .capacityUnits(9.0)
                .writeCapacityUnits(3.0)
                .build());
        when(response.sdkHttpResponse()).thenReturn(SdkHttpResponse.builder().statusCode(200).build());
        when(dynamo.putItem(any(PutItemRequest.class))).thenReturn(response);

        DynamoProviderClient client = new DynamoProviderClient(dynamo);
        OperationDiagnostics diagnostics = client.createWithDiagnostics(
                new ResourceAddress("db", "col"),
                MulticloudDbKey.of("pk", "sk"),
                Map.of("name", "value"),
                OperationOptions.defaults());

        assertNotNull(diagnostics);
        assertEquals(OperationNames.CREATE, diagnostics.operation());
        assertEquals(3.0, diagnostics.requestCharge(), 1e-9);
        assertEquals(Integer.valueOf(200), diagnostics.statusCode());
    }

    @Test
    void readDiagnosticsPreferReadCapacityUnits() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        GetItemResponse response = mock(GetItemResponse.class);
        DynamoDbResponseMetadata metadata = mock(DynamoDbResponseMetadata.class);
        when(metadata.requestId()).thenReturn("req-2");
        when(response.responseMetadata()).thenReturn(metadata);
        when(response.consumedCapacity()).thenReturn(ConsumedCapacity.builder()
                .capacityUnits(9.0)
                .readCapacityUnits(2.0)
                .build());
        when(response.sdkHttpResponse()).thenReturn(SdkHttpResponse.builder().statusCode(200).build());
        when(response.hasItem()).thenReturn(true);
        when(response.item()).thenReturn(Map.of(
                DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS("pk"),
                DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS("sk"),
                "name", AttributeValue.fromS("value")));
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(response);

        DynamoProviderClient client = new DynamoProviderClient(dynamo);
        DocumentResult result = client.read(new ResourceAddress("db", "col"),
                MulticloudDbKey.of("pk", "sk"), OperationOptions.defaults());

        assertNotNull(result);
        assertNotNull(result.diagnostics());
        assertEquals(OperationNames.READ, result.diagnostics().operation());
        assertEquals(2.0, result.diagnostics().requestCharge(), 1e-9);
    }
}
