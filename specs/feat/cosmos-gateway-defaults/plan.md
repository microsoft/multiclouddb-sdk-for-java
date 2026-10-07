# Implementation Plan: Cosmos Gateway Transport Defaults

**Branch**: `feat/cosmos-gateway-defaults` | **Date**: 2026-08-31 |
**Spec**: [spec.md](spec.md)
**Input**: Feature specification from
`/specs/feat/cosmos-gateway-defaults/spec.md`

## Summary

Standardize the Cosmos provider on Gateway mode with HTTP/2 explicitly enabled,
upgrade Azure Cosmos Java SDK from 4.78.0 to 4.82.0, and use the SDK's
probe-gated Gateway V2 thin-client routing as the zero-configuration default.
Retain `thinClientEnabled` as a strict process-wide hard opt-in/opt-out, honor
operator-supplied Azure SDK settings, and fail fast when removed
`connectionMode` or `gatewayHttp2Enabled` keys are present.

The change is isolated to Cosmos client construction, provider configuration,
tests, examples, changelogs, and design artifacts. It does not alter the
provider-neutral API or cross-provider data semantics.

## Technical Context

**Language/Version**: Java 17 LTS
**Primary Dependencies**: Azure Cosmos Java SDK 4.82.0, Azure Identity 1.18.2,
Jackson 2.22.1, SLF4J 2.0.12
**Storage**: N/A; construction-time provider configuration only
**Testing**: JUnit 5.10.2, Mockito 5.11.0, Maven Surefire, Cosmos emulator
conformance
**Target Platform**: JVM 17+ on Windows and Linux; Azure Cosmos DB and Cosmos
emulator endpoints
**Project Type**: Multi-module Maven library
**Performance Goals**: Add no wrapper-owned request path or network probe;
delegate Gateway V2 probing/fallback to the Azure SDK and perform wrapper
selection only during client construction
**Constraints**: Gateway and HTTP/2 are fixed; Direct/RNTBD is unavailable
through wrapper configuration; Azure thin-client selection is JVM-global;
provider-neutral API and behavior must remain unchanged
**Scale/Scope**: One dependency update, two Cosmos production classes, focused
provider tests, four conformance/example fixtures, user documentation,
changelogs, and feature design artifacts

There are no unresolved technical clarifications.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Status | Evidence |
|---|---|---|
| **0 - Portability-First Default** | PASS | Portable application operations are unchanged. Cosmos-specific transport selection remains connection configuration owned by the adapter. |
| **1 - Thin Wrapper** | PASS | The official Azure SDK performs all I/O, HTTP/2 transport, Gateway V2 probing, authentication, and fallback. No wrapper probe is introduced. |
| **2 - Capability-Based API** | PASS | No provider-neutral capability is promised or changed. This is a Cosmos connectivity policy, not a portable operation feature. |
| **3 - Consistent Surface** | PASS | CRUD/query inputs, outputs, errors, and diagnostics are unchanged. The SDK-global thin-client limitation is documented explicitly rather than presented as truly per-client. |
| **3.1 - Configuration-Only Portability** | PASS | Default behavior requires no code or transport setting. The operational opt-out is configuration-driven. |
| **4 - Explicit Reliability Controls** | PASS | Unset thin-client state uses the provider SDK's connectivity probe and Gateway V1 fallback. Explicit hard opt-in failures remain visible. |
| **5 - Diagnostics Without Secrets** | PASS | Configuration conflicts fail with actionable, non-secret messages. Endpoint keys and credentials are never logged by this change. |
| **5.1 - Layered Diagnostics** | PASS | Wrapper validation identifies the invalid setting; native SDK connectivity failures remain available for explicit hard opt-in troubleshooting. |
| **Provider Adapter Requirements** | PASS | The adapter continues to delegate to the official SDK and exposes no new provider type through the portable API. |
| **Testing Minimum** | PASS | Provider construction behavior has focused unit coverage and existing Cosmos emulator conformance remains the integration gate. |
| **Versioning & Compatibility** | PASS | The provider changelog states SDK 4.82.0 and the pre-release breaking configuration/constant removal. |

**Pre-research gate result**: PASS. No constitutional violations require an
exception.

## Research Decisions

Phase 0 research is captured in [research.md](research.md):

1. Gateway mode is the only supported wrapper path.
2. HTTP/2 must be explicitly enabled because SDK 4.82.0 does not enable it by
   default.
3. SDK 4.82.0 supplies probe-gated Gateway V2 with Gateway V1 fallback.
4. Unset, `true`, and `false` must preserve the native SDK tri-state.
5. Thin-client selection is process-wide and follows operator-first precedence.
6. The narrower query-plan kill switch does not require wrapper exposure.
7. Removed switches fail fast instead of becoming silent no-ops.

## Design

The detailed architecture and migration rationale are in
[design.md](design.md). The external behavior is fixed by
[contracts/configuration-contract.md](contracts/configuration-contract.md), and
the construction-time entities and transitions are in
[data-model.md](data-model.md).

### Construction Sequence

