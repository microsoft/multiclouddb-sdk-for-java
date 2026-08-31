# Feature Specification: Cosmos Gateway Transport Defaults

**Feature Branch**: `feat/cosmos-gateway-defaults`
**Created**: 2026-08-31
**Status**: In review
**Input**: Cosmos must use Gateway mode only, HTTP/2 must be fixed on, and
Gateway V2 thin-client proxy routing must be eligible by default with an
explicit user opt-out.

## User Scenarios & Testing

### User Story 1 - Safe Gateway Defaults (Priority: P1)

As an SDK user, I want every Cosmos client to use the supported Gateway
transport automatically so that I do not need provider-specific transport
knowledge to establish a safe, performant connection.

**Why this priority**: A single safe default removes configuration drift and
prevents applications from unknowingly selecting a different network protocol.

**Independent Test**: Construct a Cosmos provider client with only endpoint and
credentials, then verify that the native builder receives Gateway mode with
HTTP/2 enabled and never receives Direct mode.

**Acceptance Scenarios**:

1. **Given** valid Cosmos endpoint and authentication settings, **When** a
   client is created, **Then** Gateway mode is selected.
2. **Given** no transport settings, **When** a client is created, **Then**
   HTTP/2 is explicitly enabled.
3. **Given** no thin-client override, **When** Gateway V2 is reachable, **Then**
   the provider SDK may route through Gateway V2 after its connectivity probe.
4. **Given** no thin-client override, **When** Gateway V2 is not reachable,
   **Then** the provider SDK remains on Gateway V1 without failing client
   construction.

---

### User Story 2 - Explicit Gateway V2 Opt-Out (Priority: P2)

As an operator, I want to disable Gateway V2 thin-client proxy routing through
configuration so that I can mitigate a regional, account, or intermediary
compatibility issue without changing application code.

**Why this priority**: Gateway V2 is the preferred default, but operators need
a deterministic kill switch for incident response and compatibility.

**Independent Test**: Create a client with `thinClientEnabled=false` and verify
that the provider supplies the Azure SDK hard opt-out before native client
construction.

**Acceptance Scenarios**:

1. **Given** `thinClientEnabled=false`, **When** a Cosmos client is created,
   **Then** Gateway V2 is disabled process-wide through the Azure SDK setting.
2. **Given** `thinClientEnabled=true`, **When** a Cosmos client is created,
   **Then** the Azure SDK receives an explicit hard opt-in.
3. **Given** an operator-supplied Azure SDK system property or environment
   variable, **When** the connection property disagrees, **Then** the
   operator-supplied value wins.

---

### User Story 3 - Actionable Migration Failure (Priority: P3)

As an existing pre-release user, I want removed transport settings to fail
clearly so that a stale Direct-mode or HTTP/2-off configuration cannot appear
to work while being silently ignored.

**Why this priority**: Silent configuration changes make deployment behavior
unpredictable and are harder to diagnose than construction-time failures.

**Independent Test**: Attempt client construction with each removed key and
verify that it fails before network I/O with migration guidance.

**Acceptance Scenarios**:

1. **Given** any `connectionMode` value, **When** a client is created, **Then**
   construction fails and states that Gateway mode is fixed.
2. **Given** any `gatewayHttp2Enabled` value, **When** a client is created,
   **Then** construction fails and states that Gateway HTTP/2 is fixed.
3. **Given** an invalid `thinClientEnabled` value, **When** a client is
   created, **Then** construction fails and lists the valid Boolean values.

### Edge Cases

- A JVM system property and environment variable are both present: the Azure
  SDK system-property precedence is preserved.
- Multiple Cosmos clients request different thin-client values: the first
  effective JVM-wide setting remains authoritative; documentation requires a
  consistent process-wide value.
- The thin-client setting is absent: the provider must not write a system
  property, because doing so would bypass SDK 4.82's safe connectivity probe.
- Gateway V2 is unsupported by the account or network path: the SDK probe must
  leave traffic on Gateway V1.
- A stale transport key uses the value that is now fixed (`gateway` or `true`):
  it still fails so the removed configuration surface cannot persist.

## Requirements

### Functional Requirements

- **FR-001**: The Cosmos provider MUST always select Gateway connection mode.
- **FR-002**: The Cosmos provider MUST NOT expose a supported Direct-mode
  configuration option.
- **FR-003**: The Cosmos provider MUST explicitly enable HTTP/2 on every native
  Cosmos client.
- **FR-004**: The Cosmos provider MUST NOT expose a supported HTTP/2 enablement
  toggle.
- **FR-005**: The provider MUST use an Azure Cosmos SDK version whose unset
  thin-client state performs Gateway V2 connectivity probing with Gateway V1
  fallback.
- **FR-006**: When `thinClientEnabled` is absent, the provider MUST leave the
  Azure SDK thin-client property unset.
- **FR-007**: `thinClientEnabled=false` MUST provide a hard Gateway V2 opt-out.
- **FR-008**: `thinClientEnabled=true` MUST provide an explicit Gateway V2
  opt-in.
- **FR-009**: Only case-insensitive `true` and `false` values are valid for
  `thinClientEnabled`; all other values MUST fail before native client
  construction.
- **FR-010**: Existing non-empty Azure SDK system-property or environment
  settings MUST take precedence over the connection property.
- **FR-011**: Stale `connectionMode` and `gatewayHttp2Enabled` keys MUST fail
  before network I/O with actionable messages.
- **FR-012**: Documentation MUST state that thin-client selection is JVM-wide,
  not per-client.
- **FR-013**: The change MUST NOT add or alter provider-neutral API methods,
  capability declarations, data semantics, or error normalization.
- **FR-014**: Cosmos emulator and provider unit coverage MUST continue to pass
  under the fixed Gateway transport.

### Key Entities

- **FixedTransportPolicy**: The non-configurable Cosmos transport decision:
  Gateway mode with HTTP/2 enabled.
- **ThinClientPreference**: Tri-state Gateway V2 routing preference:
  `AUTO` (unset), `FORCE_ENABLED` (`true`), or `DISABLED` (`false`).
- **ThinClientConfigurationSource**: The effective source ordered by
  precedence: Azure SDK system property, Azure SDK environment variable, then
  Multicloud DB connection property.
- **RemovedTransportSetting**: A stale `connectionMode` or
  `gatewayHttp2Enabled` key that causes construction-time rejection.

## Success Criteria

### Measurable Outcomes

- **SC-001**: 100% of Cosmos client-construction paths select Gateway mode with
  HTTP/2 enabled.
- **SC-002**: 0 supported configuration paths can select Direct mode or disable
  HTTP/2.
- **SC-003**: With no thin-client override, 100% of clients leave routing to
  the provider SDK's probe-and-fallback behavior.
- **SC-004**: Explicit `true`, explicit `false`, malformed values, operator
  precedence, and removed settings each have automated construction-time
  coverage.
- **SC-005**: Active configuration examples contain no `connectionMode` or
  `gatewayHttp2Enabled` setting.
- **SC-006**: Provider unit tests and all three emulator conformance jobs pass.
