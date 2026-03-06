package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.ProviderId;
import org.junit.jupiter.api.Tag;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB native client access conformance test.
 */
@Tag("dynamo")
@Tag("emulator")
public class DynamoNativeClientAccessTest extends NativeClientAccessConformanceTest {

    @Override
    protected ProviderId providerId() {
        return ProviderId.DYNAMO;
    }

    @Override
    protected Class<?> expectedNativeClientType() {
        return DynamoDbClient.class;
    }
}
