# Changelog

All notable changes to the Multicloud DB SDK modules.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and all modules adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## multiclouddb-api

### [Unreleased]

**Added:**

- `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` — optional
  caller-supplied token appended to the SDK user-agent header sent by all
  provider clients.
- `MulticloudDbClientConfig.userAgentSuffix()` — accessor returning the
  configured suffix, or `null` if unset.
- `com.multiclouddb.spi.SdkUserAgent` — SPI helper that builds the canonical
  `multiclouddb-sdk-java/<version>` user-agent token.

**Validation:**

- `userAgentSuffix(String)` rejects values longer than 256 characters and
  non-printable US-ASCII, protecting against header injection.

### [0.1.0-beta.1] — 2026-04-03

Initial public beta of the portable API and SPI layer.

- `MulticloudDbClient` — synchronous, provider-agnostic interface for CRUD,
  query, and schema provisioning
- `MulticloudDbClientFactory` — discovers provider adapters via `ServiceLoader`
- `MulticloudDbClientConfig` — immutable builder-pattern configuration
- `QueryRequest` — portable expression or native expression passthrough with
  named parameters, pagination, partition key scoping, limit, and orderBy
- `Key` — portable `(partitionKey, sortKey)` identity
- `ResourceAddress` — `(database, collection)` targeting
- Portable expression parser, validator, and translator SPI
- `CapabilitySet` — runtime capability introspection
- `MulticloudDbException` — structured error model with portable categories
- `OperationDiagnostics` — latency, request charge, request ID
- `DocumentMetadata` — last modified, TTL expiry, version/ETag
- Document size enforcement (399 KB limit)

---

## multiclouddb-provider-cosmos

### [Unreleased]

**Added:**

- User-Agent header stamping with `multiclouddb-sdk-java/<version>` token
  and optional user-configured suffix.

### [0.1.0-beta.1] — 2026-04-03

Initial public beta of the Azure Cosmos DB provider.

- `CosmosProviderAdapter` — SPI entry point for Cosmos DB
- `CosmosProviderClient` — full implementation backed by Azure Cosmos DB Java SDK v4
- Master-key and Azure Identity (Entra ID) authentication
- Gateway and Direct connection modes
- Full CRUD with automatic field injection (`id`, `partitionKey`)
- Portable expression translation to Cosmos SQL
- Native SQL passthrough
- Cross-partition query support (capability-gated)
- Schema provisioning (database + container creation)

---

## multiclouddb-provider-dynamo

### [Unreleased]

**Added:**

- User-Agent suffix support via `SdkAdvancedClientOption.USER_AGENT_SUFFIX`.

### [0.1.0-beta.1] — 2026-04-03

Initial public beta of the Amazon DynamoDB provider.

- `DynamoProviderAdapter` — SPI entry point for DynamoDB
- `DynamoProviderClient` — full implementation backed by AWS SDK for Java 2.25.16
- AWS credential authentication (access key + secret key)
- Full CRUD with `attribute_not_exists` / `attribute_exists` guards
- Portable expression translation to PartiQL
- Native PartiQL passthrough
- Schema provisioning (table creation with ACTIVE-wait)

---

## multiclouddb-provider-spanner

### [Unreleased]

**Added:**

- User-Agent support via gax `FixedHeaderProvider`.

### [0.1.0-beta.1] — 2026-04-03

Initial public beta of the Google Cloud Spanner provider.

- `SpannerProviderAdapter` — SPI entry point for Spanner
- `SpannerProviderClient` — full implementation backed by Google Cloud Spanner 6.62.0
- GCP credential and emulator authentication
- Full CRUD with mutation-based writes
- Portable expression translation to GoogleSQL
- Native GoogleSQL passthrough
- Schema provisioning (DDL-based table creation)
