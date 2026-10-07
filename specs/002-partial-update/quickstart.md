# Quickstart: Portable Partial Update

These examples execute `update()` only after the selected provider advertises
`Capability.PARTIAL_UPDATE`. Cosmos DB and DynamoDB advertise it in this release;
Spanner callers skip these examples because the shared client rejects the operation
before provider I/O.

## Update selected fields

```java
ResourceAddress orders = new ResourceAddress("orders-db", "orders");
MulticloudDbKey key = MulticloudDbKey.of("cust-42", "order-7");

client.upsert(orders, key, Map.of(
    "status", "NEW",
    "owner", "ana",
    "region", "westus"));

if (client.capabilities().isSupported(Capability.PARTIAL_UPDATE)) {
    client.update(orders, key, Map.of("status", "SHIPPED"));
}
```

On a provider advertising `PARTIAL_UPDATE`, `status` is then `SHIPPED` while
`owner` and `region` remain unchanged.
`update()` never creates a missing document.

## Null, map, and list values

Use a mutable map for Java null:

```java
Map<String, Object> fields = new LinkedHashMap<>();
fields.put("closedAt", null);
fields.put("profile", Map.of("name", "Bob"));
fields.put("tags", List.of("priority"));

if (client.capabilities().isSupported(Capability.PARTIAL_UPDATE)) {
    client.update(orders, key, fields);
}
```

The merge is shallow: `profile` and `tags` replace their complete top-level
values.

### Spanner release boundary

Spanner partial update is deliberately unsupported in this feature release.
Targeted provider changes enforce baseline complete-write and portable-result
contracts only, while `CapabilitySet` defaults the omitted core Feature 002
capability to unsupported. After shared validation, a valid call returns
non-retryable `UNSUPPORTED_CAPABILITY` with
`capability=partial_update` before any Spanner provider I/O. The Spanner emulator
ran shared gate coverage and the provider-direct legacy regression; this is not
live production Spanner validation and does not advertise portable update
support.

## Literal names

Shared validation does not trim accepted names. Cosmos escapes `/` and `~` as
one RFC 6901 segment; Dynamo aliases every name:

```java
Map<String, Object> literal = new LinkedHashMap<>();
literal.put("/", "slash");
literal.put("~", "tilde");
literal.put(" customer ", "spaces preserved");
literal.put("foo", "lower-case field");
literal.put("Foo", "separate upper-case field");
if (client.capabilities().isSupported(Capability.PARTIAL_UPDATE)) {
    client.update(orders, key, literal);
}
```

Case-distinct non-reserved names are separate literal fields, including in the
same atomic request.

## Invalid requests

On a provider advertising `PARTIAL_UPDATE`, these fail with non-retryable
`INVALID_REQUEST` before provider I/O:

```java
if (client.capabilities().isSupported(Capability.PARTIAL_UPDATE)) {
    client.update(orders, key, Map.of());

    client.update(
        orders,
        key,
        Map.of("status", "SHIPPED"),
        OperationOptions.builder().ttlSeconds(3600).build());
}
```

