# Cosmos Transport Configuration Contract

This feature changes a Java library construction contract, not a REST or
GraphQL endpoint. This document is the external configuration contract.

## Supported input

| Property | Required | Values | Default |
|---|---:|---|---|
| `multiclouddb.connection.endpoint` | yes | Non-blank Cosmos account URI | none |
| `multiclouddb.connection.key` | no | Cosmos account key | Azure identity |
| `multiclouddb.connection.tenantId` | no | Azure tenant ID | credential-chain default |
| `multiclouddb.connection.thinClientEnabled` | no | `true` or `false`, case-insensitive | unset / SDK auto-probe |

## Fixed output contract

For every valid Cosmos client construction:

```text
CosmosClientBuilder
  .gatewayMode(
      GatewayConnectionConfig
        .http2ConnectionConfig(
            Http2ConnectionConfig.enabled = true))
```

`directMode(...)` is never selected.

## Thin-client precedence contract

| SDK system property | SDK environment variable | Connection property | Effective behavior |
|---|---|---|---|
| non-empty | any | any | System property wins |
| empty/absent | non-empty | any | Environment variable wins |
| empty/absent | empty/absent | `true` | Hard opt-in |
| empty/absent | empty/absent | `false` | Hard opt-out |
| empty/absent | empty/absent | absent | SDK probe and fallback |

The connection property is mapped to the SDK system property only when no
operator-level value exists.

## Rejected input

| Property | Result |
|---|---|
| `multiclouddb.connection.connectionMode` | `IllegalArgumentException`: Gateway mode is always used |
| `multiclouddb.connection.gatewayHttp2Enabled` | `IllegalArgumentException`: Gateway HTTP/2 is always enabled |
| `multiclouddb.connection.thinClientEnabled=<other>` | `IllegalArgumentException`: value must be `true` or `false` |

All validation occurs before native client construction and before network
I/O.

## Compatibility

- Provider-neutral interfaces and operation semantics are unchanged.
- The public Cosmos constants for connection-mode selection are removed.
- Existing pre-release configuration containing a removed key must be updated.
- `thinClientEnabled` is process-wide due to the native SDK contract, even
  though it is accepted through the standard connection-property map.
