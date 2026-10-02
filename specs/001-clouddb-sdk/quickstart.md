# Quickstart: Multicloud DB SDK (Java)

This quickstart describes intended usage of the SDK’s portable contract in Java.

## Goal
Run the same base create/read/upsert/delete/query code against Cosmos DB, DynamoDB,
or Spanner by changing configuration only. Capability-gated operations such as
partial update must be checked separately.

## Install (planned)

Maven (example coordinates; final artifact names may differ):

```xml
<dependency>
	<groupId>com.microsoft.multiclouddb</groupId>
	<artifactId>multiclouddb-bundle</artifactId>
	<version>0.1.0</version>
</dependency>
```

Gradle:

```gradle
dependencies {
	implementation "com.microsoft.multiclouddb:multiclouddb-bundle:0.1.0"
}
```

## Minimal portable usage (planned)

1. Load configuration (provider selection + connection/auth details).
2. Construct `MulticloudDbClient` from configuration.
3. Use portable point operations (`create`, `read`, `upsert`, `delete`) and query.
4. Use `update` only when `Capability.PARTIAL_UPDATE` is supported.

### Example (illustrative)

```java
MulticloudDbClient client = MulticloudDbClientFactory.create(config);

ResourceAddress resource = new ResourceAddress("db", "collection");
MulticloudDbKey key = MulticloudDbKey.of("partitionKey-123", "sortKey-456");
Map<String, Object> doc = Map.of("customerId", "123", "name", "Ada");

client.upsert(resource, key, doc);
DocumentResult got = client.read(resource, key);  // returns null if not found
if (got != null) {
    ObjectNode payload = got.document();
}
if (client.capabilities().isSupported(Capability.PARTIAL_UPDATE)) {
    client.update(resource, key, Map.of("status", "active"));
}
// Portable query expression — works on all providers without changes
QueryPage page1 = client.query(resource, QueryRequest.builder()
	.expression("status = @status AND STARTS_WITH(name, @prefix)")
	.parameter("status", "active")
	.parameter("prefix", "A")
	.maxPageSize(50)
	.build());
client.delete(resource, key);  // Cleanup after all reads and queries

```

## Portable Query Expressions (planned)

The SDK provides a portable query expression language using a SQL-subset WHERE clause syntax. The same expression produces equivalent results on Cosmos DB, DynamoDB, and Spanner.

### Basic filtering

```java
// Simple equality with named parameter
QueryRequest simple = QueryRequest.builder()
	.expression("status = @status")
	.parameter("status", "active")
	.build();

// Multiple conditions with AND/OR
QueryRequest complex = QueryRequest.builder()
	.expression("(status = @s1 OR status = @s2) AND priority > @minP")
	.parameter("s1", "active")
	.parameter("s2", "pending")
	.parameter("minP", 3)
	.build();

// Using portable functions
QueryRequest withFunctions = QueryRequest.builder()
	.expression("starts_with(name, @prefix) AND contains(description, @keyword)")
	.parameter("prefix", "cloud")
	.parameter("keyword", "database")
	.build();
```

### Supported operators and functions

**Operators**: `=`, `<>`, `<`, `>`, `<=`, `>=`, `AND`, `OR`, `NOT`, `IN (...)`, `BETWEEN ... AND ...`

**Portable functions** (available on all providers):
- `starts_with(field, value)` — string prefix match
- `contains(field, value)` — substring match
- `field_exists(field)` — check if field is present and non-null
- `string_length(field)` — character length of a string
- `collection_size(field)` — number of elements in an array/list

### IN and BETWEEN

```java
// IN operator
QueryRequest inQuery = QueryRequest.builder()
	.expression("status IN (@s1, @s2, @s3)")
	.parameter("s1", "active")
	.parameter("s2", "pending")
	.parameter("s3", "review")
	.build();

// BETWEEN operator
QueryRequest betweenQuery = QueryRequest.builder()
	.expression("priority BETWEEN @low AND @high")
	.parameter("low", 1)
	.parameter("high", 5)
	.build();
```

### Full scan (no filter)

```java
// Null/empty expression returns all items
QueryRequest fullScan = QueryRequest.builder()
	.maxPageSize(100)
	.build();
```

### Native expression mode (explicit non-portable query syntax)

When you need provider-specific features, use `nativeExpression` instead of `expression`:

```java
// Cosmos DB-specific: LIKE operator (not portable to DynamoDB)
QueryRequest cosmosNative = QueryRequest.builder()
	.nativeExpression("SELECT * FROM c WHERE c.name LIKE '%database%'")
	.build();

// DynamoDB-specific: PartiQL with provider-native syntax
QueryRequest dynamoNative = QueryRequest.builder()
	.nativeExpression("SELECT * FROM \"MyTable\" WHERE begins_with(\"name\", 'cloud')")
	.build();
```

