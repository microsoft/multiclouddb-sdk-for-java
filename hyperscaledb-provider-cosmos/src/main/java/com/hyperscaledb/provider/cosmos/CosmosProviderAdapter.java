// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.cosmos;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.query.ExpressionTranslator;
import com.hyperscaledb.spi.HyperscaleDbProviderAdapter;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;

/**
 * ServiceLoader-discovered adapter for Azure Cosmos DB.
 */
public class CosmosProviderAdapter implements HyperscaleDbProviderAdapter {

    @Override
    public ProviderId providerId() {
        return ProviderId.COSMOS;
    }

    @Override
    public HyperscaleDbProviderClient createClient(HyperscaleDbClientConfig config) {
        return new CosmosProviderClient(config);
    }

    @Override
    public ExpressionTranslator createExpressionTranslator() {
        return new CosmosExpressionTranslator();
    }
}
