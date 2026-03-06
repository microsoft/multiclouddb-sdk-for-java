package com.hyperscaledb.conformance.us2;

import com.azure.cosmos.CosmosClient;
import com.hyperscaledb.api.ProviderId;
import org.junit.jupiter.api.Tag;

/**
 * Cosmos DB native client access conformance test.
 */
@Tag("cosmos")
@Tag("emulator")
public class CosmosNativeClientAccessTest extends NativeClientAccessConformanceTest {

    @Override
    protected ProviderId providerId() {
        return ProviderId.COSMOS;
    }

    @Override
    protected Class<?> expectedNativeClientType() {
        return CosmosClient.class;
    }
}
