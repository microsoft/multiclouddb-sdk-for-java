# Partial Update Contracts

This feature changes a Java SDK method and does not expose an HTTP or GraphQL
endpoint. An OpenAPI document would therefore invent a transport that the
repository does not provide.

- [partial-update-contract.md](partial-update-contract.md) is the normative Java
  API behavior, capability gates, Cosmos/Dynamo mechanics, and unchanged
  Spanner release boundary.
- [provider-limit-details.schema.json](provider-limit-details.schema.json)
  defines the structured, string-valued `providerDetails` carried by
  provider-envelope errors tied to `partial_update_extended_payload`. Local
  Cosmos/Dynamo request-envelope rejections perform zero provider I/O. Cosmos
  DB's state-dependent result-item rejection follows one attempted patch or
  batch, and DynamoDB's follows one attempted `UpdateItem`. The extension
  describes native request/result envelopes for mappings already supported by
  a participating provider. The schema intentionally does not describe the
  simpler core-gate detail
  `{ "capability": "partial_update" }`.

The binding algorithm remains in [../design.md](../design.md). These contracts
summarize its caller-visible surface and must not be used to weaken that design.
