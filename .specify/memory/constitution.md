# hyperscaledb Constitution

This constitution defines the minimum, non-negotiable requirements for `hyperscaledb`: a unifying client SDK that wraps provider SDKs for cloud native databases.

Initial providers: Azure Cosmos DB, AWS DynamoDB, Google Cloud Spanner.

## Core Principles

### 0) Portability-First Default
- The default usage of `hyperscaledb` MUST be centered on portability: the same application code should run across supported providers by changing configuration only.
- The provider-neutral API surface MUST represent a portable contract: features and behaviors that are intended to be consistent across supported providers.
- If consistent behavior cannot be achieved for a provider-neutral API, `hyperscaledb` MUST NOT silently diverge; it MUST either normalize to a consistent outcome or clearly flag the difference and require explicit user opt-in to accept non-portable behavior.

### 1) Thin Wrapper, Not a Re-implementation
- `hyperscaledb` MUST delegate all network I/O and auth signing to the underlying provider SDKs.
- `hyperscaledb` MUST provide an explicit “escape hatch” to access the native provider client for advanced features.
- `hyperscaledb` MUST NOT attempt to create a lowest-common-denominator database; it is a portability layer with capability discovery.

### 2) Capability-Based API (No False Promises)
- Any cross-provider feature MUST be guarded by explicit capability checks (e.g., “supports transactions”, “supports server-side paging tokens”).
- If a capability is unavailable, `hyperscaledb` MUST fail fast with a typed, actionable error (not silent no-ops).
- Provider-specific features MUST be accessible either via (a) capability-gated extensions or (b) the escape hatch.
- Provider-specific features and behaviors MUST NOT appear in the default provider-neutral surface unless they can be enabled in a way that preserves the portable contract.

### 3) Consistent Surface, Predictable Semantics
- Common operations MUST have consistent naming, inputs, outputs, and error categories across providers.
- Cross-provider options MUST be explicit and scoped (per request and per client) to avoid ambient global state.
- Any semantic mismatch MUST be documented as part of the provider adapter contract (e.g., mapping “container/table”, “partition key/primary key”).

### 3.1) Configuration-Only Portability (No Code Changes)
- For the portable contract, switching providers or deploying the same application to different environments MUST NOT require application code changes.
- Provider choice MUST be expressed via configuration only.
- Provider-specific configuration MUST be limited to connection/auth/environment details (e.g., endpoints, regions, account/project identifiers, credentials, and related settings required to establish connectivity).
- Provider-specific features and behaviors MUST require explicit opt-in (extensions/hooks/escape hatch) so portable-by-default usage stays seamless.
- When a provider-specific opt-in can be represented as a configuration toggle (without changing the shape of the portable contract), enabling/disabling it SHOULD be configuration-driven rather than requiring application code changes.
- If a provider-specific feature inherently requires provider-specific code (e.g., calling a provider-native API), that code MUST be isolated behind explicit opt-in boundaries (extensions/hooks/escape hatch) and MUST NOT affect the default portable behavior.

### 4) Explicit Reliability Controls
- All operations MUST support timeouts and cancellation.
- Retries MUST be configurable and MUST default to safe behavior:
	- Retry only idempotent operations by default.
	- Never retry non-idempotent operations unless explicitly enabled.
- `hyperscaledb` MUST expose throttling/backoff information when the provider supplies it.

### 5) Diagnostics by Default (Without Leaking Secrets)
- All requests MUST emit structured diagnostics hooks (e.g., operation name, duration, provider, status category).
- Errors MUST include a provider-neutral summary plus the original provider error/details (sanitized).
- `hyperscaledb` MUST NOT log secrets, raw auth headers, or user payloads by default.

