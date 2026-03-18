// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.dynamo;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.PortabilityWarning;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.query.ExpressionTranslator;
import com.hyperscaledb.spi.HyperscaleDbProviderAdapter;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;

import java.util.List;

/**
 * ServiceLoader-discovered adapter for Amazon DynamoDB.
 */
public class DynamoProviderAdapter implements HyperscaleDbProviderAdapter {

    @Override
    public ProviderId providerId() {
        return ProviderId.DYNAMO;
    }

    @Override
    public HyperscaleDbProviderClient createClient(HyperscaleDbClientConfig config) {
        return new DynamoProviderClient(config);
    }

    @Override
    public ExpressionTranslator createExpressionTranslator() {
        return new DynamoExpressionTranslator();
    }

    @Override
    public List<PortabilityWarning> checkFeatureFlags(HyperscaleDbClientConfig config) {
        return DynamoExtensions.checkFeatureFlags(config);
    }
}
