# Quickstart: Cosmos Gateway Transport Defaults

## Default configuration

No transport keys are required:

```properties
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=https://account.documents.azure.com:443/
multiclouddb.connection.key=<account-key>
```

The provider always constructs a Gateway client with HTTP/2 enabled. Azure
Cosmos SDK 4.82.0 then probes Gateway V2 thin-client proxy connectivity. It
uses Gateway V2 after a successful probe and otherwise remains on Gateway V1.

> **Thin client requires nothing extra to install or deploy.** It is an
> Azure SDK routing path inside Gateway mode. See the
> [request-path diagram](design.md#what-thin-client-means) and
> [selection flow](design.md#gateway-v2-selection).

## Opt out of Gateway V2

```properties
multiclouddb.connection.thinClientEnabled=false
```

This is a hard process-wide opt-out. Gateway mode and HTTP/2 remain enabled.

## Force Gateway V2

```properties
multiclouddb.connection.thinClientEnabled=true
```

This is a hard process-wide opt-in and bypasses the connectivity probe. Use it
only after verifying Gateway V2 availability for the account, region, and
network path.

## Operator-level configuration

The native Azure SDK settings take precedence over the Multicloud DB
connection property:

```powershell
java -DCOSMOS.THINCLIENT_ENABLED=false -jar app.jar
```

or:

```text
COSMOS_THINCLIENT_ENABLED=false
```

Because the Azure SDK exposes this selection globally, all Cosmos clients in
one JVM must use the same effective value.

## Programmatic configuration

```java
MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
    .provider(ProviderId.COSMOS)
    .connection("endpoint", "https://account.documents.azure.com:443/")
    .connection("key", accountKey)
    .connection("thinClientEnabled", "false")
    .build();

MulticloudDbClient client = MulticloudDbClientFactory.create(config);
```

## Migration

Remove these keys from existing configuration:

```properties
multiclouddb.connection.connectionMode=gateway
multiclouddb.connection.gatewayHttp2Enabled=true
```

They are no longer switches. Their presence fails client construction with an
actionable message so stale configuration is not silently ignored.
