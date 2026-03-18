// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.samples.todo;

import com.hyperscaledb.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;

/**
 * Sample TODO web application demonstrating the Hyperscale DB SDK.
 * <p>
 * Starts an embedded HTTP server with a REST API and a browser-based UI.
 * Switch between Azure Cosmos DB, Amazon DynamoDB, and Google Cloud Spanner
 * by changing the properties file — no code changes required.
 * <p>
 * Usage:
 * 
 * <pre>
 *   mvn -pl hyperscaledb-samples exec:java \
 *     -Dexec.mainClass="com.hyperscaledb.samples.todo.TodoApp" \
 *     -Dtodo.config=todo-app-cosmos.properties
 * </pre>
 */
public class TodoApp {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DATABASE = "todoapp";
    private static final String COLLECTION = "todos";
    private static final int DEFAULT_PORT = 8080;

    private final HyperscaleDbClient client;
    private final ResourceAddress address;

    public TodoApp(HyperscaleDbClient client) {
        this.client = client;
        this.address = new ResourceAddress(DATABASE, COLLECTION);
    }

    // ── CRUD operations ─────────────────────────────────────────────────────

    public JsonNode createTodo(String id, String title) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("title", title);
        doc.put("completed", false);
        doc.put("createdAt", Instant.now().toString());
        doc.put("updatedAt", Instant.now().toString());

        Key key = Key.of(id, id); // id doubles as partition key
        client.upsert(address, key, doc);

