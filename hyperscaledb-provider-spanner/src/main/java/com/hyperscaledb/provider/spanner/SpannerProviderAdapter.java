package com.hyperscaledb.provider.spanner;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.PortabilityWarning;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.query.ExpressionTranslator;
import com.hyperscaledb.spi.HyperscaleDbProviderAdapter;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;

import java.util.List;

/**
 * ServiceLoader-discovered adapter for Google Cloud Spanner (stub).
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

    @Override
    public List<PortabilityWarning> checkFeatureFlags(HyperscaleDbClientConfig config) {
        return SpannerExtensions.checkFeatureFlags(config);
    }
}
