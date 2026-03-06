package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.ProviderId;
import org.junit.jupiter.api.Tag;

@Tag("cosmos")
@Tag("emulator")
public class CosmosDiagnosticsTest extends DiagnosticsConformanceTest {
    @Override
    protected ProviderId providerId() {
        return ProviderId.COSMOS;
    }
}
