# Data Model: Cosmos Gateway Transport Defaults

This feature does not add persisted application data. Its model is the
construction-time configuration and the effective native transport policy.

## FixedTransportPolicy

Represents the invariant transport applied to every Cosmos client.

| Field | Type | Value | Validation |
|---|---|---|---|
| `connectionMode` | enum | `GATEWAY` | Fixed; no user input accepted |
| `http2Enabled` | Boolean | `true` | Fixed; no user input accepted |

Relationships:

- One `FixedTransportPolicy` is applied to every Cosmos provider client.
- It owns one Azure `GatewayConnectionConfig`.
- The gateway configuration owns one Azure `Http2ConnectionConfig`.

## ThinClientPreference

Represents the requested Gateway V2 thin-client proxy behavior.

| State | Connection value | SDK property written | Routing behavior |
|---|---|---|---|
| `AUTO` | absent | none | SDK connectivity probe; V2 on success, V1 otherwise |
| `FORCE_ENABLED` | `true` | `true` | Hard opt-in; probe bypassed |
| `DISABLED` | `false` | `false` | Hard opt-out; no probe |

Validation:

- Matching is case-insensitive.
- Only `true` and `false` are accepted when the key is present.
- Any other value fails client construction before network I/O.

## ThinClientConfigurationSource

Represents the source of the effective process-wide preference.

Precedence:

1. Non-empty JVM system property `COSMOS.THINCLIENT_ENABLED`
2. Non-empty environment variable `COSMOS_THINCLIENT_ENABLED`
3. `thinClientEnabled` Multicloud DB connection property
4. Unset SDK default (`AUTO`)

Relationships and constraints:

- A connection property is translated into the JVM system property because the
  Azure SDK has no per-client API.
- The translation is synchronized within the Cosmos provider class.
- Once a non-empty process value exists, later client construction does not
  overwrite it.
- All Cosmos clients in one JVM must use a compatible preference.

## RemovedTransportSetting

Represents a stale configuration key that is no longer supported.

| Key | Former purpose | Construction result |
|---|---|---|
| `connectionMode` | Select Gateway or Direct | Reject: Gateway is fixed |
| `gatewayHttp2Enabled` | Enable or disable Gateway HTTP/2 | Reject: HTTP/2 is fixed |

## State Transitions

```text
raw connection config
        |
        +-- removed key present --------> REJECTED
        |
        +-- thin value malformed -------> REJECTED
        |
        +-- operator override present --> OPERATOR_CONTROLLED
        |
        +-- thin=true -------------------> FORCE_ENABLED
        |
        +-- thin=false ------------------> DISABLED
        |
        `-- thin absent -----------------> AUTO

AUTO -- probe success ------------------> GATEWAY_V2
AUTO -- probe failure/no verdict -------> GATEWAY_V1
```

The fixed Gateway/HTTP2 policy applies in every non-rejected state.
