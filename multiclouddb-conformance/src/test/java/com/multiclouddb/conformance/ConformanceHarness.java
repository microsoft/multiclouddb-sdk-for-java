// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.*;
import com.multiclouddb.spi.MulticloudDbProviderAdapter;

import java.io.File;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Conformance test harness utilities.
 * <p>
 * Provides helper methods to build clients from config, create unique test
 * resource names, and clean up test data.
 */
public final class ConformanceHarness {

    private ConformanceHarness() {
    }

    /**
     * Create a {@link MulticloudDbClient} for the given provider using
     * {@link ConformanceConfig}.
     *
     * @param provider the target provider
     * @return a ready-to-use client
     */
    public static MulticloudDbClient createClient(ProviderId provider) {
        Objects.requireNonNull(provider, "provider");
        MulticloudDbClientConfig config = ConformanceConfig.forProvider(provider);
        return MulticloudDbClientFactory.create(config);
    }

    /**
     * Build a {@link ResourceAddress} using the default database/table for the
     * given provider.
     *
     * @param provider the target provider
     * @return a resource address pointing to the default conformance test table
     */
    public static ResourceAddress defaultAddress(ProviderId provider) {
        return new ResourceAddress(
                ConformanceConfig.databaseFor(provider),
                ConformanceConfig.tableFor(provider));
    }

    /**
     * Generate a unique test key with a random suffix to avoid collisions between
     * test runs.
     * Uses the same value for both id and partition key.
     *
     * @param prefix a human-readable prefix for the key
     * @return a unique {@link MulticloudDbKey}
     */
    public static MulticloudDbKey uniqueKey(String prefix) {
        String id = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return MulticloudDbKey.of(id, id);
    }

    /**
     * Generate a unique string suitable for resource names or identifiers.
     *
     * @param prefix a human-readable prefix
     * @return a unique string
     */
    public static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Silently delete a document, ignoring any errors (useful for test cleanup).
     *
     * @param client  the client to use
     * @param address the resource address
     * @param key     the key to delete
     */
    public static void safeDelete(MulticloudDbClient client, ResourceAddress address, MulticloudDbKey key) {
        try {
            client.delete(address, key);
        } catch (Exception ignored) {
            // Cleanup is best-effort
        }
    }