1. Read and validate the Cosmos endpoint.
2. Reject removed `connectionMode` and `gatewayHttp2Enabled` keys.
3. Parse `thinClientEnabled` using an explicit Boolean whitelist.
4. Preserve a non-empty Azure SDK system-property or environment override.
5. If no operator override exists, map an explicit connection value to the SDK
   system property; leave an absent value untouched.
6. Configure endpoint and key or Azure identity on `CosmosClientBuilder`.
7. Attach `GatewayConnectionConfig` containing
   `Http2ConnectionConfig(enabled=true)`.
8. Apply consistency and user-agent configuration.
9. Build the native Cosmos client.

### Configuration Precedence

```text
COSMOS.THINCLIENT_ENABLED system property
    > COSMOS_THINCLIENT_ENABLED environment variable
    > multiclouddb.connection.thinClientEnabled
    > unset SDK auto-probe/fallback
```

The connection-to-system-property check-and-set is synchronized. Different
thin-client policies in one JVM are not supported by the underlying Azure SDK
and require process isolation.

## Project Structure

### Documentation (this feature)

```text
specs/feat/cosmos-gateway-defaults/
|-- spec.md
|-- plan.md
|-- research.md
|-- design.md
|-- data-model.md
|-- quickstart.md
`-- contracts/
    `-- configuration-contract.md
```

No `tasks.md` is created by this planning phase.

### Source Code (repository root)

```text
pom.xml
    # Azure Cosmos SDK version

multiclouddb-provider-cosmos/
|-- CHANGELOG.md
`-- src/
    |-- main/java/com/multiclouddb/provider/cosmos/
    |   |-- CosmosConstants.java
    |   `-- CosmosProviderClient.java
    `-- test/java/com/multiclouddb/provider/cosmos/
        |-- CosmosConstantsTest.java
        `-- CosmosGatewayDefaultsTest.java

multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/
    # Cosmos fixtures no longer set connectionMode

multiclouddb-e2e/src/main/resources/cosmos.properties.template
    # Fixed transport and optional thin-client opt-out example

docs/
|-- configuration.md
`-- changelog.md

README.md
specs/001-clouddb-sdk/
|-- plan.md
`-- spec.md
```

**Structure Decision**: Keep implementation in the existing Cosmos provider
module and place feature-specific design artifacts under the path selected by
the repository planning script. No module, service, endpoint, or portable API
package is added.

## Implementation Phases

### Phase 0 - Research

- Verify HTTP/2 defaults and public builder configuration in SDK 4.82.0.
- Verify Gateway V2 behavior for unset, `false`, and `true`.
- Verify system-property and environment-variable names and precedence.
- Verify query-plan routing follows the main thin-client eligibility gate.
- Record decisions and rejected alternatives in `research.md`.

### Phase 1 - Design and Contract

- Define prioritized user scenarios and acceptance criteria in `spec.md`.
- Define fixed and tri-state configuration entities in `data-model.md`.
- Define the external configuration contract and migration errors.
- Document architecture, global-state boundary, rollout, rollback, and PR
  dependency in `design.md`.
- Provide default, opt-out, force, and migration examples in `quickstart.md`.
- Update the original SDK plan with the Cosmos transport amendment.

### Phase 2 - Implementation

- Upgrade Azure Cosmos Java SDK to 4.82.0.
- Remove public connection-mode constants.
- Add `thinClientEnabled` and internal Azure SDK setting names.
- Reject removed transport keys.
- Always construct Gateway mode with HTTP/2 enabled.
- Preserve SDK tri-state and operator precedence.
- Update active examples, conformance fixtures, and changelogs.
- Add focused unit tests.

## Test Strategy

| Level | Coverage |
|---|---|
| Unit | Gateway overload selected, HTTP/2 enabled, Direct never selected |
| Unit | Unset thin-client value leaves SDK setting untouched |
| Unit | Explicit `false` and `true` map to hard opt-out/opt-in |
| Unit | Operator SDK property is not overwritten |
| Unit | Malformed Boolean and removed keys fail before builder creation |
| Build | Cosmos provider and upstream API reactor |
| Integration | Cosmos emulator conformance under fixed Gateway HTTP/2 |
| Regression | DynamoDB and Spanner emulator jobs remain unchanged and green |

## Migration and Documentation

- Remove active `connectionMode` examples from README, configuration guide,
  conformance fixtures, and E2E template.
- Explain fixed Gateway/HTTP2 separately from Gateway V2 routing.
- Document JVM-wide semantics and operator precedence.
- Add SDK 4.82.0 and breaking pre-release cleanup to both Cosmos and aggregate
  changelogs.
- Preserve historical changelog statements as historical records.

## Post-Design Constitution Re-check

The final design introduces no portable API, provider capability, retry,
diagnostic, data-model, or error-semantic divergence. The only ambient state is
imposed by the official Azure SDK's thin-client switch; the design contains it
with synchronized first-value precedence, exposes it through normal
configuration, and documents process isolation for conflicting policies.

**Post-design gate result**: PASS. Implementation may proceed without a
constitution exception.

## Complexity Tracking

No constitution violations or additional architectural layers require
justification.
