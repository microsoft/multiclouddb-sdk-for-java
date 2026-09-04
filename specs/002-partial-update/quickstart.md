# Quickstart: Portable Partial Update

## Update selected fields

```java
ResourceAddress orders = new ResourceAddress("orders-db", "orders");
MulticloudDbKey key = MulticloudDbKey.of("cust-42", "order-7");

client.upsert(orders, key, Map.of(
    "status", "NEW",
    "owner", "ana",
    "region", "westus"));

client.update(orders, key, Map.of("status", "SHIPPED"));
```

Afterward, `status` is `SHIPPED`; `owner` and `region` remain unchanged.
`update()` never creates a missing document.

## Null, map, and list values

Use a mutable map for Java null:

```java
Map<String, Object> fields = new LinkedHashMap<>();
fields.put("closedAt", null);
fields.put("profile", Map.of("name", "Bob"));
fields.put("tags", List.of("priority"));

client.update(orders, key, fields);
```

The merge is shallow: `profile` and `tags` replace their complete top-level
values.

### Spanner release boundary

Spanner is not part of this feature release. Its provider module remains
unchanged and does not advertise `PARTIAL_UPDATE`. After shared validation, a
valid call returns non-retryable `UNSUPPORTED_CAPABILITY` with
`capability=partial_update` before any Spanner provider I/O.
## Literal names

Shared validation does not trim accepted names. Cosmos escapes `/` and `~` as
one RFC 6901 segment; Dynamo aliases every name:

```java
Map<String, Object> literal = new LinkedHashMap<>();
literal.put("/", "slash");
literal.put("~", "tilde");
literal.put(" customer ", "spaces preserved");
client.update(orders, key, literal);
```


## Invalid requests

These fail with non-retryable `INVALID_REQUEST` before provider I/O:

```java
client.update(orders, key, Map.of());

client.update(
    orders,
    key,
    Map.of("status", "SHIPPED"),
    OperationOptions.builder().ttlSeconds(3600).build());
```

Reserved names, underscore-prefixed names, blank names, and case-insensitive
duplicates also fail. The exact shared serialized limit is 408,576 bytes.

## Capabilities

Callers using Cosmos DB or DynamoDB do not need to pre-check:

```java
client.update(orders, key, fields);
```

The default client internally gates `Capability.PARTIAL_UPDATE`.

`Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD` describes whether mappings already
supported by a provider can reach the shared size limit without a lower native
request or resulting-item envelope:

```java
boolean noLowerEnvelope = client.capabilities()
    .isSupported(Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD);
```

Cosmos and Dynamo report false. Spanner does not declare this extension because
it does not advertise the core operation.

Field-case identity is separately discoverable:

```java
boolean preservesCaseDistinctNames = client.capabilities()
    .isSupported(Capability.PARTIAL_UPDATE_CASE_SENSITIVE_FIELDS);
```

Cosmos and Dynamo report true. Spanner does not declare this extension because
it is outside the feature release.

## Provider-envelope errors

```java
try {
    client.update(orders, key, veryWideFields);
} catch (MulticloudDbException ex) {
    if (ex.error().category()
            == MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY) {
        String reason = ex.error().providerDetails().get("reason");
        // cosmos_transactional_batch_limit
        // cosmos_result_item_size_limit
        // dynamodb_update_expression_limit
        // dynamodb_result_item_size_limit
    }
}
```

Cosmos request-envelope errors include operation and serialized-byte counts. A
Cosmos HTTP 413 after one attempted patch/batch includes
`maximumResultBytes=2097152`. Dynamo limit errors include update-expression
bytes for local preflight, or
`maximumResultBytes=409600` when DynamoDB rejects the one attempted
`UpdateItem` because the existing item plus fields would be too large. The
local request/expression paths perform zero provider I/O; result-item paths do
not add a read and are returned after the failed native update.

## Migrate replacement and TTL-bearing updates

If existing Cosmos/Dynamo code used `update()` to remove omitted fields, move
to a complete upsert:

```java
client.upsert(orders, key, completeDesiredDocument);
```

To set TTL:

```java
client.upsert(
    orders,
    key,
    completeDesiredDocument,
    OperationOptions.builder().ttlSeconds(3600).build());
```

`upsert()` creates a missing document. It is not an atomic replacement guarded
by existence.

## Focused unit validation

```powershell
mvn -pl multiclouddb-api -am -Punit `
  '-Dtest=PartialUpdateValidatorTest,DefaultMulticloudDbClientPartialUpdateTest,DocumentSizeValidatorTest,MulticloudDbClientPartialUpdateContractTest,CapabilityTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

mvn -pl multiclouddb-provider-cosmos -am -Punit `
  '-Dtest=CosmosPartialUpdatePlannerTest,CosmosPartialUpdateTest,CosmosErrorMappingTest,CosmosDiagnosticsLogTest,CosmosConsistencyTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

mvn -pl multiclouddb-provider-dynamo -am -Punit `
  '-Dtest=DynamoPartialUpdatePlannerTest,DynamoPartialUpdateTest,DynamoItemMapperTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Shared conformance and provider-neutral E2E use the existing Spanner schema.
The blocker-remediation rerun adds no schema fixture or E2E schema helper.