    /**
     * Packaged-artifact lane, invoked outside the reactor after install/deploy.
     * Child JVMs deliberately exclude provider target/classes and test/mocking libraries.
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && "--probe".equals(args[0])) {
            probePackagedProviders(Boolean.parseBoolean(args[1]), Boolean.parseBoolean(args[2]), args[3]);
            return;
        }
        require(args.length == 2, "Expected dependency directory and conformance test-classes");
        Path dependencies = Path.of(args[0]).toAbsolutePath();
        List<Path> jars;
        try (var files = Files.list(dependencies)) {
            jars = files.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        List<Path> runtime = jars.stream().filter(ConformanceHarness::isPublishedRuntime).toList();
        require(runtime.size() == 8, "Expected API, three providers, SLF4J, JNA/platform and Conscrypt: " + runtime);
        inspectPublishedArtifacts(dependencies, runtime);
        for (String scope : List.of("all", "cosmos", "dynamo", "spanner")) {
          List<Path> scopedRuntime = runtime.stream().filter(p ->
                  scope.equals("all") || belongsToProviderRuntime(p, scope)).toList();
          for (boolean modules : List.of(false, true)) {
            for (boolean competing : scope.equals("all") ? List.of(false, true) : List.of(false)) {
                List<String> command = new ArrayList<>();
                command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                String classpath = Path.of(args[1]).toAbsolutePath().toString();
                if (modules) {
                    String roots = scope.equals("all")
                            ? "com.multiclouddb.provider.cosmos,com.multiclouddb.provider.dynamo,com.multiclouddb.provider.spanner"
                            : "com.multiclouddb.provider." + scope;
                    command.addAll(List.of("--module-path", joinPaths(scopedRuntime), "--add-modules", roots,
                            "--add-exports", "com.multiclouddb.api/com.multiclouddb.spi=ALL-UNNAMED"));
                    // White-box codec/native probes only; no opens are added to the shipped descriptors.
                    Map<String, List<String>> openedPackages = Map.of(
                            "cosmos", List.of("com.azure.cosmos.implementation", "com.azure.core.http",
                                    "com.fasterxml.jackson.databind", "io.netty.handler.ssl", "io.netty.channel.epoll"),
                            "dynamo", List.of("software.amazon.awssdk.http", "software.amazon.awssdk.http.async",
                                    "software.amazon.awssdk.auth.credentials.internal",
                                    "software.amazon.awssdk.profiles"),
                            "spanner", List.of("io.grpc", "io.grpc.netty.shaded.io.netty.handler.ssl",
                                    "io.grpc.netty.shaded.io.netty.channel.epoll",
                                    "com.google.cloud", "com.google.cloud.spanner"));
                    for (var entry : openedPackages.entrySet()) {
                        if (!scope.equals("all") && !entry.getKey().equals(scope)) continue;
                        String module = "com.multiclouddb.provider." + entry.getKey();
                        command.addAll(List.of("--add-opens", module + "/" + module + "=ALL-UNNAMED"));
                        for (String pkg : entry.getValue()) {
                            command.addAll(List.of("--add-opens", module + "/com.multiclouddb.shaded."
                                    + entry.getKey() + "." + pkg + "=ALL-UNNAMED"));
                        }
                    }
                } else {
                    classpath += File.pathSeparator + joinPaths(scopedRuntime);
                }
                if (competing) {
                    classpath += File.pathSeparator + joinPaths(jars.stream()
                            .filter(p -> !isPublishedRuntime(p))
                            .filter(p -> !p.getFileName().toString().matches(
                                    "(mockito|byte-buddy|objenesis|junit|jcl-over-slf4j|logback|opentest4j|apiguardian).*"))
                            .toList());
                }
                command.addAll(List.of("-classpath", classpath, ConformanceHarness.class.getName(),
                        "--probe", Boolean.toString(modules), Boolean.toString(competing), scope));
                // Keep native command lines short even with deep Windows paths and competing SDKs.
                Path argumentFile = Files.createTempFile(dependencies.getParent(), "consumer-", ".args");
                Path home = Files.createTempDirectory(dependencies.getParent(), "consumer-home-");
                try {
                    command.add(1, "-Duser.home=" + home);
                    command.add(1, "-Dpackaged.fixtureHome=" + home);
                    command.add(1, "-Daws.region=us-east-1");
                    command.add(1, "-Daws.disableEc2Metadata=true");
                    command.add(1, "-Daws.configFile=" + home.resolve("config"));
                    command.add(1, "-Daws.sharedCredentialsFile=" + home.resolve("credentials"));
                    Files.write(argumentFile, command.subList(1, command.size()).stream()
                            .map(ConformanceHarness::quoteJavaArgument).toList());
                    ProcessBuilder builder = new ProcessBuilder(command.get(0), "@" + argumentFile).inheritIO();
                    builder.environment().keySet().removeIf(key -> key.startsWith("AWS_")
                            || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(key));
                    builder.environment().put("HOME", home.toString());
                    builder.environment().put("USERPROFILE", home.toString());
                    Process process = builder.start();
                    try {
                        if (!process.waitFor(90, TimeUnit.SECONDS)) {
                            throw new AssertionError("Packaged consumer timed out: module-path=" + modules);
                        }
                        require(process.exitValue() == 0,
                                "Packaged consumer failed: scope=" + scope + ", module-path=" + modules
                                        + ", competing=" + competing);
                    } finally {
                        if (process.isAlive()) {
                            process.destroyForcibly();
                            process.waitFor();
                        }
                    }
                } finally {
                    Files.deleteIfExists(argumentFile);
                    // This is a newly-created, task-owned fixture directory, never the real user home.
                    try (var files = Files.walk(home)) {
                        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
                    }
                }
            }
          }
        }
    }

    private static boolean isPublishedRuntime(Path path) {
        return path.getFileName().toString().matches(
                "(multiclouddb-(api|provider-(cosmos|dynamo|spanner))-|slf4j-api-|jna-|conscrypt-openjdk-uber-).*\\.jar");
    }

    private static boolean belongsToProviderRuntime(Path path, String provider) {
        String name = path.getFileName().toString();
        return name.startsWith("multiclouddb-api-") || name.startsWith("slf4j-api-")
                || name.startsWith("multiclouddb-provider-" + provider + "-")
                || (provider.equals("cosmos") && name.startsWith("jna-"))
                || (provider.equals("spanner") && name.startsWith("conscrypt-openjdk-uber-"));
    }

    private static String quoteJavaArgument(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String joinPaths(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    private static void inspectPublishedArtifacts(Path directory, List<Path> jars) throws Exception {
        Set<String> allClasses = new HashSet<>();
        Set<String> allPackages = new HashSet<>();
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            if (!name.startsWith("multiclouddb-")) continue;
            boolean api = name.startsWith("multiclouddb-api-");
            String provider = api ? "" : name.substring("multiclouddb-provider-".length()).split("-")[0];
            String ownPackage = api ? "com/multiclouddb/" : "com/multiclouddb/provider/" + provider + "/";
            String privatePackage = "com/multiclouddb/shaded/" + provider + "/";
            var descriptor = ModuleFinder.of(jar).findAll().iterator().next().descriptor();
            require(!descriptor.isAutomatic(), "Explicit JPMS descriptor lost: " + jar);
            for (String pkg : descriptor.packages()) {
                require(allPackages.add(pkg), "Split JPMS resource/class package: " + pkg);
            }
            Set<String> packages = new HashSet<>();
            try (JarFile archive = new JarFile(jar.toFile())) {
                for (String service : descriptor.uses()) {
                    if (service.startsWith("com.multiclouddb.shaded.")) {
                        require(archive.getEntry(service.replace('.', '/') + ".class") != null,
                                "Nonexistent JPMS service type: " + service);
                    }
                }
                for (var entry : Collections.list(archive.entries())) {
                    String original = entry.getName();
                    String resource = original.replaceFirst("^META-INF/versions/[0-9]+/", "");
                    require(!original.matches("META-INF/.*\\.(SF|DSA|RSA)"), "Stale signature: " + original);
                    if (!resource.endsWith(".class") || resource.equals("module-info.class")) continue;
                    require(resource.startsWith(ownPackage) || (!api && resource.startsWith(privatePackage)),
                            "Unisolated class in " + name + ": " + resource);
                    require(allClasses.add(original), "Duplicate class across SDK JARs: " + original);
                    packages.add(resource.substring(0, resource.lastIndexOf('/')).replace('/', '.'));
                }
                require(descriptor.packages().containsAll(packages), "Incomplete JPMS package inventory: " + name);
                if (!api) {
                    require("true".equals(archive.getManifest().getMainAttributes().getValue("Multi-Release")),
                            "Multi-release behavior lost: " + name);
                    Set<String> declaredServices = descriptor.provides().stream()
                            .map(java.lang.module.ModuleDescriptor.Provides::service).collect(Collectors.toSet());
                    for (var entry : Collections.list(archive.entries())) {
                        String service = entry.getName().replaceFirst("^META-INF/services/", "");
                        if (!entry.getName().startsWith("META-INF/services/") || entry.isDirectory()) continue;
                        if (archive.getEntry(service.replace('.', '/') + ".class") == null
                                && !service.equals(MulticloudDbProviderAdapter.class.getName())) {
                            // Optional Reactor BlockHound/context-propagation SPI is not bundled.
                            require(service.contains("blockhound.") || service.contains("micrometer.context."),
                                    "Unexpected external SPI: " + service);
                            continue;
                        }
                        require(declaredServices.contains(service), "Classpath-only service: " + service);
                    }
                }
            }
            Path pom = directory.resolve(name.replaceFirst("\\.jar$", ".pom"));
            require(Files.isRegularFile(pom), "Published POM missing: " + pom);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(pom.toFile());
            require(document.getElementsByTagName("parent").getLength() == 0, "Unflattened publication: " + pom);
            Set<String> expected = new HashSet<>(Set.of("slf4j-api"));
            if (!api) expected.add("multiclouddb-api");
            if (provider.equals("cosmos")) expected.addAll(Set.of("jna", "jna-platform"));
            if (provider.equals("spanner")) expected.add("conscrypt-openjdk-uber");
            Set<String> actual = new HashSet<>();
            var dependencies = document.getElementsByTagName("dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                var dependency = (org.w3c.dom.Element) dependencies.item(i);
                actual.add(dependency.getElementsByTagName("artifactId").item(0).getTextContent());
                var scopes = dependency.getElementsByTagName("scope");
                require(scopes.getLength() == 0 || Set.of("compile", "runtime").contains(scopes.item(0).getTextContent()),
                        "Required runtime dependency has a non-runtime scope: " + dependency.getTextContent());
                var optional = dependency.getElementsByTagName("optional");
                require(optional.getLength() == 0 || "false".equals(optional.item(0).getTextContent()),
                        "Required runtime dependency is optional: " + dependency.getTextContent());
            }
            require(actual.equals(expected), "Published dependency leakage: " + pom + " -> " + actual);
        }
    }

    private static void probePackagedProviders(boolean modules, boolean competing, String scope) throws Exception {
        Set<ProviderId> expectedProviders = scope.equals("all")
                ? Set.of(ProviderId.COSMOS, ProviderId.DYNAMO, ProviderId.SPANNER) : Set.of(ProviderId.fromId(scope));
        assertPackagedProviders(expectedProviders);
        for (var adapter : ServiceLoader.load(MulticloudDbProviderAdapter.class)) {
            require(adapter.getClass().getModule().isNamed() == modules, "Wrong module/classpath lane");
            require(adapter.createExpressionTranslator() != null, "Translator discovery failed");
        }
        if (scope.equals("dynamo") || scope.equals("all")) probeAwsCredentialFactories();
        if (!scope.equals("all")) {
            probeStandalonePublicFactory(scope);
            System.out.println("Standalone provider discovery/public error path OK: " + scope + ", module-path=" + modules);
            return;
        }
        probeCombinedProviders(modules, competing);
    }

    private static void probeAwsCredentialFactories() throws Exception {
        String aws = "com.multiclouddb.shaded.dynamo.software.amazon.awssdk.";
        Object webFactory = call(aws + "auth.credentials.internal.WebIdentityCredentialsUtils", null, "factory");
        requireJarOrigin(webFactory.getClass(), "multiclouddb-provider-dynamo-");
        Class<?> profileFileType = Class.forName(aws + "profiles.ProfileFile");
        Class<?> profileType = Class.forName(aws + "profiles.Profile");
        Class<?> builderType = Class.forName(aws + "profiles.ProfileFile$Builder");
        Class<?> fileType = Class.forName(aws + "profiles.ProfileFile$Type");
        String fixtureHome = System.getProperty("packaged.fixtureHome");
        require(fixtureHome != null && fixtureHome.equals(System.getProperty("user.home")),
                "AWS probes must run through the launcher with an isolated fixture home");
        Path tokenCache = Files.createDirectories(Path.of(fixtureHome, ".aws", "sso", "cache"));
        String sessionHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1")
                .digest("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.writeString(tokenCache.resolve(sessionHash + ".json"),
                "{\"accessToken\":\"fixture-only-not-a-real-token\",\"expiresAt\":\"2999-01-01T00:00:00Z\"}");
        String config = """
                [profile source]
                aws_access_key_id = fixture-only
                aws_secret_access_key = fixture-only
                [profile assume]
                role_arn = arn:aws:iam::123456789012:role/fixture
                source_profile = source
                [profile web]
                role_arn = arn:aws:iam::123456789012:role/fixture
                web_identity_token_file = unused-fixture-token
                [profile sso]
                sso_session = fixture
                sso_account_id = 123456789012
                sso_role_name = fixture
                [sso-session fixture]
                sso_region = us-east-1
                sso_start_url = https://fixture.invalid/start
                """;
        Object builder = call(profileFileType.getName(), null, "builder");
        method(builderType, "content", java.io.InputStream.class).invoke(builder,
                new java.io.ByteArrayInputStream(config.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        method(builderType, "type", fileType).invoke(builder, fileType.getField("CONFIGURATION").get(null));
        Object file = method(builderType, "build").invoke(builder);
        Method profiles = method(profileFileType, "profiles");
        Map<?, ?> profileMap = (Map<?, ?>) profiles.invoke(file);
        java.util.function.Function<String, Optional<?>> lookup = name -> Optional.ofNullable(profileMap.get(name));
        Class<?> credentialsUtils = Class.forName(aws + "auth.credentials.internal.ProfileCredentialsUtils");
        for (String name : List.of("assume", "web", "sso")) {
            Object utils = credentialsUtils.getConstructor(profileFileType, profileType, java.util.function.Function.class)
                    .newInstance(file, profileMap.get(name), lookup);
            Optional<?> selected = (Optional<?>) method(credentialsUtils, "credentialsProvider").invoke(utils);
            require(selected.isPresent(), "AWS profile factory absent: " + name);
            try (AutoCloseable provider = (AutoCloseable) selected.orElseThrow()) {
                requireJarOrigin(provider.getClass(), "multiclouddb-provider-dynamo-");
            }
        }
        // The sso_session path above invokes SSOOIDC token discovery and reads only
        // the synthetic unexpired cache; no refresh or AWS/SSO request is made.
    }

    private static void probeStandalonePublicFactory(String scope) throws Exception {
        if (scope.equals("cosmos")) {
            try (MulticloudDbClient ignored = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                    .provider(ProviderId.COSMOS).connection("endpoint", "not a valid URI")
                    .connection("key", "invalid-base64!").build())) {
                throw new AssertionError("Cosmos accepted an invalid native client configuration");
            } catch (IllegalArgumentException expected) {
                require(Arrays.stream(expected.getStackTrace()).anyMatch(frame ->
                                frame.getClassName().startsWith("com.multiclouddb.shaded.cosmos.com.azure.")),
                        "Cosmos did not reach its packaged native validation: " + expected);
            }
            return;
        }
        MulticloudDbClientConfig config = scope.equals("dynamo")
                ? MulticloudDbClientConfig.builder().provider(ProviderId.DYNAMO)
                        .connection("endpoint", "http://127.0.0.1:1").connection("region", "us-east-1")
                        .auth("accessKeyId", "fixture").auth("secretAccessKey", "fixture").build()
                : MulticloudDbClientConfig.builder().provider(ProviderId.SPANNER)
                        .connection("projectId", "fixture").connection("instanceId", "fixture")
                        .connection("databaseId", "fixture").connection("emulatorHost", "127.0.0.1:1").build();
        MulticloudDbClient client = MulticloudDbClientFactory.create(config);
        client.close();
        try {
            client.read(new ResourceAddress("fixture", "fixture"), MulticloudDbKey.of("key", "key"));
            throw new AssertionError("Closed packaged client accepted a read: " + scope);
        } catch (MulticloudDbException expected) {
            require(expected.error().category() == MulticloudDbErrorCategory.CLIENT_CLOSED,
                    "Wrong packaged public error: " + expected);
        }
    }

    private static void probeCombinedProviders(boolean modules, boolean competing) throws Exception {
        String cosmos = "com.multiclouddb.shaded.cosmos.";
        String spanner = "com.multiclouddb.shaded.spanner.";
        Object mapper = call(cosmos + "com.azure.cosmos.implementation.Utils", null, "getSimpleObjectMapper");
        String json = "{\"text\":\"\\uD83D\\uDE80\\n\\\"\",\"n\":9007199254740991,\"list\":[true,null,{\"x\":1}]}";
        Object cosmosDocument = method(mapper.getClass(), "readValue", String.class, Class.class)
                .invoke(mapper, json, Map.class);
        Class<?> rowMapper = Class.forName("com.multiclouddb.provider.spanner.SpannerRowMapper");
        Object spannerDocument = method(rowMapper, "readJson", String.class).invoke(null, json);
        require(cosmosDocument.equals(spannerDocument), "Packaged Cosmos/Spanner JSON semantics differ");
        String encoded = (String) method(rowMapper, "writeJson", Object.class).invoke(null, spannerDocument);
        require(spannerDocument.equals(method(rowMapper, "readJson", String.class).invoke(null, encoded)),
                "Spanner stored JSON did not round-trip");
        require(List.of("text", "n").equals(method(rowMapper, "fieldNames", String.class)
                .invoke(null, "[\"text\",\"n\"]")), "Spanner field metadata changed");
        Map<String, Object> feed = new LinkedHashMap<>(Map.of(
                "partitionKey", "pk", "text", "value", "stale", "old", "data", "[\"text\"]"));
        require(Map.of("partitionKey", "pk", "text", "value").equals(method(Class.forName(
                "com.multiclouddb.provider.spanner.SpannerChangeFeedReader"), "filterByFieldData", Object.class)
                .invoke(null, feed)), "Spanner change-stream field metadata changed");
        Class<?> mrClass = Class.forName(cosmos + "reactor.netty.resources.DefaultLoopNIO");
        require(mrClass.getResource("DefaultLoopNIO.class").toString().contains("META-INF/versions/17/"),
                "Relocated Java 17 multi-release implementation was not selected");
        call(cosmos + "com.azure.core.http.HttpClient", null, "createDefault");
        require(call(spanner + "io.grpc.ManagedChannelProvider", null, "provider") != null,
                "gRPC channel provider not found");
        Object spannerOptions = call(spanner + "com.google.cloud.spanner.SpannerOptions", null, "newBuilder");
        method(spannerOptions.getClass(), "setEmulatorHost", String.class).invoke(spannerOptions, "127.0.0.1:1");
        method(Class.forName(spanner + "com.google.cloud.ServiceOptions$Builder"), "setProjectId", String.class)
                .invoke(spannerOptions, "packaged-test");
        require(method(spannerOptions.getClass(), "build").invoke(spannerOptions) != null,
                "Spanner option/service factory discovery failed");
        for (String netty : List.of(cosmos + "io.netty.", spanner + "io.grpc.netty.shaded.io.netty.")) {
            call(netty + "handler.ssl.OpenSsl", null, "ensureAvailability");
            if (System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64")) {
                call(netty + "channel.epoll.Epoll", null, "ensureAvailability");
            }
        }
        require(((Integer) Class.forName("com.sun.jna.Native").getField("POINTER_SIZE").get(null)) > 0,
                "Shared JNA unavailable");
        Class<?> structure = Class.forName("com.sun.jna.Structure");
        Object credential = structure.getMethod("newInstance", Class.class).invoke(null,
                Class.forName(cosmos + "com.azure.identity.implementation.WindowsCredentialApi$CREDENTIAL"));
        require((Integer) structure.getMethod("size").invoke(credential) > 0,
                "External JNA cannot reflect on relocated Azure identity structures");
        call("org.conscrypt.Conscrypt", null, "checkAvailability");
        require(call("org.conscrypt.Conscrypt", null, "newProvider") != null, "Shared Conscrypt unavailable");

        // Construction uses explicit fake credentials and a loopback endpoint; no provisioning or cloud auth.
        try (var dynamo = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                .provider(ProviderId.DYNAMO).connection("endpoint", "http://127.0.0.1:1")
                .connection("region", "us-east-1").auth("accessKeyId", "test").auth("secretAccessKey", "test")
                .build())) {
            require(dynamo != null, "Dynamo HTTP provider construction failed");
        }
        if (competing) {
            Class<?> applicationMapper = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
            Object applicationJson = applicationMapper.getConstructor().newInstance();
            require(cosmosDocument.equals(applicationMapper.getMethod("readValue", String.class, Class.class)
                    .invoke(applicationJson, json, Map.class)), "Application Jackson broken");
            requireJarOrigin(applicationMapper, "jackson-databind-2.13.5");
            requireJarOrigin(Class.forName("com.google.gson.Gson"), "gson-2.10.1");
            requireJarOrigin(Class.forName("com.azure.cosmos.CosmosClient"), "azure-cosmos-");
            requireJarOrigin(Class.forName("software.amazon.awssdk.services.dynamodb.DynamoDbClient"), "dynamodb-");
            requireJarOrigin(Class.forName("com.google.cloud.spanner.Spanner"), "google-cloud-spanner-");
            call("io.netty.handler.ssl.OpenSsl", null, "ensureAvailability");
            call("io.grpc.netty.shaded.io.netty.handler.ssl.OpenSsl", null, "ensureAvailability");
        } else {
            for (String absent : List.of("com.fasterxml.jackson.databind.ObjectMapper", "com.google.gson.Gson",
                    "com.azure.cosmos.CosmosClient", "software.amazon.awssdk.services.dynamodb.DynamoDbClient",
                    "com.google.cloud.spanner.Spanner")) {
                try {
                    Class.forName(absent);
                    throw new AssertionError("Unshaded dependency masked the clean consumer: " + absent);
                } catch (ClassNotFoundException expected) {
                    // The clean lane must not be rescued by application SDK dependencies.
                }
            }
        }
        System.out.println("Packaged providers OK: module-path=" + modules + ", competing=" + competing);
    }

    static void assertPackagedProviders() throws Exception {
        assertPackagedProviders(Set.of(ProviderId.COSMOS, ProviderId.DYNAMO, ProviderId.SPANNER));
    }

    private static void assertPackagedProviders(Set<ProviderId> expected) throws Exception {
        Set<ProviderId> providers = new HashSet<>();
        for (var adapter : ServiceLoader.load(MulticloudDbProviderAdapter.class)) {
            require(providers.add(adapter.providerId()), "Duplicate provider: " + adapter.providerId());
            requireJarOrigin(adapter.getClass(), "multiclouddb-provider-" + adapter.providerId().id());
        }
        require(providers.equals(expected),
                "Provider discovery incomplete: " + providers);
        requireJarOrigin(MulticloudDbClient.class, "multiclouddb-api");

    }

    private static Object call(String className, Object target, String name) throws Exception {
        return method(Class.forName(className), name).invoke(target);
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    private static void requireJarOrigin(Class<?> type, String artifact) throws Exception {
        Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(origin.getFileName().toString().startsWith(artifact) && origin.toString().endsWith(".jar"),
                "Not loaded from published " + artifact + " JAR: " + origin);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
