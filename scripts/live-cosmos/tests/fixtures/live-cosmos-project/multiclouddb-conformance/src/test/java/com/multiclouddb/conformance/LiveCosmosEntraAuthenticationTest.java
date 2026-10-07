package com.multiclouddb.conformance;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
public final class LiveCosmosEntraAuthenticationTest {
  @Test
  void entraAuthenticationPerformsCreateReadDelete() throws Exception {
    com.multiclouddb.api.MulticloudDbClientConfig config =
        com.multiclouddb.api.MulticloudDbClientConfig.builder()
            .provider(com.multiclouddb.api.ProviderId.COSMOS)
            .connection(
                /* approved property name */ "endpoint",
                java.lang.System.getProperty(/* pipeline-owned property */ "cosmos.endpoint"))
            .build();
    var key = com.multiclouddb.conformance.ConformanceHarness.uniqueKey("entra-live");
    var address = new com.multiclouddb.api.ResourceAddress("fixture-db", "fixture-container");
    try (com.multiclouddb.api.MulticloudDbClient client =
        com.multiclouddb.api.MulticloudDbClientFactory.create(config)) {
      try {
        client.create(address, key, Map.of("id", key.partitionKey()));
        var result = client.read(address, key);
        assertNotNull(result);
      } finally {
        client.delete(address, key);
      }
    }
  }
}
