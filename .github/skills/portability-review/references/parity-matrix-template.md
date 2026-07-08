# Cross-provider parity matrix (template)

For any change that touches portable behaviour or a provider adapter,
the portability-parity-reviewer builds this matrix. **One row per
behaviour or contract surface that the change touches.**

| Behaviour | `multiclouddb-api` SPI | Cosmos | Dynamo | Spanner | Verdict | Evidence |
|---|---|---|---|---|---|---|
| *(illustrative — substitute real rows)* `<behaviour>` | `<ApiType>.<method>` returns/throws `<X>` | `<CosmosAdapter>.java:<line>` does `<X>` | `<DynamoAdapter>.java:<line>` does `<X>` | `<SpannerAdapter>.java:<line>` does `<Y>` → ❌ | **Divergent** | one-line summary, with citing line(s) |

> The row above is an **illustrative placeholder only**. Substitute real
> `file:line` citations from the PR under review. Do **not** copy the
> placeholder text into a real review. Every cell that names a file
> must point at a path that exists at the PR's HEAD.

**Verdict values:** `Equivalent` | `Partially Equivalent` | `Divergent`
| `Missing` (no implementation) | `Capability-gated` (intentionally
unsupported via `CapabilitySet`).

Every verdict cites **file + line** (or method) plus one line of
evidence. Don't stop at naming similarity — compare execution
semantics (when it triggers, what state changes, what outcome follows).

For each `Divergent` or `Missing` verdict, **classify impact**:

- **High** — Customer code that uses two providers will see different
  results, data loss, or incorrect retries.
- **Medium** — Subtle behavioural inconsistency; bug-prone for users
  exercising both providers in the same app.
- **Low** — Implementation detail unlikely to be observed by users.

`High` and `Medium` are 🔴 unless explicitly gated via `CapabilitySet`
+ `UNSUPPORTED_CAPABILITY` (in which case they're 💬). `Low` is 🟡.

## Cost-efficiency sub-matrix

For any portable read/write/query operation introduced or modified,
add a companion table tracking the cost driver on each provider. This
is separate from the behaviour parity matrix because a row can be
functionally `Equivalent` and still have wildly asymmetric cost.

| Operation | Cosmos cost driver | DynamoDB cost driver | Spanner cost driver | Concern |
|---|---|---|---|---|
| *(illustrative)* `query(no partitionKey, no expression)` | cross-partition scan → high RU per page | full-table `Scan` → high RCU, examines every item | full table scan → high CPU/IO | 🟡 unbounded on all three; consider `Capability-gated` |
| *(illustrative)* `query(partitionKey=X)` | partition-scoped query → bounded RU | `Query` on PK → bounded RCU | indexed read → bounded CPU/IO | OK — symmetric and bounded |

> Rows above are placeholders. Real rows must cite the operation as it
> appears in the diff and the actual cost driver on each provider, with
> file:line evidence.

When a row is asymmetric (cheap on two providers, expensive on the
third), promote the finding from 🟡 to 🔴 if the cost is **severe**
(order of magnitude worse) **and unbounded** (scales with data size or
request volume). Otherwise leave it 🟡 with a concrete recommendation:
name the cheaper path the provider offers (e.g., *"prefer `Query` over
`Scan` here"*) or recommend declaring the operation `Capability-gated`.

## Per-finding template

````
**<n>. <severity> · <category>: <one-line summary>**
- File: `<path/to/file>` (lines <start>–<end> if known)
- Quoted code/doc:
  ```<lang>
  <exact snippet from the diff or the file>
  ```
- Why it matters: <consequence, tied to portability or doc-alignment invariant>
- Suggested fix:
  ```<lang>
  <concrete patch text — exact CHANGELOG line, test stub, doc paragraph>
  ```
````

The outer fence is 4 backticks so the inner triple-backtick `<lang>`
blocks render correctly on github.com. (CommonMark §4.5: an outer
fence is only closed by a fence using *the same or more* of the same
character; mixing 4-backtick outer with 3-backtick inner is safe.)

**Categories:** `Provider Symmetry` · `Conformance` · `Spec Conformance`
· `Capability Declaration` · `Error Normalization` · `Cost Efficiency`
· `Doc Alignment` · `Changelog` · `E2E` · `Correctness`.

## Report skeleton

````
## Summary
<one paragraph: what the change does, overall assessment, headline findings>

## Cross-provider parity matrix
<the matrix above>

## Cost-efficiency sub-matrix
<the cost sub-matrix above, when relevant>

## Findings

### 🔴 Blocking
<each finding using the per-finding template>

### 🟡 Recommendations
<each finding using the per-finding template>

### 🟢 Suggestions
<each finding using the per-finding template>

### 💬 Observations
<each finding using the per-finding template>

## Confirmed OK
- <one line per checklist item that passed, with citation>
````