        doc.put("id", id);
        return doc;
    }

    public JsonNode getTodo(String id) {
        Key key = Key.of(id, id);
        return client.read(address, key);
    }

    public JsonNode updateTodo(String id, JsonNode updates) {
        Key key = Key.of(id, id);
        JsonNode existing = client.read(address, key);
        if (existing == null) {
            return null;
        }

        ObjectNode updated = existing.deepCopy();
        updates.fields().forEachRemaining(field -> updated.set(field.getKey(), field.getValue()));
        updated.put("updatedAt", Instant.now().toString());
        client.upsert(address, key, updated);
        return updated;
    }

    public void deleteTodo(String id) {
        Key key = Key.of(id, id);
        client.delete(address, key);
    }

    public ArrayNode listTodos() {
        QueryRequest query = QueryRequest.builder()
                .pageSize(100)
                .build();

        ArrayNode result = MAPPER.createArrayNode();
        QueryPage page = client.query(address, query);
        for (JsonNode item : page.items()) {
            result.add(item);
        }
        return result;
    }

    public ObjectNode getCapabilities() {
        CapabilitySet caps = client.capabilities();
        ObjectNode result = MAPPER.createObjectNode();
        result.put("provider", client.providerId().displayName());
        result.put("providerId", client.providerId().id());

        ArrayNode capsArray = result.putArray("capabilities");
        for (Capability cap : caps.all()) {
            ObjectNode c = MAPPER.createObjectNode();
            c.put("name", cap.name());
            c.put("supported", cap.supported());
            if (cap.notes() != null)
                c.put("notes", cap.notes());
            capsArray.add(c);
        }
        return result;
    }

    // ── HTTP Server ─────────────────────────────────────────────────────────

    public void startServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/todos", this::handleTodos);
        server.createContext("/api/capabilities", this::handleCapabilities);
        server.createContext("/", this::handleStatic);

        server.setExecutor(null);
        server.start();

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("  Hyperscale DB TODO App");
        System.out.println("  Provider:  " + client.providerId().displayName());
        System.out.println("  UI:        http://localhost:" + port);
        System.out.println("  API:       http://localhost:" + port + "/api/todos");
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
    }

    // ── REST handlers ───────────────────────────────────────────────────────

    private void handleTodos(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        // Extract optional ID from /api/todos/{id}
        String id = null;
        if (path.length() > "/api/todos/".length()) {
            id = path.substring("/api/todos/".length());
        }

        try {
            switch (method) {
                case "GET" -> {
                    if (id != null) {
                        JsonNode todo = getTodo(id);
                        if (todo == null) {
                            sendJson(exchange, 404,
                                    MAPPER.createObjectNode().put("error", "Not found"));
                        } else {
                            sendJson(exchange, 200, todo);
                        }
                    } else {
                        sendJson(exchange, 200, listTodos());
                    }
                }
                case "POST" -> {
                    JsonNode body = MAPPER.readTree(exchange.getRequestBody());
                    String newId = body.has("id")
                            ? body.get("id").asText()
                            : "todo-" + System.currentTimeMillis();
                    String title = body.has("title")
                            ? body.get("title").asText()
                            : "(untitled)";
                    JsonNode created = createTodo(newId, title);
                    sendJson(exchange, 201, created);
                }
                case "PUT" -> {
                    if (id == null) {
                        sendJson(exchange, 400,
                                MAPPER.createObjectNode().put("error", "ID required"));
                        return;
                    }
                    JsonNode body = MAPPER.readTree(exchange.getRequestBody());
                    JsonNode updated = updateTodo(id, body);
                    if (updated == null) {
                        sendJson(exchange, 404,
                                MAPPER.createObjectNode().put("error", "Not found"));
                    } else {
                        sendJson(exchange, 200, updated);
                    }
                }
                case "DELETE" -> {
                    if (id == null) {
                        sendJson(exchange, 400,
                                MAPPER.createObjectNode().put("error", "ID required"));
                        return;
                    }
                    deleteTodo(id);
                    sendJson(exchange, 200,
                            MAPPER.createObjectNode().put("deleted", id));
                }
                case "OPTIONS" -> {
                    addCorsHeaders(exchange);
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                }
                default -> sendJson(exchange, 405,
                        MAPPER.createObjectNode().put("error", "Method not allowed"));
            }
        } catch (HyperscaleDbException e) {
            ObjectNode err = MAPPER.createObjectNode();
            err.put("error", e.getMessage());
            err.put("category", e.error().category().name());
            sendJson(exchange, 500, err);
        } catch (Exception e) {
            sendJson(exchange, 500,
                    MAPPER.createObjectNode().put("error", e.getMessage()));
        }
    }

    private void handleCapabilities(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, getCapabilities());
        } else {
            sendJson(exchange, 405,
                    MAPPER.createObjectNode().put("error", "Method not allowed"));
        }
    }

    // ── Static file serving ─────────────────────────────────────────────────

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path) || "/index.html".equals(path)) {
            serveResource(exchange, "/static/index.html", "text/html");
        } else if (path.startsWith("/static/")) {
            serveResource(exchange, path, guessContentType(path));
        } else {
            // SPA fallback
            serveResource(exchange, "/static/index.html", "text/html");
        }
    }

    private void serveResource(HttpExchange exchange, String resourcePath,
            String contentType) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                String body = "Not found: " + resourcePath;
                exchange.sendResponseHeaders(404, body.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            byte[] data = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type",
                    contentType + "; charset=utf-8");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    private void sendJson(HttpExchange exchange, int status, JsonNode body)
            throws IOException {
        byte[] data = MAPPER.writeValueAsBytes(body);
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private void addCorsHeaders(HttpExchange exchange) {
        var h = exchange.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private String guessContentType(String path) {
        if (path.endsWith(".html"))
            return "text/html";
        if (path.endsWith(".css"))
            return "text/css";
        if (path.endsWith(".js"))
            return "application/javascript";
        if (path.endsWith(".json"))
            return "application/json";
        if (path.endsWith(".svg"))
            return "image/svg+xml";
        if (path.endsWith(".png"))
            return "image/png";
        if (path.endsWith(".ico"))
            return "image/x-icon";
        return "application/octet-stream";
    }

    // ── Main ────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        Properties props = loadProperties();
        HyperscaleDbClientConfig config = buildConfig(props);

        int port = Integer.parseInt(
                System.getProperty("todo.port", String.valueOf(DEFAULT_PORT)));

        HyperscaleDbClient client = HyperscaleDbClientFactory.create(config);
        TodoApp app = new TodoApp(client);
        app.startServer(port);

        // Block main thread so the server stays alive
        Thread.currentThread().join();
    }

    private static Properties loadProperties() throws IOException {
        Properties props = new Properties();
        String propsFile = System.getProperty("todo.config",
                "todo-app-cosmos.properties");

        try (InputStream is = TodoApp.class.getClassLoader()
                .getResourceAsStream(propsFile)) {
            if (is != null) {
                props.load(is);
                System.out.println("  Loaded config: " + propsFile);
            } else {
                System.out.println("  Config file not found: " + propsFile
                        + " — using system properties");
            }
        }

        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("hyperscaledb.")) {
                props.setProperty(key, System.getProperty(key));
            }
        }

        return props;
    }

    private static HyperscaleDbClientConfig buildConfig(Properties props) {
        String providerName = props.getProperty("hyperscaledb.provider", "cosmos");
        ProviderId provider = ProviderId.fromId(providerName);

        HyperscaleDbClientConfig.Builder builder = HyperscaleDbClientConfig.builder()
                .provider(provider);

        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("hyperscaledb.connection.")) {
                builder.connection(
                        key.substring("hyperscaledb.connection.".length()),
                        props.getProperty(key));
            }
            if (key.startsWith("hyperscaledb.auth.")) {
                builder.auth(
                        key.substring("hyperscaledb.auth.".length()),
                        props.getProperty(key));
            }
            if (key.startsWith("hyperscaledb.feature.")) {
                builder.featureFlag(
                        key.substring("hyperscaledb.feature.".length()),
                        props.getProperty(key));
            }
        }

        return builder.build();
    }
}
