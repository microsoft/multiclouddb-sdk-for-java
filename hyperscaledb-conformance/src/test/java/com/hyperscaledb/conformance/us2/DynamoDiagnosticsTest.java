package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.ProviderId;
import org.junit.jupiter.api.Tag;

@Tag("dynamo")
@Tag("emulator")
public class DynamoDiagnosticsTest extends DiagnosticsConformanceTest {
    @Override
    protected ProviderId providerId() {
        return ProviderId.DYNAMO;
    }
}
