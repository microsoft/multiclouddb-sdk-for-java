// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.spanner;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.query.ExpressionTranslator;
import com.hyperscaledb.spi.HyperscaleDbProviderAdapter;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;

/**
 * ServiceLoader-discovered adapter for Google Cloud Spanner.
 */
public class SpannerProviderAdapter implements HyperscaleDbProviderAdapter {

    @Override
    public ProviderId providerId() {
        return ProviderId.SPANNER;
    }

    @Override
    public HyperscaleDbProviderClient createClient(HyperscaleDbClientConfig config) {
        return new SpannerProviderClient(config);
    }

    @Override
    public ExpressionTranslator createExpressionTranslator() {
        return new SpannerExpressionTranslator();
    }
}
