# Query & Filter Expression Research: Portable Query DSL Design

**Purpose**: Document the query/filter expression capabilities of Azure Cosmos DB, Amazon DynamoDB (including PartiQL), and Google Cloud Spanner to inform the design of a portable query DSL for the Hyperscale DB SDK.

**Date**: 2026-02-27  
**Status**: Research  
**Related**: spec.md (FR-002, FR-006), data-model.md (Query, QueryRequest), research.md (Decision 6)

---

## Table of Contents

1. [Azure Cosmos DB (SQL API)](#1-azure-cosmos-db-sql-api)
2. [Amazon DynamoDB](#2-amazon-dynamodb)
3. [Google Cloud Spanner](#3-google-cloud-spanner)
4. [DynamoDB PartiQL (SQL-Compatible Layer)](#4-dynamodb-partiql-sql-compatible-layer)
5. [Common Ground Analysis](#5-common-ground-analysis)
6. [Portable DSL Design Recommendation](#6-portable-dsl-design-recommendation)
7. [Equivalent Operation Examples](#7-equivalent-operation-examples)

---

## 1. Azure Cosmos DB (SQL API)

### 1.1 Overview

Cosmos DB SQL API provides a SQL-like query language that operates on JSON documents within a container. Queries are scoped to a single container (no cross-container JOINs). The `FROM` clause aliases the container, typically as `c`.

### 1.2 SELECT / FROM / WHERE Syntax

```sql
SELECT <select_list>
FROM <container_alias>
[WHERE <filter_condition>]
[ORDER BY <order_expression> [ASC|DESC]]
[OFFSET <offset_amount> LIMIT <limit_amount>]
```

**Key patterns:**
```sql
-- Select all fields
SELECT * FROM c

-- Select specific fields (projection)
SELECT c.id, c.name, c.status FROM c

-- Select with alias
SELECT c.name AS itemName FROM c

-- Nested property access
SELECT c.address.city FROM c

-- Value (unwrap single-value results)
SELECT VALUE c.name FROM c
```

### 1.3 Comparison Operators

| Operator | Syntax | Example |
|----------|--------|---------|
| Equal | `=` | `c.status = 'active'` |
| Not equal | `!=` or `<>` | `c.status != 'deleted'` |
| Less than | `<` | `c.age < 30` |
| Greater than | `>` | `c.age > 18` |
| Less than or equal | `<=` | `c.price <= 100.00` |
| Greater than or equal | `>=` | `c.priority >= 3` |
| IN | `IN` | `c.status IN ('active', 'pending')` |
| BETWEEN | `BETWEEN` | `c.age BETWEEN 18 AND 65` |
| LIKE | `LIKE` | `c.name LIKE 'Jo%'` |

**Notes on LIKE:**
- `%` matches zero or more characters
- `_` matches exactly one character
- Escape with `\`: `c.name LIKE 'test\_%' ESCAPE '\'`

### 1.4 Logical Operators

| Operator | Example |
|----------|---------|
| `AND` | `c.status = 'active' AND c.age > 18` |
| `OR` | `c.status = 'active' OR c.status = 'pending'` |
| `NOT` | `NOT c.isDeleted` |
| `NOT IN` | `c.status NOT IN ('deleted', 'archived')` |

Precedence: `NOT` > `AND` > `OR` (parentheses override)

### 1.5 Built-in Functions (Filtering-Relevant)

**String functions:**

| Function | Syntax | Description |
|----------|--------|-------------|
| `CONTAINS` | `CONTAINS(c.name, 'ohn')` | Substring match (case-sensitive by default) |
| `CONTAINS` (case-insensitive) | `CONTAINS(c.name, 'ohn', true)` | Third arg = ignore case |
| `STARTSWITH` | `STARTSWITH(c.name, 'Jo')` | Prefix match |
| `ENDSWITH` | `ENDSWITH(c.name, 'son')` | Suffix match |
| `STRINGEQUALS` | `STRINGEQUALS(c.name, 'john', true)` | Case-insensitive equality |
| `LENGTH` | `LENGTH(c.name) > 3` | String length |
| `LOWER` | `LOWER(c.name) = 'john'` | Lowercase conversion |
| `UPPER` | `UPPER(c.name) = 'JOHN'` | Uppercase conversion |
| `LTRIM` / `RTRIM` / `TRIM` | `TRIM(c.name)` | Whitespace trimming |
| `SUBSTRING` | `SUBSTRING(c.name, 0, 3)` | Extract substring |
| `CONCAT` | `CONCAT(c.first, ' ', c.last)` | Concatenation |
| `INDEX_OF` | `INDEX_OF(c.name, 'a')` | Find position |
| `REPLACE` | `REPLACE(c.name, 'old', 'new')` | String replacement |
| `RegexMatch` | `RegexMatch(c.name, '^Jo.*')` | Regular expression match |

**Type-checking functions:**

| Function | Description |
|----------|-------------|
| `IS_DEFINED(c.prop)` | Property exists |
| `IS_NULL(c.prop)` | Property is null |
| `IS_STRING(c.prop)` | Type check |
| `IS_NUMBER(c.prop)` | Type check |
| `IS_BOOL(c.prop)` | Type check |
| `IS_ARRAY(c.prop)` | Type check |
| `IS_OBJECT(c.prop)` | Type check |

**Math functions:**

| Function | Description |
|----------|-------------|
| `ABS(c.value)` | Absolute value |
| `CEILING(c.value)` | Round up |
| `FLOOR(c.value)` | Round down |
| `ROUND(c.value)` | Round |
| `POWER(c.value, n)` | Exponent |

**Array functions (usable in WHERE):**

| Function | Description |
|----------|-------------|
| `ARRAY_CONTAINS(c.tags, 'urgent')` | Check if array contains value |
| `ARRAY_LENGTH(c.tags) > 0` | Array count |

### 1.6 Parameterized Queries

Yes, fully supported. Parameters use the `@` prefix:

```sql
SELECT * FROM c WHERE c.status = @status AND c.age > @minAge
```

**Java SDK usage:**
```java
SqlQuerySpec query = new SqlQuerySpec(
    "SELECT * FROM c WHERE c.status = @status AND c.age > @minAge",
    List.of(
        new SqlParameter("@status", "active"),
        new SqlParameter("@minAge", 18)
    )
);
```

Parameters are strongly recommended for security (prevent injection) and performance (query plan caching).

### 1.7 Limitations

- **Single container scope**: No JOINs across containers. The `JOIN` keyword is for intra-document (self-join on arrays/sub-objects within a document).
- **Partition key awareness**: Cross-partition queries are allowed but more expensive (fan-out). Single-partition queries are preferred for performance.
- **No aggregate without GROUP BY in filters**: Aggregates (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`) exist but are for SELECT projections, not in WHERE clauses.
- **No subqueries in WHERE** (limited correlated subquery support via `EXISTS` + `JOIN` on sub-arrays).
- **ORDER BY** requires a composite index if the sort field is not the partition key or `id`.
- **OFFSET/LIMIT** are supported for paging but continuation tokens are more efficient.

### 1.8 Current SDK Implementation

The existing `CosmosProviderClient.query()` passes the expression string directly through to `SqlQuerySpec`, and maps `@`-prefixed parameters to `SqlParameter` objects. This means Cosmos can accept any valid Cosmos SQL expression natively.

---

## 2. Amazon DynamoDB

### 2.1 Overview

DynamoDB has two native query mechanisms:
1. **Query operation**: Requires a `KeyConditionExpression` on the partition key (and optional sort key conditions). Efficient; uses indexes.
2. **Scan operation**: Reads the entire table/index and optionally applies a `FilterExpression` to discard items. Expensive; full table read.

Both use an expression language with **placeholders** for attribute names and values.

### 2.2 Filter Expression Syntax

DynamoDB expressions use a custom (non-SQL) syntax:

```
attribute_name comparison_operator operand
```

**Example:**
```
#status = :statusVal AND age > :minAge
```

### 2.3 Comparison Operators

| Operator | Syntax | Example |
|----------|--------|---------|
| Equal | `=` | `#status = :val` |
| Not equal | `<>` | `#status <> :val` |
| Less than | `<` | `age < :val` |
| Greater than | `>` | `age > :val` |
| Less than or equal | `<=` | `age <= :val` |
| Greater than or equal | `>=` | `age >= :val` |
| BETWEEN | `BETWEEN ... AND` | `age BETWEEN :lo AND :hi` |
| IN | `IN` | `#status IN (:v1, :v2, :v3)` |

**Important**: DynamoDB uses `<>` for "not equal" (not `!=`).

### 2.4 Logical Operators

| Operator | Example |
|----------|---------|
| `AND` | `#status = :s AND age > :a` |
| `OR` | `#status = :s1 OR #status = :s2` |
| `NOT` | `NOT contains(#name, :sub)` |

Parentheses are supported for grouping.

### 2.5 Functions

| Function | Syntax | Description |
|----------|--------|-------------|
| `begins_with` | `begins_with(#name, :prefix)` | String prefix match |
| `contains` | `contains(#name, :substr)` | Substring/set membership |
| `attribute_exists` | `attribute_exists(#prop)` | Attribute is present |
| `attribute_not_exists` | `attribute_not_exists(#prop)` | Attribute is absent |
| `attribute_type` | `attribute_type(#prop, :type)` | Type check (S, N, B, SS, NS, BS, L, M, BOOL, NULL) |
| `size` | `size(#list) > :val` | Returns size of string, set, list, or map |

**Notable absences**: No `ends_with`, no `LIKE`/wildcards, no regex, no `LOWER`/`UPPER` (case-insensitive matching not supported), no string concatenation, no `SUBSTRING`.

### 2.6 Expression Attribute Names and Values

DynamoDB expressions use two kinds of placeholders:

**Expression Attribute Names** (`#name` placeholders):
- Required when the attribute name is a DynamoDB reserved word (e.g., `status`, `name`, `type`, `data`)
- Also required when attribute name contains `.` or starts with a number
- Maps `#name` → actual attribute name

```java
Map<String, String> expressionNames = Map.of(
    "#status", "status",
    "#name", "name"
);
```

**Expression Attribute Values** (`:value` placeholders):
- Required for all literal values in expressions
- Maps `:valueName` → `AttributeValue`

```java
Map<String, AttributeValue> expressionValues = Map.of(
    ":statusVal", AttributeValue.fromS("active"),
    ":minAge", AttributeValue.fromN("18")
);
```

### 2.7 KeyConditionExpression vs FilterExpression

| Aspect | KeyConditionExpression | FilterExpression |
|--------|----------------------|------------------|
| **Used with** | `Query` operation only | Both `Query` and `Scan` |
| **Purpose** | Identifies which items to read from the index | Post-read filter; items are read then discarded |
| **Performance** | Efficient (reads only matching items from index) | Items consumed first (charged RCU), then filtered |
| **Required** | Partition key equality is required; sort key conditions optional | Entirely optional |
| **Sort key operators** | `=`, `<`, `>`, `<=`, `>=`, `BETWEEN`, `begins_with` | All comparison operators + all functions |
| **Partition key operators** | `=` only | N/A |

**KeyConditionExpression example:**
```
pk = :pkVal AND begins_with(sk, :skPrefix)
```

**FilterExpression example (applied after KeyCondition):**
```
#status = :active AND age > :minAge
```

### 2.8 Query vs Scan with Filters

**Query** (partition-targeted, efficient):
```java
QueryRequest.builder()
    .tableName("Users")
    .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
    .filterExpression("#status = :active")
    .expressionAttributeNames(Map.of("#status", "status"))
    .expressionAttributeValues(Map.of(
        ":pk", AttributeValue.fromS("USER#123"),
        ":prefix", AttributeValue.fromS("ORDER#"),
        ":active", AttributeValue.fromS("active")
    ))
    .build();
```

**Scan** (full table, expensive):
```java
ScanRequest.builder()
    .tableName("Users")
    .filterExpression("#status = :active AND age > :minAge")
    .expressionAttributeNames(Map.of("#status", "status"))
    .expressionAttributeValues(Map.of(
        ":active", AttributeValue.fromS("active"),
        ":minAge", AttributeValue.fromN("18")
    ))
    .build();
```

**Key implication for portable DSL**: Without knowing the table's key schema, any portable filter expression maps to a **Scan + FilterExpression** on DynamoDB — which is expensive. Partition-targeted queries require knowledge of the key schema that goes beyond the expression itself.

### 2.9 Current SDK Implementation

The existing `DynamoProviderClient.query()` currently:
- Treats a non-empty expression (other than `SELECT * FROM c`) as a DynamoDB `FilterExpression` on a Scan
- Converts parameters by prefixing `:` to create `expressionAttributeValues`
- Does **not** use `expressionAttributeNames` (potential issue with reserved words)
- Does **not** support `KeyConditionExpression` (always does a Scan)
- Does **not** support PartiQL

---

## 3. Google Cloud Spanner

### 3.1 Overview

Spanner supports full SQL queries via two dialects:
- **GoogleSQL** (default): Google's SQL dialect with some extensions
- **PostgreSQL dialect**: Available on Spanner databases created with the PostgreSQL interface

For portability, **GoogleSQL** is the primary target since it's the default and most widely used Spanner dialect.

### 3.2 SELECT / FROM / WHERE Syntax

```sql
SELECT <select_list>
FROM <table_name> [AS alias]
[WHERE <filter_condition>]
[ORDER BY <order_expression> [ASC|DESC]]
[LIMIT <count>]
[OFFSET <count>]
```

**Key patterns:**
```sql
-- Select all
SELECT * FROM Users

-- Select specific columns
SELECT id, name, status FROM Users

-- With alias
SELECT u.name AS user_name FROM Users AS u

-- Nested struct field access (GoogleSQL)
SELECT address.city FROM Users
```

### 3.3 Comparison Operators

| Operator | Syntax | Example |
|----------|--------|---------|
| Equal | `=` | `status = 'active'` |
| Not equal | `!=` or `<>` | `status != 'deleted'` |
| Less than | `<` | `age < 30` |
| Greater than | `>` | `age > 18` |
| Less than or equal | `<=` | `price <= 100.00` |
| Greater than or equal | `>=` | `priority >= 3` |
| IN | `IN` | `status IN ('active', 'pending')` |
| BETWEEN | `BETWEEN` | `age BETWEEN 18 AND 65` |
| LIKE | `LIKE` | `name LIKE 'Jo%'` |
| IS NULL | `IS NULL` | `email IS NULL` |
| IS NOT NULL | `IS NOT NULL` | `email IS NOT NULL` |

### 3.4 Logical Operators

| Operator | Example |
|----------|---------|
| `AND` | `status = 'active' AND age > 18` |
| `OR` | `status = 'active' OR status = 'pending'` |
| `NOT` | `NOT is_deleted` |

Precedence: `NOT` > `AND` > `OR` (parentheses override). Same as ANSI SQL.

### 3.5 String Functions

| Function | Syntax | Description |
|----------|--------|-------------|
| `STARTS_WITH` | `STARTS_WITH(name, 'Jo')` | Prefix match (GoogleSQL-specific) |
| `ENDS_WITH` | `ENDS_WITH(name, 'son')` | Suffix match (GoogleSQL-specific) |
| `CONTAINS` | Not a built-in function | **Not directly available** — use `LIKE '%substr%'` or `STRPOS` |
| `STRPOS` | `STRPOS(name, 'ohn') > 0` | Find position (0 = not found); usable as contains equivalent |
| `LIKE` | `name LIKE '%ohn%'` | Pattern matching with `%` and `_` wildcards |
| `LENGTH` | `LENGTH(name) > 3` | String length |
| `LOWER` | `LOWER(name) = 'john'` | Lowercase |
| `UPPER` | `UPPER(name) = 'JOHN'` | Uppercase |
| `TRIM` / `LTRIM` / `RTRIM` | `TRIM(name)` | Whitespace trimming |
| `SUBSTR` | `SUBSTR(name, 1, 3)` | Substring extraction (1-indexed) |
| `CONCAT` | `CONCAT(first, ' ', last)` | String concatenation |
| `REPLACE` | `REPLACE(name, 'old', 'new')` | String replacement |
| `REGEXP_CONTAINS` | `REGEXP_CONTAINS(name, r'^Jo.*')` | Regular expression match (GoogleSQL) |
| `REGEXP_EXTRACT` | `REGEXP_EXTRACT(name, r'^(Jo)')` | Regex extraction |

**Note**: GoogleSQL's `STARTS_WITH` and `ENDS_WITH` are functions (not operators). Spanner's PostgreSQL dialect uses standard `LIKE` patterns instead.

### 3.6 Type-Checking and Null Handling

```sql
-- NULL checks
WHERE email IS NULL
WHERE email IS NOT NULL

-- COALESCE
WHERE COALESCE(status, 'unknown') = 'active'

-- IFNULL (GoogleSQL)
WHERE IFNULL(status, 'unknown') = 'active'

-- CAST
WHERE CAST(age AS STRING) = '30'
```

Spanner is strongly typed (schema-defined columns), so type-checking functions like Cosmos's `IS_DEFINED` or DynamoDB's `attribute_exists` are not needed — columns always exist (may be NULL if nullable).

### 3.7 Parameterized Queries

Yes, fully supported. Parameters use the `@` prefix in GoogleSQL:

```sql
SELECT * FROM Users WHERE status = @status AND age > @minAge
```

**Java SDK usage:**
```java
Statement statement = Statement.newBuilder(
        "SELECT * FROM Users WHERE status = @status AND age > @minAge")
    .bind("status").to("active")
    .bind("minAge").to(18)
    .build();

try (ResultSet resultSet = dbClient.singleUse().executeQuery(statement)) {
    while (resultSet.next()) {
        // process rows
    }
}
```

### 3.8 Key Limitations for Single-Table Queries

- **Schema-bound**: Unlike Cosmos DB and DynamoDB (schemaless documents), Spanner requires a predefined schema. Queries reference strongly-typed columns.
- **No document-style nested property queries**: While Spanner supports JSON columns (as of recent versions), querying into JSON requires `JSON_VALUE()` or `JSON_QUERY()` functions — not dot-notation like Cosmos.
- **No `ARRAY_CONTAINS` equivalent in WHERE**: Array columns exist but require `UNNEST` + subquery or `EXISTS` for membership checks.
- **Interleaved tables**: Spanner's parent-child table interleaving is efficient but doesn't directly map to document-model sub-documents.
- **No continuation tokens for queries**: Paging must be done via `LIMIT`/`OFFSET` or keyset pagination patterns. The SDK does not expose a resume token for application-level use.

### 3.9 Current SDK Implementation

The Spanner provider is a stub (`SpannerProviderClient`) that throws `UnsupportedOperationException` for all operations. When implemented, the natural approach is to pass the SQL expression directly to `executeQuery(Statement)`, similar to the Cosmos provider.

---

## 4. DynamoDB PartiQL (SQL-Compatible Layer)

### 4.1 Overview

DynamoDB supports **PartiQL**, an SQL-compatible query language developed by Amazon, as an alternative to the native expression-based API. PartiQL support was added in November 2020 and is available in the standard AWS SDK.

### 4.2 SELECT / WHERE Syntax

```sql
-- Select all items (full table scan)
SELECT * FROM "Users"

-- Select with filter
SELECT * FROM "Users" WHERE status = 'active'

-- Select specific attributes
SELECT id, name, status FROM "Users" WHERE age > 18

-- Table names are quoted with double quotes
SELECT * FROM "MyTable" WHERE "pk" = 'value123'
```

**Important**: Table names and reserved-word column names MUST be double-quoted.

### 4.3 Comparison Operators

| Operator | Syntax | Example |
|----------|--------|---------|
| Equal | `=` | `status = 'active'` |
| Not equal | `<>` or `!=` | `status <> 'deleted'` |
| Less than | `<` | `age < 30` |
| Greater than | `>` | `age > 18` |
| Less than or equal | `<=` | `price <= 100.00` |
| Greater than or equal | `>=` | `priority >= 3` |
| IN | `IN` | `status IN ('active', 'pending')` |
| BETWEEN | `BETWEEN` | `age BETWEEN 18 AND 65` |
| IS MISSING | `IS MISSING` | `email IS MISSING` (attribute does not exist) |
| IS NOT MISSING | `IS NOT MISSING` | `email IS NOT MISSING` |

**Notable**: PartiQL does NOT support `LIKE` on DynamoDB.

### 4.4 Logical Operators

| Operator | Example |
|----------|---------|
| `AND` | `status = 'active' AND age > 18` |
| `OR` | `status = 'active' OR status = 'pending'` |
| `NOT` | `NOT is_deleted` |

### 4.5 Functions

| Function | Syntax | Description |
|----------|--------|-------------|
| `begins_with` | `begins_with(name, 'Jo')` | Prefix match |
| `contains` | `contains(name, 'ohn')` | Substring / set membership |
| `attribute_type` | `attribute_type(prop, 'S')` | Type check |
| `size` | `size(tags) > 0` | Size of string/list/map/set |
| `EXISTS` | `EXISTS(SELECT * FROM ...)` | Subquery existence check (limited) |
| `MISSING` | `field IS MISSING` | Check for absent attributes |

**Notable absences vs native DynamoDB expressions**: `attribute_exists` and `attribute_not_exists` are replaced by `IS MISSING` / `IS NOT MISSING` syntax.

### 4.6 Parameterized Queries

Yes. The AWS SDK supports parameterized PartiQL via `ExecuteStatementRequest`:

```java
ExecuteStatementRequest request = ExecuteStatementRequest.builder()
    .statement("SELECT * FROM \"Users\" WHERE status = ? AND age > ?")
    .parameters(
        AttributeValue.fromS("active"),
        AttributeValue.fromN("18")
    )
    .build();

ExecuteStatementResponse response = dynamoClient.executeStatement(request);
```

Parameters use **positional** `?` placeholders (not named parameters like `@param` or `:param`).

### 4.7 PartiQL vs Native DynamoDB API

| Aspect | PartiQL | Native Query/Scan API |
|--------|---------|----------------------|
| **Syntax** | SQL-like | Expression strings with placeholders |
| **Partition-targeted queries** | Yes — if WHERE clause includes full partition key equality, DynamoDB will optimize to a Query internally | Explicit `KeyConditionExpression` on Query operation |
| **Filter performance** | Same as Scan if no key conditions; DynamoDB optimizes WHERE on keys automatically | Explicit distinction between key conditions and filter |
| **Parameterization** | Positional `?` | Named `:value` placeholders |
| **Reserved word handling** | Double-quote column names | `#name` expression attribute names |
| **Batch queries** | `BatchExecuteStatement` (up to 25 statements) | `BatchGetItem` (up to 100 keys) |
| **Transactions** | `ExecuteTransaction` (up to 100 statements) | `TransactGetItems` / `TransactWriteItems` |
| **LIKE support** | **No** | **No** |
| **Projection** | `SELECT col1, col2 FROM ...` | `ProjectionExpression` |

### 4.8 AWS SDK v2 for Java — PartiQL API Calls

| Operation | API Method | Class |
|-----------|-----------|-------|
| Single statement | `executeStatement` | `DynamoDbClient.executeStatement(ExecuteStatementRequest)` |
| Batch (up to 25) | `batchExecuteStatement` | `DynamoDbClient.batchExecuteStatement(BatchExecuteStatementRequest)` |
| Transaction (up to 100) | `executeTransaction` | `DynamoDbClient.executeTransaction(ExecuteTransactionRequest)` |

**Java example:**
```java
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

// Single PartiQL query
ExecuteStatementRequest request = ExecuteStatementRequest.builder()
    .statement("SELECT * FROM \"TodoItems\" WHERE status = ? AND priority >= ?")
    .parameters(
        AttributeValue.fromS("active"),
        AttributeValue.fromN("3")
    )
    .limit(50)
    .build();

ExecuteStatementResponse response = dynamoClient.executeStatement(request);
List<Map<String, AttributeValue>> items = response.items();

// Pagination with NextToken
String nextToken = response.nextToken();
if (nextToken != null) {
    ExecuteStatementRequest nextPage = request.toBuilder()
        .nextToken(nextToken)
        .build();
    // ...
}
```

**Key observation**: PartiQL's `ExecuteStatementResponse.nextToken()` provides a native continuation token, which is simpler to integrate than manual `LastEvaluatedKey` serialization used by native Scan/Query.

### 4.9 Limitations of PartiQL on DynamoDB

1. **No LIKE operator**: Cannot do wildcard/pattern matching
2. **No ORDER BY**: Results are returned in DynamoDB's native order (key order for Query; undefined for Scan)
3. **No JOIN**: Single-table only (consistent with DynamoDB's model)
4. **No aggregates**: No `COUNT`, `SUM`, `AVG`, `MIN`, `MAX` in SELECT
5. **No GROUP BY**: Not supported
6. **No OFFSET**: Paging uses `NextToken` only
7. **No HAVING**: Not supported
8. **No subqueries in WHERE**: Limited to `EXISTS` for certain patterns
9. **Positional parameters only**: No named parameters (unlike Cosmos `@param` and Spanner `@param`)
10. **Performance**: A `SELECT * FROM "Table" WHERE non_key_attr = ?` is still a full table scan internally

---

## 5. Common Ground Analysis

### 5.1 Operator Support Matrix

| Operator | Cosmos DB SQL | DynamoDB Native | DynamoDB PartiQL | Spanner GoogleSQL | **All Three?** |
|----------|:---:|:---:|:---:|:---:|:---:|
| `=` | ✅ | ✅ | ✅ | ✅ | **✅ Universal** |
| `!=` / `<>` | ✅ (`!=`,`<>`) | ✅ (`<>`) | ✅ (`<>`,`!=`) | ✅ (`!=`,`<>`) | **✅ Universal** (use `<>` for max compat) |
| `<` | ✅ | ✅ | ✅ | ✅ | **✅ Universal** |
| `>` | ✅ | ✅ | ✅ | ✅ | **✅ Universal** |
| `<=` | ✅ | ✅ | ✅ | ✅ | **✅ Universal** |
| `>=` | ✅ | ✅ | ✅ | ✅ | **✅ Universal** |
| `AND` | ✅ | ✅ | ✅ | ✅ | **✅ Universal** |
| `OR` | ✅ | ✅ | ✅ | ✅ | **✅ Universal** |
| `NOT` | ✅ | ✅ | ✅ | ✅ | **✅ Universal** |
| `IN` | ✅ | ✅ | ✅ | ✅ | **✅ Universal** |
| `BETWEEN` | ✅ | ✅ | ✅ | ✅ | **✅ Universal** |
| `LIKE` | ✅ | ❌ | ❌ | ✅ | ❌ (DynamoDB lacks) |
| `IS NULL` | ✅ | N/A (schemaless) | ✅ | ✅ | ⚠️ Partial |
| `ORDER BY` | ✅ | ❌ (key order) | ❌ | ✅ | ❌ (DynamoDB lacks) |
| `LIMIT` | ✅ | ✅ (`Limit`) | ✅ (`Limit`) | ✅ | **✅ Universal** (by API param) |
| `OFFSET` | ✅ | ❌ | ❌ | ✅ | ❌ (DynamoDB lacks) |

### 5.2 Function Support Matrix

| Function | Cosmos DB SQL | DynamoDB Native | DynamoDB PartiQL | Spanner GoogleSQL | **All Three?** |
|----------|:---:|:---:|:---:|:---:|:---:|
| Prefix match | `STARTSWITH(c.f, v)` | `begins_with(f, v)` | `begins_with(f, v)` | `STARTS_WITH(f, v)` | **✅** (different names) |
| Substring match | `CONTAINS(c.f, v)` | `contains(f, v)` | `contains(f, v)` | `STRPOS(f, v) > 0` or `LIKE` | **✅** (different names/syntax) |
| Suffix match | `ENDSWITH(c.f, v)` | ❌ | ❌ | `ENDS_WITH(f, v)` | ❌ (DynamoDB lacks) |
| String length | `LENGTH(c.f)` | `size(f)` | `size(f)` | `LENGTH(f)` | **✅** (different names) |
| Lowercase | `LOWER(c.f)` | ❌ | ❌ | `LOWER(f)` | ❌ (DynamoDB lacks) |
| Uppercase | `UPPER(c.f)` | ❌ | ❌ | `UPPER(f)` | ❌ (DynamoDB lacks) |
| Attribute exists | `IS_DEFINED(c.f)` | `attribute_exists(f)` | `f IS NOT MISSING` | `f IS NOT NULL` | **✅** (different syntax) |
| Attribute absent | `NOT IS_DEFINED(c.f)` | `attribute_not_exists(f)` | `f IS MISSING` | `f IS NULL` | ⚠️ Semantic gap: NULL vs absent |
| Regex match | `RegexMatch(c.f, p)` | ❌ | ❌ | `REGEXP_CONTAINS(f, p)` | ❌ (DynamoDB lacks) |
| Type checking | `IS_STRING`, `IS_NUMBER`, etc. | `attribute_type(f, t)` | `attribute_type(f, t)` | Not needed (schema-typed) | ⚠️ Semantic gap |
| Size (collection) | `ARRAY_LENGTH(c.f)` | `size(f)` | `size(f)` | Requires `ARRAY_LENGTH(f)` | **✅** (different names) |

### 5.3 SQL Syntax Compatibility

| Syntax Element | Cosmos DB SQL | DynamoDB PartiQL | Spanner GoogleSQL | **Portable?** |
|----------------|:---:|:---:|:---:|:---:|
| `SELECT * FROM t` | ✅ (`FROM c`) | ✅ (`FROM "Table"`) | ✅ (`FROM Table`) | **✅** (table alias differs) |
| `SELECT col FROM t` | ✅ (`c.col`) | ✅ (`col`) | ✅ (`col`) | ⚠️ Cosmos requires alias prefix |
| `WHERE cond` | ✅ | ✅ | ✅ | **✅** |
| Named params (`@p`) | ✅ | ❌ (positional `?`) | ✅ | ❌ (DynamoDB lacks) |
| Positional params (`?`) | ❌ | ✅ | ❌ | ❌ (only DynamoDB) |

### 5.4 Fundamental Incompatibilities

1. **Parameterization syntax**: Cosmos DB and Spanner use named `@param` parameters. DynamoDB PartiQL uses positional `?` parameters. DynamoDB native uses `:value` placeholders. **Resolution**: The portable DSL should use named parameters internally and translate to the target syntax.

2. **Property/column reference syntax**: Cosmos SQL requires container alias prefix (`c.fieldName`). Spanner SQL uses bare column names. DynamoDB native uses `#name` placeholders for reserved words. **Resolution**: The portable DSL should use bare field names and the adapter adds the necessary prefix/placeholder.

3. **Document vs. relational model**: Cosmos DB and DynamoDB are schemaless (documents); Spanner is schema-defined (relational). Functions like `IS_DEFINED` (Cosmos), `attribute_exists` (Dynamo) have no Spanner equivalent because columns always exist. **Resolution**: Map `attribute_exists` to `IS NOT NULL` on Spanner (semantic approximation).

4. **Nested property access**: Cosmos uses `c.address.city`; DynamoDB uses `address.city`; Spanner requires JSON functions or struct access. **Resolution**: Support single-level properties in the portable DSL; nested access is provider-specific/capability-gated.

5. **Table quoting**: DynamoDB PartiQL requires double-quoted table names; Spanner and Cosmos generally don't. **Resolution**: Adapter handles quoting.

6. **Array/list membership**: Cosmos has `ARRAY_CONTAINS(c.tags, val)`; DynamoDB has `contains(tags, val)` (overloaded for strings and sets); Spanner requires `val IN UNNEST(tags)`. **Resolution**: Provide a portable `array_contains(field, value)` function and translate per provider.

7. **No ORDER BY in DynamoDB**: DynamoDB (native or PartiQL) does not support arbitrary ordering. Items are returned in partition+sort key order from Query or undefined order from Scan. **Resolution**: `ORDER BY` is a capability-gated feature, not part of the portable minimum.

8. **No LIKE in DynamoDB**: Neither native nor PartiQL support LIKE/wildcard patterns. **Resolution**: `LIKE` is capability-gated. Portable alternatives: `starts_with` (universal) and `contains` (universal).

### 5.5 Viable Portable Subset

**The following operators and functions form a safe portable query subset:**

**Operators (universal):**
- Comparison: `=`, `<>`, `<`, `>`, `<=`, `>=`
- Logical: `AND`, `OR`, `NOT`
- Range: `IN (...)`, `BETWEEN ... AND ...`

**Functions (universal, with name translation):**

| Portable Name | Cosmos DB | DynamoDB (native) | DynamoDB (PartiQL) | Spanner |
|---------------|-----------|-------|---------|---------|
| `starts_with(field, value)` | `STARTSWITH(c.field, value)` | `begins_with(field, value)` | `begins_with(field, value)` | `STARTS_WITH(field, value)` |
| `contains(field, value)` | `CONTAINS(c.field, value)` | `contains(field, value)` | `contains(field, value)` | `STRPOS(field, value) > 0` |
| `field_exists(field)` | `IS_DEFINED(c.field)` | `attribute_exists(field)` | `field IS NOT MISSING` | `field IS NOT NULL` |
| `string_length(field)` | `LENGTH(c.field)` | `size(field)` | `size(field)` | `LENGTH(field)` |
| `collection_size(field)` | `ARRAY_LENGTH(c.field)` | `size(field)` | `size(field)` | `ARRAY_LENGTH(field)` |

**Capability-gated features (not in portable minimum):**

| Feature | Supported By |
|---------|-------------|
| `LIKE` / wildcard patterns | Cosmos, Spanner |
| `ends_with` | Cosmos, Spanner |
| `LOWER` / `UPPER` | Cosmos, Spanner |
| `ORDER BY` | Cosmos, Spanner |
| `OFFSET` paging | Cosmos, Spanner |
| Regex matching | Cosmos, Spanner |
| Array membership (`ARRAY_CONTAINS`) | All (different syntax) |

---

## 6. Portable DSL Design Recommendation

### 6.1 Approach: SQL-Subset with Named Parameters

The portable query expression language should be a **minimal SQL subset** with the following properties:

1. **SQL-like WHERE clause syntax** using the universal operator set
2. **Named parameters** with `@` prefix (natural for Cosmos and Spanner; translated to `?` for DynamoDB PartiQL or `:val` for DynamoDB native)
3. **Bare field names** (no container alias; adapter adds `c.` for Cosmos, `#name` mapping for DynamoDB, or bare name for Spanner)
4. **Portable function names** that each adapter translates to the native equivalent
5. **`SELECT * FROM <implicit_collection>`** as the default; projections are optional

### 6.2 Proposed Grammar (Informal)

```
portable_query ::= [WHERE] condition

condition ::= comparison
            | condition AND condition
            | condition OR condition
            | NOT condition
            | '(' condition ')'

comparison ::= field_ref operator value
             | field_ref IN '(' value_list ')'
             | field_ref BETWEEN value AND value
             | function_call

operator ::= '=' | '<>' | '<' | '>' | '<=' | '>='

value ::= parameter      -- @paramName
        | literal_string  -- 'text'
        | literal_number  -- 42, 3.14
        | literal_bool    -- true, false
        | NULL

field_ref ::= identifier ['.' identifier]*  -- nested property access

function_call ::= starts_with '(' field_ref ',' value ')'
                | contains '(' field_ref ',' value ')'
                | field_exists '(' field_ref ')'
                | string_length '(' field_ref ')'
                | collection_size '(' field_ref ')'
```

### 6.3 Translation Strategy

Each adapter translates the portable expression to the native form:

| Target | Translation Steps |
|--------|-------------------|
| **Cosmos DB** | Wrap in `SELECT * FROM c WHERE ...`; prefix field refs with `c.`; map function names; pass `@param` through |
| **DynamoDB (PartiQL)** | Wrap in `SELECT * FROM "tableName" WHERE ...`; map function names; replace `@param` with `?` and build positional parameter list |
| **DynamoDB (Native)** | Parse to expression tree; emit `FilterExpression` with `#name`/`:value` placeholders; build `ExpressionAttributeNames` and `ExpressionAttributeValues` maps |
| **Spanner** | Wrap in `SELECT * FROM tableName WHERE ...`; map function names; pass `@param` through |

### 6.4 Expression Representation in QueryRequest

Two modes should be supported:

```java
// Mode 1: Portable expression (translated by adapter)
QueryRequest.builder()
    .expression("status = @status AND starts_with(name, @prefix)")
    .parameters(Map.of("status", "active", "prefix", "Jo"))
    .pageSize(25)
    .build();

// Mode 2: Native expression (passed through to provider)
QueryRequest.builder()
    .nativeExpression("SELECT c.id, c.name FROM c WHERE CONTAINS(c.name, @name, true)")
    .parameters(Map.of("name", "joh"))
    .pageSize(25)
    .build();
```

### 6.5 DynamoDB Backend Strategy: PartiQL vs Native

**Recommendation**: Use **PartiQL** as the primary DynamoDB backend for the portable query DSL:

| Factor | PartiQL | Native API |
|--------|---------|-----------|
| SQL-like syntax match | ✅ Closer to Cosmos/Spanner | ❌ Completely different syntax |
| Continuation token | ✅ Native `NextToken` string | ❌ Requires custom `LastEvaluatedKey` serialization |
| Parameter handling | Positional `?` (simple translation) | Named `:val` + `#name` (complex mapping) |
| Performance | Same as native (DynamoDB optimizes internally) | Same as PartiQL |
| SDK support | `executeStatement` in standard SDK | `query`/`scan` in standard SDK |
| Sort key optimization | ✅ Automatic when WHERE includes key equality | ✅ Explicit `KeyConditionExpression` |

**When to fall back to native**: The adapter may optionally use native `Query` with `KeyConditionExpression` when it can detect that the portable expression targets the partition key — this is a performance optimization, not a correctness requirement.

---

## 7. Equivalent Operation Examples

### 7.1 Simple Equality Filter

**Portable DSL:**
```
status = @status
```
**Parameters:** `{ "status": "active" }`

| Provider | Translated Expression |
|----------|----------------------|
| **Cosmos DB** | `SELECT * FROM c WHERE c.status = @status` with `SqlParameter("@status", "active")` |
| **DynamoDB PartiQL** | `SELECT * FROM "TodoItems" WHERE status = ?` with `[AttributeValue.fromS("active")]` |
| **DynamoDB Native** | FilterExpression: `#status = :status`, Names: `{"#status": "status"}`, Values: `{":status": {S: "active"}}` |
| **Spanner** | `SELECT * FROM TodoItems WHERE status = @status` with `.bind("status").to("active")` |

### 7.2 Multiple Conditions

**Portable DSL:**
```
status = @status AND priority >= @minPriority AND starts_with(title, @prefix)
```
**Parameters:** `{ "status": "active", "minPriority": 3, "prefix": "URGENT" }`

| Provider | Translated Expression |
|----------|----------------------|
| **Cosmos DB** | `SELECT * FROM c WHERE c.status = @status AND c.priority >= @minPriority AND STARTSWITH(c.title, @prefix)` |
| **DynamoDB PartiQL** | `SELECT * FROM "TodoItems" WHERE status = ? AND priority >= ? AND begins_with(title, ?)` |
| **DynamoDB Native** | FilterExpression: `#status = :status AND priority >= :minPriority AND begins_with(title, :prefix)` |
| **Spanner** | `SELECT * FROM TodoItems WHERE status = @status AND priority >= @minPriority AND STARTS_WITH(title, @prefix)` |

### 7.3 IN Operator

**Portable DSL:**
```
status IN (@s1, @s2, @s3)
```
**Parameters:** `{ "s1": "active", "s2": "pending", "s3": "review" }`

| Provider | Translated Expression |
|----------|----------------------|
| **Cosmos DB** | `SELECT * FROM c WHERE c.status IN (@s1, @s2, @s3)` |
| **DynamoDB PartiQL** | `SELECT * FROM "TodoItems" WHERE status IN (?, ?, ?)` |
| **DynamoDB Native** | FilterExpression: `#status IN (:s1, :s2, :s3)` |
| **Spanner** | `SELECT * FROM TodoItems WHERE status IN (@s1, @s2, @s3)` |

### 7.4 BETWEEN Operator

**Portable DSL:**
```
age BETWEEN @lo AND @hi
```
**Parameters:** `{ "lo": 18, "hi": 65 }`

| Provider | Translated Expression |
|----------|----------------------|
| **Cosmos DB** | `SELECT * FROM c WHERE c.age BETWEEN @lo AND @hi` |
| **DynamoDB PartiQL** | `SELECT * FROM "Users" WHERE age BETWEEN ? AND ?` |
| **DynamoDB Native** | FilterExpression: `age BETWEEN :lo AND :hi` |
| **Spanner** | `SELECT * FROM Users WHERE age BETWEEN @lo AND @hi` |

### 7.5 Contains (Substring Match)

**Portable DSL:**
```
contains(description, @term)
```
**Parameters:** `{ "term": "important" }`

| Provider | Translated Expression |
|----------|----------------------|
| **Cosmos DB** | `SELECT * FROM c WHERE CONTAINS(c.description, @term)` |
| **DynamoDB PartiQL** | `SELECT * FROM "Tasks" WHERE contains(description, ?)` |
| **DynamoDB Native** | FilterExpression: `contains(description, :term)` |
| **Spanner** | `SELECT * FROM Tasks WHERE STRPOS(description, @term) > 0` |

### 7.6 Field Exists Check

**Portable DSL:**
```
field_exists(email)
```
**Parameters:** `{}`

| Provider | Translated Expression |
|----------|----------------------|
| **Cosmos DB** | `SELECT * FROM c WHERE IS_DEFINED(c.email)` |
| **DynamoDB PartiQL** | `SELECT * FROM "Users" WHERE email IS NOT MISSING` |
| **DynamoDB Native** | FilterExpression: `attribute_exists(email)` |
| **Spanner** | `SELECT * FROM Users WHERE email IS NOT NULL` |

### 7.7 Complex Boolean Logic

**Portable DSL:**
```
(status = @active OR status = @pending) AND priority > @minP AND NOT contains(title, @excluded)
```
**Parameters:** `{ "active": "active", "pending": "pending", "minP": 2, "excluded": "TEST" }`

| Provider | Translated Expression |
|----------|----------------------|
| **Cosmos DB** | `SELECT * FROM c WHERE (c.status = @active OR c.status = @pending) AND c.priority > @minP AND NOT CONTAINS(c.title, @excluded)` |
| **DynamoDB PartiQL** | `SELECT * FROM "Tasks" WHERE (status = ? OR status = ?) AND priority > ? AND NOT contains(title, ?)` |
| **DynamoDB Native** | FilterExpression: `(#status = :active OR #status = :pending) AND priority > :minP AND NOT contains(title, :excluded)` |
| **Spanner** | `SELECT * FROM Tasks WHERE (status = @active OR status = @pending) AND priority > @minP AND NOT STRPOS(title, @excluded) > 0` |

### 7.8 Select All (No Filter)

**Portable DSL:**
```
(empty/null expression)
```

| Provider | Translated Expression |
|----------|----------------------|
| **Cosmos DB** | `SELECT * FROM c` |
| **DynamoDB PartiQL** | `SELECT * FROM "TableName"` |
| **DynamoDB Native** | Scan with no filter |
| **Spanner** | `SELECT * FROM TableName` |

---

## Appendix A: PartiQL as Unifying SQL Subset

PartiQL (developed by Amazon) is actually designed to be a SQL-compatible query language for semi-structured data. It has broader ambitions beyond DynamoDB — it's used in Amazon Redshift, S3 Select, and other AWS services. The fact that Cosmos DB, Spanner, and DynamoDB all support SQL-like queries (or in DynamoDB's case, PartiQL as a SQL layer) makes a SQL-subset approach viable.

However, the key insight is: **the intersection of what all three support is smaller than any individual query language**. The portable DSL should embrace this constraint by:

1. Supporting only the **universal subset** by default
2. Capability-gating features supported by a subset of providers
3. Allowing **native passthrough** for users who need full provider capabilities

## Appendix B: Impact on Current Implementation

The current `DynamoProviderClient` passes the expression directly as a DynamoDB `FilterExpression` on a Scan. This means the current API already requires provider-native syntax for DynamoDB filters, which defeats portability.

**Recommended changes:**
1. Add a portable expression parser/translator to `hyperscaledb-api` (or a new `hyperscaledb-query` module)
2. Each provider adapter implements a `translateExpression(portableExpr)` method
3. The Dynamo adapter should optionally support PartiQL via `executeStatement` as an alternative to Scan + FilterExpression
4. The `QueryRequest` should distinguish between `expression` (portable) and `nativeExpression` (passthrough)

## Appendix C: Performance Considerations

| Provider | Portable WHERE (no key targeting) | Key-Targeted Query |
|----------|-----------------------------------|-------------------|
| **Cosmos DB** | Cross-partition query (fan-out); RU cost proportional to data scanned | Single-partition with partition key in WHERE |
| **DynamoDB** | Full table Scan; RCU cost proportional to all data read | Partition key equality → Query operation (efficient) |
| **Spanner** | Full table scan unless secondary index matches | Primary key prefix or secondary index |

**Implication**: The portable DSL should document that queries without key conditions may be expensive. An optional `partitionHint` or `keyCondition` extension in `QueryRequest` could help adapters optimize for partition-targeted operations without breaking portability.