> **Warning**: Native expressions are not portable. Running a native expression on a different provider will produce an error.

### Checking query capabilities

```java
// Check if a capability-gated feature is available before using it
if (client.capabilities().isSupported(Capability.LIKE_OPERATOR)) {
	// Safe to use LIKE in a portable expression (Cosmos, Spanner)
}

if (client.capabilities().isSupported(Capability.ORDER_BY)) {
	// Safe to use ORDER BY
}
```

## Provider configuration

### Cosmos DB

Environment variables / system properties:

| Variable | System Property | Description |
|----------|----------------|-------------|
| `COSMOS_ENDPOINT` | `cosmos.endpoint` | Account endpoint URL |
| `COSMOS_KEY` | `cosmos.key` | Account key |
| `COSMOS_DATABASE` | `cosmos.database` | Database name (default: `clouddb`) |
| `COSMOS_CONTAINER` | `cosmos.container` | Container name (default: `items`) |

```java
MulticloudDbClientConfig cosmosConfig = MulticloudDbClientConfig.builder()
	.provider(ProviderId.COSMOS)
	.connection(Map.of(
		"endpoint", "https://myaccount.documents.azure.com:443/",
		"database", "mydb",
		"container", "items"))
	.auth(Map.of("key", System.getenv("COSMOS_KEY")))
	.build();
```

### DynamoDB

Environment variables / system properties:

| Variable | System Property | Description |
|----------|----------------|-------------|
| `DYNAMO_ENDPOINT` | `dynamo.endpoint` | Endpoint override (for local emulator) |
| `DYNAMO_REGION` | `dynamo.region` | AWS region (default: `us-east-1`) |
| `DYNAMO_TABLE` | `dynamo.table` | Table name (default: `items`) |
| `AWS_ACCESS_KEY_ID` | — | AWS access key |
| `AWS_SECRET_ACCESS_KEY` | — | AWS secret key |

```java
MulticloudDbClientConfig dynamoConfig = MulticloudDbClientConfig.builder()
	.provider(ProviderId.DYNAMO)
	.connection(Map.of(
		"region", "us-west-2",
		"table", "my-table"))
	.auth(Map.of())  // uses AWS default credential chain
	.build();
```

### Spanner

Environment variables / system properties:

| Variable | System Property | Description |
|----------|----------------|-------------|
| `SPANNER_PROJECT` | `spanner.project` | GCP project ID |
| `SPANNER_INSTANCE` | `spanner.instance` | Spanner instance name |
| `SPANNER_DATABASE` | `spanner.database` | Database name (default: `clouddb`) |
| `SPANNER_TABLE` | `spanner.table` | Table name (default: `items`) |
| `SPANNER_EMULATOR_HOST` | — | Emulator host (e.g., `localhost:9010`) |

```java
MulticloudDbClientConfig spannerConfig = MulticloudDbClientConfig.builder()
	.provider(ProviderId.SPANNER)
	.connection(Map.of(
		"project", "my-project",
		"instance", "my-instance",
		"database", "my-db",
		"table", "items"))
	.auth(Map.of())  // uses Google default credentials
	.build();
```

## Provider-specific opt-ins
Provider-specific features/behaviors may be enabled via explicit configuration toggles when possible.

- Callers MUST inspect `CapabilitySet` before using an optional capability; unsupported operations fail with structured `UNSUPPORTED_CAPABILITY` errors.
- Portable contract behavior remains the default if opt-ins are not enabled.

## Testing portability
- Run the shared conformance test suite against each provider configuration.
- Treat any documented exceptions as portability gaps that must be explicitly acknowledged.

---

## Result Set Control — Top N and ORDER BY

```java
// Get the 5 most recent positions for a portfolio (ORDER BY requires Cosmos or Spanner)
QueryRequest request = QueryRequest.builder()
    .expression("portfolioId = @pid")
    .parameters(Map.of("pid", "portfolio-alpha"))
    .partitionKey("portfolio-alpha")
    .orderBy("timestamp", SortDirection.DESC)
    .limit(5)
    .build();

QueryPage page = client.query(address, request, OperationOptions.defaults());
page.items().forEach(item -> System.out.println(item.toPrettyString()));
```

```java
// DynamoDB does not support ORDER BY — throws UNSUPPORTED_CAPABILITY
QueryRequest badRequest = QueryRequest.builder()
    .expression("status = @s")
    .parameters(Map.of("s", "active"))
    .orderBy("createdAt", SortDirection.ASC)  // capability-gated
    .build();
// On DynamoDB: throws MulticloudDbException(UNSUPPORTED_CAPABILITY)
```