### 5.1) Layered Diagnostics (Portable First, Provider Available)
- `hyperscaledb` MUST provide a portable diagnostics surface suitable for the common case (portable events/metrics/error categories).
- When the portable diagnostics surface is insufficient for debugging, users MUST be able to access provider-specific diagnostics.
- Enabling provider-specific diagnostics SHOULD be configuration-driven when possible (consistent with configuration-only portability), rather than requiring code changes.
- Provider-specific diagnostics access MUST NOT weaken security defaults (no secrets by default).

## Minimum Public API Contract

### Client & Provider Selection
- The SDK MUST expose a single entry client (e.g., `HyperscaleDbClient`) constructed with:
	- a provider identifier (Cosmos/Dynamo/Spanner)
	- provider-specific configuration (endpoint/region/project instance, etc.)
	- credentials/auth configuration delegated to provider SDK conventions
- The SDK MUST support multiple providers side-by-side in the same process.

Notes:
- Selecting a provider or environment MUST be achievable by configuration changes only (no required `if provider == ...` branches in application code for the portable contract).
- Provider-specific configuration is expected for connectivity/auth; portable application behavior must not depend on embedding provider conditionals in code.

### Resource Model (Portable, Minimal)
- The SDK MUST define a minimal logical addressing scheme:
	- `database` (or equivalent top-level namespace)
	- `collection` (container/table) as a string name
- Provider adapters MUST define and document how the provider maps onto these two identifiers.

### Data Model
- The SDK MUST support a JSON-like document payload as the common portable value.
- The SDK MUST allow passing and returning raw bytes/strings for provider-native query statements and identifiers.

### Operations (Minimum Set)
The portable surface MUST include:
- `get`: read one item/row by key
- `put`: create or replace an item/row by key
- `delete`: delete one item/row by key
- `query`: execute a read query with paging support

Notes:
- The SDK MUST NOT invent a cross-provider write-query or stored-procedure model.
- If a provider requires additional key components (partition key / composite primary key), the SDK MUST represent them explicitly in the “key” type.

### Paging
- `query` MUST support:
	- page size
	- continuation token (or equivalent)
	- returning a continuation token for the next page
- If a provider cannot supply a stable continuation token, the adapter MUST surface this as a capability limitation.

### Per-Request Options
- The SDK MUST support per-request options for:
	- timeout / cancellation
	- consistency/read mode when the provider supports it
	- conditional operations (e.g., ETag/version match) when the provider supports it

## Error Model (Minimum)
- The SDK MUST expose a provider-neutral error hierarchy with at least these categories:
	- `InvalidRequest`
	- `AuthenticationFailed`
	- `AuthorizationFailed`
	- `NotFound`
	- `Conflict`
	- `Throttled`
	- `TransientFailure`
	- `PermanentFailure`
	- `ProviderError` (fallback)
- Errors MUST carry:
	- provider id
	- operation name
	- retryable flag
	- optional provider status code / error code / request id when available

## Provider Adapter Requirements
- Each provider adapter MUST be an installable module/package that depends on the official provider SDK.
- Each provider adapter MUST:
	- implement the minimum operations
	- declare capabilities
	- translate provider errors into the shared error model
	- expose the native provider client via the escape hatch

## Testing (Minimum)
- `hyperscaledb` MUST include cross-provider contract tests for the minimum API contract.
- Each provider adapter MUST pass the same contract suite.
- Provider-specific behavior differences MUST be covered by explicit tests and documented.

## Versioning & Compatibility
- The SDK MUST use SemVer.
- Any breaking change to the minimum API contract or error model MUST be a major version bump.
- Each release MUST state the supported versions of provider SDKs.

## Governance
- This constitution supersedes all other local conventions for `hyperscaledb`.
- A PR that changes public APIs MUST:
	- update contract tests
	- update provider adapters (or explicitly gate the change behind capabilities)
	- update docs describing semantic differences
- Adding a new provider requires:
	- implementing the full adapter requirements
	- passing the shared contract suite
	- documenting configuration/auth mapping and capability matrix

**Version**: 0.1.0 | **Ratified**: 2026-01-23 | **Last Amended**: 2026-01-23