The names `id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, and `data`
(matched case-insensitively), underscore-prefixed names, blank names, names
above 50,000 UTF-8 bytes, binary values, cyclic graphs, and non-collection iterables also fail. The shared serialized limit is
390 KiB. Independently,
each replacement value may contain at most 31 nested map/list containers (its
top-level container is level 1), and the incoming map's structural footprint may
not exceed 390 KiB. Every nested map key uses the same name bound. The footprint includes UTF-8 field
names and native map/list overhead, so compact JSON containing many empty containers can still be rejected.

These shared failures use `partial_update_field_name_size_limit`,
`non_portable_binary_value`, `partial_update_nesting_depth_limit`, or
`partial_update_structural_footprint_limit` in `providerDetails.reason`, together
with actual/maximum limit details, and perform zero provider I/O. They inspect
only incoming fields; final item size still depends on stored state.

Shared preflight snapshots the top-level map and performs one bounded SDK-owned
Jackson serialization. Its detached normalized result is the exact provider
input. Caller-registered modules are not consulted, so values requiring custom
modules must first be converted to serializable values. Binary values hidden in
a POJO are rejected during that process. POJO cycles, excessive POJO depth,
serializer re-entry, non-collection iterables, and over-limit output become
typed shared validation failures.

Complete `create()`/`upsert()` writes use the same validation and limits.
A null complete document, a top-level name matching `id`, `partitionKey`,
`sortKey`, `ttl`, `ttlExpiry`, or `data` in any letter case, or an
underscore-prefixed top-level name returns non-retryable `INVALID_REQUEST`
before provider I/O. Remaining top-level names must be unique ignoring case and
contain at most 128 Unicode characters; nested names retain the 50,000-byte
UTF-8 limit.

## Capabilities

All callers must check `Capability.PARTIAL_UPDATE` before invoking `update()`.
Cosmos DB and DynamoDB currently advertise it; Spanner does not. The default
client gate is a typed safety failure path, not a substitute for caller gating.

`PARTIAL_UPDATE` covers results whose serialized JSON and portable structural
footprint are each at most 390 KiB. A state-dependent result above either bound
is outside this release's portable contract and may succeed or fail under native
provider limits.

TTL timing is outside the portable partial-update contract. DynamoDB
`UpdateItem` happens to leave `ttlExpiry` unchanged, while Cosmos DB
`patchItem` advances `_ts` and restarts relative TTL. Until behavior is
normalized, callers requiring fixed absolute expiry must not call `update()` on
TTL-bearing items.

The SDK performs no read/merge preflight because result size depends on stored state.
Complete `create()`/`upsert()` documents use the same binary-value, name, depth,
and structural envelope. Native size failures remain non-retryable
`UNSUPPORTED_CAPABILITY` errors with
stable `providerDetails.reason` and limit values.

The enforcement values are internal rather than compile-time Java constants.
Applications should handle typed `INVALID_REQUEST` limit details rather than
copying values for client-side prevalidation. Runtime discovery and customer
configuration are deferred to
[#116](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/116).

Case-distinct field identity is part of the base `PARTIAL_UPDATE` contract.
Names such as `status` and `STATUS` remain separate fields across calls and may
also appear together in one atomic request.

## Provider-envelope errors

```java
if (client.capabilities().isSupported(Capability.PARTIAL_UPDATE)) {
    try {
        client.update(orders, key, veryWideFields);
    } catch (MulticloudDbException ex) {
        if (ex.error().category()
                == MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY) {
            String reason = ex.error().providerDetails().get("reason");
            // cosmos_result_item_size_limit
            // dynamodb_result_item_size_limit
        }
    }
}
```

Maps above 10 fields fail shared preflight with `INVALID_REQUEST`. A Cosmos HTTP
413 after one attempted patch includes `maximumResultBytes`. DynamoDB
includes `maximumResultBytes` when it rejects the one attempted
`UpdateItem` because the existing item plus fields would be too large. These
result-item paths do not add a read and are returned after the failed atomic
native update.

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

Setting TTL through a complete write is different from TTL timing during
partial update. The latter is outside this release's portable contract. Until
behavior is normalized, callers requiring an existing absolute expiry to remain
fixed must not call `update()` on TTL-bearing items.

`upsert()` creates a missing document. It is not an atomic replacement guarded
by existence, read-then-upsert is not atomic, and this release has no exact
portable atomic full-document replace-if-present equivalent.

## Focused unit validation

```powershell
mvn -pl multiclouddb-api -am -Punit `
  '-Dtest=PartialUpdateValidatorTest,DefaultMulticloudDbClientPartialUpdateTest,DocumentSizeValidatorTest,MulticloudDbClientPartialUpdateContractTest,CapabilityTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

mvn -pl multiclouddb-provider-cosmos -am -Punit `
  '-Dtest=CosmosPartialUpdatePlannerTest,CosmosPartialUpdateTest,CosmosErrorMappingTest,CosmosDiagnosticsLogTest,CosmosConsistencyTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

mvn -pl multiclouddb-provider-dynamo -am -Punit `
  '-Dtest=DynamoPartialUpdatePlannerTest,DynamoPartialUpdateTest,DynamoItemMapperTest,DynamoErrorMappingTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Shared conformance and provider-neutral E2E use the existing Spanner schema.
The current local validation ran DynamoDB Local and the Spanner emulator. The
Cosmos emulator is unavailable, so the final post-remediation Cosmos rerun and
T061 remain pending. The blocker-remediation rerun adds no schema fixture or E2E
schema helper.
