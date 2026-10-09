# Changelog - multiclouddb-serializer-jackson

## [Unreleased]

### Added

- Optional `JacksonDocumentCodec` implements the API-owned `DocumentCodec` with
  `createDefault` / caller-configured `from(ObjectMapper)`, explicit encode/decode
  and common Class/TypeRef support.
- Direct serializer-token collection into immutable Document/DocumentValue,
  without JSON bytes or Map-target deserialization. Decode uses decoder-owned
  TokenBuffer tokens and the snapshot's typed reader, preserving configured
  coercion and custom deserializers.
- Safe phase/reason failures, independent mapper configuration snapshots,
  object-root/duplicate/output validation and sticky collector failures.
  byte[]/ByteBuffer and native binary/embedded serializer tokens are explicitly
  unsupported at root or nested positions; customer Base64 string mapping is
  supported without introducing a hidden binary model kind.
- Actual factory/client/provider native-mock coverage for custom naming/date/
  generic mapping and a separate handwritten non-Jackson codec. These are
  create/upsert/point-read E2E unit tests, not live persistence guarantees.

### Changed

- Replaces the unpublished JacksonObjectCodec Map/ObjectNode boundary with
  Document. TypeRef and DocumentCodecException now belong to the neutral API.
  Existing custom-reader coercion is not replaced with stricter numeric logic;
  model numeric construction follows the documented finite bounded domain.
- Decode tokens preserve Short rather than prematurely widening to Integer.
  Byte remains an Integer token because Jackson has no byte numeric token;
  retained model payload and configured typed-reader coercion are separate.
- API is now a dependency; the adapter is optional for customer code and not a
  provider dependency. No client registration, POJO overload or module discovery.

### Scope

- Unpublished `0.1.0-SNAPSHOT` against the coordinated development API; no release
  workflow changes. Query Map/Object contracts remain and no query/change-feed
  convenience decode is introduced. Update semantics are unchanged and outside
  the new customer workflow evidence.
- Existing native provider numeric/schema/key limitations remain. Document has
  only the six JSON-like value kinds and no binary kind. Full issue-116 completion and direct
  native provider mapping remain deferred.
- See [support, migration and source/test traceability](../docs/customer-object-mapping.md).
