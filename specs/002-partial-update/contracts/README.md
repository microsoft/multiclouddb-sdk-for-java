# Partial Update Contracts

This feature changes a Java SDK method and does not expose an HTTP or GraphQL
endpoint. An OpenAPI document would therefore invent a transport that the
repository does not provide.

- [partial-update-contract.md](partial-update-contract.md) is the normative Java
  API behavior, capability gates, Cosmos/Dynamo mechanics, and explicit
  Spanner release boundary. It also records shared bounded write validation,
  same-request partial-update case identity, null complete-document and all provider-owned/
  underscore-prefixed top-level rejections, the 128-character case-insensitive
  complete-write namespace, the core Feature 002 capability, and the six
  internally enforced portable write limits.
- [provider-limit-details.schema.json](provider-limit-details.schema.json)
  defines the structured, string-valued `providerDetails` carried by native
  resulting-item errors. Cosmos DB returns its error after one attempted patch,
  and DynamoDB after one attempted `UpdateItem`. Stable reasons and limit values
  describe native rejection above the dual 390 KiB portable result bounds.
  Results above either bound are outside this release's portable contract and
  may otherwise succeed under native limits. The schema does not duplicate the
  core-gate detail `{ "capability": "partial_update" }` or
  shared `INVALID_REQUEST` details for field-name, binary values,
  nesting-depth, unsafe graphs, null/provider-owned top-level names, and
  complete-document/update structural preflight; those are normative in
  `partial-update-contract.md`.

The binding algorithm remains in [../design.md](../design.md). These contracts
summarize its caller-visible surface and must not be used to weaken that design.
