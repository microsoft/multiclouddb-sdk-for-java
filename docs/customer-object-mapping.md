# Customer codecs and neutral documents

Applications own their serialization policy through the API's `DocumentCodec`.
The database client accepts an immutable `Document`, not an arbitrary POJO, and
does not discover, register or invoke codecs:

```text
DTO --explicit codec.encode--> Document --client.create/upsert--> provider
DTO <--explicit codec.decode-- Document <--client.read().document()-- provider
```

The API's public declarations and compile/runtime dependency closure are
Jackson-free, not dependency-free. The API uses SLF4J 2.0.12 and private Gson
2.11.0 cursor JSON handling. Gson adds `error_prone_annotations` 2.27.0 to the
Maven runtime closure (annotation metadata, not a second parser); its JPMS
requirement is static. The API plus these three dependency JARs is the tested
standalone module path.
Jackson is optional for application mapping: `multiclouddb-serializer-jackson`
implements the common contract with `JacksonDocumentCodec`. Native provider
implementations/SDKs still use Jackson and declare their dependencies explicitly.
The API's test-only Jackson dependency is an old-behavior comparison oracle,
not part of the runtime closure.

This is a **breaking development increment**, related to
[microsoft/multiclouddb-sdk-for-java#116](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/116),
not issue closure or a release. It replaces the earlier unreleased
Map/ObjectNode codec boundary. Query values remain JDK Maps/Objects; direct native
provider mapping, uniform query results, richer change-image contracts, complete
migration and benchmarks remain later work.

## Dependencies and migration

Build the coordinated reactor: API/Cosmos/Dynamo use
`0.1.0-beta.2-SNAPSHOT`, Spanner uses `0.1.0-beta.1-SNAPSHOT`, and the optional
adapter uses `0.1.0-SNAPSHOT`. These are unpublished development coordinates,
not new stable releases or claims of compatibility with the released beta API.
Release workflows are unchanged.

| Earlier boundary | Current boundary |
|---|---|
| `JacksonObjectCodec.encodeMap(...)` | `JacksonDocumentCodec.encode(...)`, implementing `DocumentCodec` |
| Adapter-local `TypeRef` / `ObjectCodecException` | `com.multiclouddb.api.codec.TypeRef` / `DocumentCodecException` |
| Client/SPI create/update/upsert Map argument | `Document`, with existing key/options/convenience forms |
| `DocumentResult.document(): ObjectNode` | `Document`; missing point read is still Java null |
| `ChangeEvent.data(): JsonNode` | Nullable `DocumentValue`; selected provider payload semantics retained |
| QueryPage Map items, query parameters and AST Object values | Unchanged; no new result classification or codec convenience overload |

A `DocumentCodec` has Class and neutral TypeRef encode/decode forms. `TypeRef`
captures direct parameterized subclasses, rejects unresolved variables and
raw/indirect capture, and exposes only JDK reflection information. Captured
wildcards/arrays do not promise that a codec supports them. `TypeRef.of(Class)`
cannot recover erased generics. There is no public `reflect.Type` overload.

JPMS applications using only the model/custom codec require
`com.multiclouddb.api`. The optional adapter module is
`com.multiclouddb.serializer.jackson`; reflection-based DTO packages must be
opened to `com.fasterxml.jackson.databind`.

## A codec with no Jackson imports

A customer can implement the common interface manually or with another library.
The executable [`PlainCustomerCodec` example](../multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/PlainCustomerCodec.java)
uses only JDK/API types. Its mapping creates `customer_name` and `quantity`,
and deliberately reads only those fields, leaving provider key/metadata policy
to the application:

```java
DocumentCodec codec = new PlainCustomerCodec();
PlainCustomerCodec.Customer customer = new PlainCustomerCodec.Customer("Grace", 7);
Document document = codec.encode(customer, PlainCustomerCodec.Customer.class);
client.create(address, key, document);
client.upsert(address, key, document);
DocumentResult result = client.read(address, key);
if (result == null) throw new IllegalStateException("Document not found.");
PlainCustomerCodec.Customer restored =
        codec.decode(result.document(), PlainCustomerCodec.Customer.class);
```

No ObjectNode conversion, Jackson import, client registration or byte-parse
utility is required in that customer codec. The actual client/provider-mock
tests run this workflow on Cosmos, Dynamo and Spanner.

## Optional Jackson: naming, custom dates and generic DTOs

```java
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.multiclouddb.api.*;
import com.multiclouddb.api.codec.*;
import com.multiclouddb.api.document.Document;
import com.multiclouddb.serializer.jackson.JacksonDocumentCodec;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

public class CustomerMappingExample {
    public record Customer(String displayName) {}
    // Explicit application policy, not installed silently by the adapter.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Batch<T>(String batchName, LocalDate createdOn, List<T> items) {}

    public static Batch<Customer> createAndRead(
            MulticloudDbClient client, ResourceAddress address, MulticloudDbKey key) {
        SimpleModule dates = new SimpleModule();
        dates.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override public void serialize(LocalDate date, JsonGenerator out,
                    SerializerProvider context) throws IOException {
                out.writeString(date.toString().replace("-", "/"));
            }
        });
        dates.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override public LocalDate deserialize(JsonParser in,
                    DeserializationContext context) throws IOException {
                return LocalDate.parse(in.getText().replace("/", "-"));
            }
        });
        ObjectMapper mapper = new ObjectMapper().registerModule(dates)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        DocumentCodec codec = JacksonDocumentCodec.from(mapper);
        TypeRef<Batch<Customer>> type = new TypeRef<>() {};
        Batch<Customer> batch = new Batch<>("customers", LocalDate.of(2026, 10, 6),
                List.of(new Customer("Ada")));
        Document document = codec.encode(batch, type);
        // batch_name, created_on="2026/10/06", items=[{display_name="Ada"}]
        client.create(address, key, document);
        DocumentResult result = client.read(address, key);
        if (result == null) throw new IllegalStateException("Document not found.");
        return codec.decode(result.document(), type);
    }
}
```

`createDefault()` captures a fresh stock mapper without module discovery or
enabling default typing. `from(mapper)` requires a compatible independent
`copy()` and factory; copy failure never falls back to the original/default
mapper. Later source-mapper configuration changes do not alter the snapshot.
Copy is **not a deep clone of customer collaborators**: shared serializers and
deserializers must be thread-safe and remain unmodified. Codecs are not closeable
and do not own collaborator/client lifecycles. They are not sandboxes for
untrusted classes, annotations or code.

## Immutable model and initial numeric semantics

`Document` has an `ObjectValue` root. The closed value algebra contains exactly
six JSON-like kinds: null, boolean, string, number, array and object. There is no
binary/embedded-object kind or alias. Containers copy ownership and
are recursively immutable. Objects retain encounter
order but equality ignores it; array equality is ordered.

`document.get(name)` returns `Optional.empty()` for absence and a present
`NullValue.INSTANCE` for explicit null. Java null children are invalid. The
builder and token collector reject duplicate names before overwrite, including
a previous null; duplicates already overwritten in a caller's Map are not
recoverable. There is no MissingValue or arbitrary embedded-object model kind.

| Number/model property | Initial contract |
|---|---|
| Retained representation | Whitelisted immutable Byte/Short/Integer/Long/BigInteger/BigDecimal/finite Float/Double; original kind and BigDecimal scale retained |
| Floating decimal view | Runtime Float.toString/Double.toString convention; `0.1d` has decimal view `0.1`, not exact binary expansion |
| Equality/hash | Mathematical decimal equality: 1, 1.0 and 1.00 agree; cached normalization does not rewrite the stored payload |
| Signed zero | Floating negative-zero payload retained; positive/negative zero compare/hash equally |
| Nonfinite/unknown Number | Explicit failure; no model-level rounding, stringification or null fallback |
| Numeric bounds | Precision <=1024 digits; stored scale [-1024,+1024], checked before normalization |
| Nesting | At most 128 object/array containers including the root |

There is no promise of globally shortest or identical floating text on every
JDK. Java 17 is the current baseline. These are in-memory semantics, **not DB
round-trip guarantees** or a complete heap/CPU budget. Outputs and decode tokens
are materialized; shared subtrees may be visited repeatedly. No public budget
configuration or customer-corpus prerequisite is introduced.

Configured Jackson reader coercion is preserved. For example, with Jackson's
float-to-int coercion enabled, a DTO integer target may receive `1` from a
Document containing `1.5`; disabling it causes decode failure. That does not
round the immutable Document. Custom deserializers remain responsible for their
own conversion choices; the SDK does not replace them with a strict numeric
parser or claim exact conversion to every target.

## Adapter support and errors

The encoder collects serializer tokens directly into the model. It does not
serialize JSON bytes, parse them back, or invoke a customer Map deserializer
merely to materialize an object. `writeObject`/`writeTree` still use the snapshot.
The decoder emits decoder-owned Jackson TokenBuffer tokens and invokes the
captured typed reader. Short and the other supported numeric kinds, decimal
scale and negative zero survive the decode token boundary. **Byte is promoted
to Integer**: Jackson has no byte numeric token, and its stock byte serializer
also emits an integer. This does not rewrite a Byte payload already in the
Document. A typed Byte target can still be reconstructed by the configured
reader; model representation, token representation and reader coercion are
separate contracts. Decoder-owned containers ensure that decoded DTO or JsonNode
mutation cannot mutate the Document.

Default byte[]/ByteBuffer serializers and native binary/embedded tokens fail
explicitly with `DocumentCodecException`/`UNSUPPORTED_OUTPUT`, including inside
Maps and lists. An application can deliberately emit Base64 **text** with its own
serializer; the result is an ordinary `StringValue`. The model/provider bridge
never detects or automatically decodes Base64. A customer-selected typed reader
or custom deserializer may interpret that string according to its own mapping
policy; this is not a hidden binary model kind.

| Boundary | Behavior |
|---|---|
| Object root and supported nested scalar/container tokens | Supported; only a completed immutable result escapes |
| Scalar/array/null/multiple/incomplete roots | Explicit failure |
| Observable duplicates or nesting failure | Collector remains failed even if a serializer swallows the exception or closes it |
| Raw JSON, embedded/opaque objects, binary encoder output, native IDs, Reader strings, textual number overloads | Explicitly unsupported; emit supported tokens intentionally |
| WRITE_NUMBERS_AS_STRINGS at numeric emission | Explicitly unsupported; an intentionally emitted string is a StringValue |
| Text formatting features | Do not redefine the structural representation |
| Opaque POJONode/custom-node cloning | Not an offered model/decode contract; arbitrary objects are not retained inside Document |

`DocumentCodecException` is separate from database `MulticloudDbException`.
Its fixed phase/reason diagnostics do not expose customer values, field names,
raw causes, suppressed exceptions or mapper exception messages. This does not
govern logs emitted by customer code or existing SDK/provider diagnostics.
Model violations use fixed-reason `IllegalArgumentException`; TypeRef errors
use IllegalArgumentException or NullPointerException for a null class.

## Existing provider mapping and runtime compatibility

Providers currently unwrap Document to owned plain Map/List/scalars and retain
their existing internal mapping passes. Reads structurally convert the existing
native tree without a new default-mapper reinterpretation. These paths incur
container traversal/allocation; they are not direct native model mapping.

An external or legacy database row/event can contain a binary/opaque node or be outside the model's finite,
precision/scale or nesting domain even if the SDK did not write it. Conversion
then fails as a nonretryable `MulticloudDbException` with `PROVIDER_ERROR`,
the provider ID, operation `read` or `readChanges`, and fixed detail
`reason=invalid_document_payload`. It is not automatically classified as an
invalid caller request. Payloads and native diagnostic causes are not included,
and values are not silently replaced with null or strings by this conversion.
The three small provider-local conversion helpers remain duplicated to keep
Jackson outside API runtime; actual read/feed regressions pin their shared
conversion/error behavior. Existing native-mapper fallbacks below are unchanged.

The common contract is at the **value-kind** level, not a promise that every
numeric payload or physical schema is portable. Documents require an object
root; arrays and scalar values occur as fields or nested values.

| Value kind | Cosmos existing JSON mapping | Dynamo existing attribute mapping | Spanner existing schema-bound mapping |
|---|---|---|---|
| Null | JSON null | NULL | Omitted value column with FIELD_DATA retaining the field; read as null |
| Boolean | JSON boolean | BOOL | BOOL column |
| String | JSON string | S | STRING column; existing nested-JSON marker escaping retained |
| Number | JSON number | N | INT64/FLOAT64 for supported primitive wrappers; top-level BigDecimal/BigInteger use STRING |
| Array | JSON array | L | JSON-marked STRING column |
| Object | JSON object | M | Root fields select columns; nested objects use JSON-marked STRING columns |

The native-mock create/upsert/read workflows exercise all six kinds, including
nulls and nested containers. Real services still require compatible key/column
schemas and have their own numeric and size limits; the tests do not establish
live persistence or exact precision for all NumberValue payloads.

| Provider | Retained behavior and limitation |
|---|---|
| Cosmos | Map-to-ObjectNode route; existing `/partitionKey` schema, id/partitionKey/TTL overwrite and system-field stripping |
| Dynamo | Map-to-tree-to-AttributeValue route; number text on write, dotted-number Double vs Int/Long read parsing. The old native mapper turns unsupported native B attributes into null before neutral conversion, including in feed images. Large integers, exponents and decimal precision are not repaired |
| Spanner | Existing INT64/FLOAT64 binding, JSON-marked nested STRINGs and FIELD_DATA selection. **Top-level BigDecimal/BigInteger still become STRINGs**; e.g. decimal `1.20` reads as StringValue `"1.20"`, not NumberValue. The old row mapper converts native BYTES to Base64 strings. Feed decoding restores only metadata-declared scalar INT64 strings to numbers; other string values remain strings. No schema change |

Spanner change-feed `column_types` metadata identifies scalar INT64 columns.
For the selected new/old image, retained fields of that type accept decimal
integer strings or integral JSON numbers within the signed 64-bit range, without
rounding. They become NumberValue through the existing neutral converter.
Null stays NullValue; absent fields are not invented. FIELD_DATA filtering
precedes normalization, and unselected images are not decoded.

Missing, null or empty `column_types` preserves legacy representation without
type inference; an undeclared field is also unchanged. Malformed supplied type
declarations and invalid/overflowing declared INT64 values fail with the safe
`PROVIDER_ERROR/readChanges/invalid_document_payload` error, not the legacy raw
fallback. Numeric-looking STRINGs, JSON, NUMERIC and BYTES are not reinterpreted.
Native ARRAY columns are unchanged: the current writer encodes Collection/Map
as JSON-marked STRING columns, not native ARRAY&lt;INT64&gt;. This correction does
not add native-array support or recursively coerce nested strings.

A BinaryNode that actually reaches any provider's neutral converter is rejected
with the safe error above, never converted to null/Base64 there. Cosmos read/feed
fixtures exercise this boundary directly; ordinary service JSON does not have a
binary token kind. Dynamo/Spanner's earlier legacy fallbacks are characterized
separately, not advertised as binary support in Document.

Existing field/key collisions and metadata visibility remain provider-specific;
no union reserved-name guard or global rewrite is added. Schema/index/TTL and
legacy data requirements still apply.

QueryPage Map items, query parameter/AST Objects and query token handling remain
unchanged. ChangeEvent's nullable neutral payload preserves the selected body:
it may be partial or a before-image, especially on deletion. Java null means no
supplied payload, distinct from NullValue/empty object. No Full/Partial/None or
BEFORE/AFTER public classification is inferred. There is no convenience query/
change-feed decode overload or implicit partial-image-to-DTO conversion.
Document supports existing update calls, but the new customer codec workflow
tests establish create/upsert/point-read only, not new update semantics.

The wrapper still validates logical JSON size (399 KiB) before provider calls.
A structural counter preserves the old default Map-to-tree byte profile,
including escaping and BigDecimal size normalization, without mutating the
number or allocating serialized document bytes. This is not native storage size.
Size-overflow diagnostics report the observed byte count as an **at-least lower
bound** because traversal stops when the limit is crossed, not the complete
document size. Binary is not a model value and cannot reach this size counter.
Closed-client-first validation and capability gates remain.

Cursor v1 keeps Base64URL JSON, field omission/order, binding, retention and
error reasons. Gson 2.11.0 `Strictness.STRICT` syntax handling is checked against
legacy goldens/coercion fixtures and public `fromToken` lexical-rejection tests,
including mixed-case literals, unescaped controls and a leading U+FEFF at the
String input boundary. Literal/escaped U+FEFF inside string values is preserved.
Escaped controls,
duplicate-last-wins and intentional one-root/trailing-root behavior remain.
Opaque Unicode/unpaired UTF-16 is preserved. Valid
Unicode escape spelling may differ without changing decoded cursor content.
No change-feed token migration waiver is applied.

## Source and evidence traceability

| Part | Source / executable coverage |
|---|---|
| Complete immutable model | API `document/*`; `DocumentModelTest`, `NumberValueTest`: exact six-kind sealed set, ownership, invalid erased byte[]/ByteBuffer/object inputs, duplicate/null/absence, order, equality/scale/kinds, finite extremes and exact bounds |
| Common codec/types/errors | API `codec/*`; `TypeRefTest`, `DocumentCodecExceptionTest`; handwritten `PlainCustomerCodec` |
| Optional Jackson mapping | `JacksonDocumentCodec`, `DocumentGenerator`, `DocumentTokens`, `MapperSnapshot`; corresponding codec/generator/snapshot tests preserve naming/date/generic mapping, coercion configuration, sticky failures, safe binary/embedded-output rejection and explicit customer Base64-to-string mapping |
| API runtime without Jackson | `CursorTokenCodec`/`CursorJson`, `DocumentSizeValidator`/`DocumentJsonSize`; `CursorWireCompatibilityTest`, `CursorJsonCompatibilityTest`, existing cursor/factory tests and `DocumentJsonSizeTest` legacy byte-profile comparison |
| Native boundaries | Three ProviderClient/ChangeFeedReader pairs and provider-local NativeDocuments; each `NativeDocumentsTest` rejects binary nodes for read/feed. `CustomerObjectMappingTest` checks actual read failures/legacy native binary fallbacks; the three `*ChangeFeedReaderTest` classes assert final event payload selection/null/nesting, safe model-domain errors and reachable binary/string behavior |
| Actual customer workflow | `CustomerObjectMappingTest`: real factory/wrapper/providers, native SDK mocks, custom Jackson and handwritten JDK-only codec plus all six kinds through create/upsert/read; Spanner fixture uses captured mutation including FIELD_DATA |
| Build isolation | API/provider/adapter POMs and module descriptors; runtime dependency/JAR inspection and manual API-only/optional-adapter JPMS consumer checks |

These customer workflow tests are **E2E unit tests**, not live DB persistence,
schema/index validation or universal numeric storage acceptance. Manual JPMS
checks are not a new CI framework or cross-version compatibility matrix.

```text
mvn -q -Punit -pl multiclouddb-conformance -am "-Dtest=CustomerObjectMappingTest,JacksonDocumentCodecTest,DocumentGeneratorTest,MapperSnapshotTest,DocumentModelTest,NumberValueTest,Cursor*Test,DocumentJsonSizeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -q -Punit clean verify
```

Still deferred from issue #116: uniform query Document/Projection/Value and
typed query boundaries, Full/Partial/None images, direct native provider mapping,
complete migration/conformance/benchmarks and provider numeric-domain work.
This increment does not guarantee less total future migration, authorize a
release or fix unrelated preexisting provider bugs.