```java
// Top N without ORDER BY works on all providers
QueryRequest limitOnly = QueryRequest.builder()
    .expression("status = @s")
    .parameters(Map.of("s", "active"))
    .limit(10)
    .build();
```

---

## Document TTL and Write Metadata

### Setting TTL on a document

```java
// Create a document that expires in 300 seconds (5 minutes)
OperationOptions optionsWithTtl = OperationOptions.builder()
    .ttlSeconds(300)
    .build();

client.create(address, key, document, optionsWithTtl);
// Providers without ROW_LEVEL_TTL (including Spanner) ignore ttlSeconds.
// Check the capability first when expiry is required.
```

### Reading document metadata

This snippet assumes a class-level SLF4J logger such as
`private static final Logger LOG = LoggerFactory.getLogger(YourApplication.class)`.

```java
OperationOptions optionsWithMeta = OperationOptions.builder()
    .includeMetadata(true)
    .build();

DocumentResult result = client.read(address, key, optionsWithMeta);
ObjectNode doc = result.document();
DocumentMetadata meta = result.metadata(); // requested: current providers return an envelope
if (meta != null) {
    if (meta.lastModified() != null) LOG.info("Last written: {}", meta.lastModified());
    if (meta.ttlExpiry() != null) LOG.info("Expires at: {}", meta.ttlExpiry());
    if (meta.version() != null) LOG.info("ETag: {}", meta.version());
}
```

### Without metadata (backward compatible)

```java
// Existing callers: pass OperationOptions.defaults() — no metadata overhead
DocumentResult result = client.read(address, key, OperationOptions.defaults());
ObjectNode doc = result.document();  // same as before
assert result.metadata() == null;     // metadata was not requested
```

---

## Uniform Write-Input Envelope

The SDK enforces separate **390 KiB serialized and structural bounds** across all
providers. Complete create/upsert documents and incoming update maps must fit both;
binary values, field names above 50,000 UTF-8 bytes, and nesting above 31
map/list containers below the document root are rejected before provider I/O.
Shared preflight snapshots the top-level map and uses bounded SDK-owned Jackson
serialization while inspecting nested values. Binary values hidden in POJOs are
also rejected. A null create/upsert document is
invalid. Complete writes also reject top-level `id`, `partitionKey`, `sortKey`,
`ttl`, `ttlExpiry`, and `data` case-insensitively, plus every
underscore-prefixed top-level name.

The enforcement values are internal rather than compile-time Java constants.
Handle typed `INVALID_REQUEST` limit details instead of duplicating numeric
literals. Runtime limit discovery and configuration are deferred to
[#116](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/116).

```java
// Inputs exceeding either portable bound are rejected before any I/O
Map<String, Object> largeDoc = buildStructurallyLargeDocument();
try {
    client.create(address, key, largeDoc, OperationOptions.defaults());
} catch (MulticloudDbException e) {
    if (e.error().category() == MulticloudDbErrorCategory.INVALID_REQUEST) {
        LOG.warn("Write input outside portable envelope: {}", e.error().message());
    }
}
```

---

## Capability Checking for New Features

Before using capability-gated features, check provider support:

```java
CapabilitySet caps = client.capabilities();

// Check ORDER BY support
if (caps.isSupported(Capability.ORDER_BY)) {
    // Safe to use orderBy() on QueryRequest
}

// Check row-level TTL support
if (caps.isSupported(Capability.ROW_LEVEL_TTL)) {
    // Safe to set ttlSeconds on OperationOptions
}

// Check RESULT_LIMIT support (all three providers support this)
if (caps.isSupported(Capability.RESULT_LIMIT)) {
    // Safe to set limit() on QueryRequest
}

// Check partial-update support before calling update().
if (caps.isSupported(Capability.PARTIAL_UPDATE)) {
    // Do not call update() on a TTL-bearing item when fixed absolute expiry is required.
}
```

`CapabilitySet` supplies an unsupported default only when a provider omits
`PARTIAL_UPDATE`. Each built-in provider exposes 18 effective capability rows:
Cosmos DB and DynamoDB explicitly declare 18, while Spanner declares 17 and
receives that one default. Unrelated omitted capability names are not
backfilled. Portable partial-update behavior applies only while both resulting
logical-document size measures remain at or below 390 KiB. TTL timing is outside
the contract; DynamoDB happens to leave `ttlExpiry` unchanged, while Cosmos DB
patch advances `_ts` and restarts relative TTL.
