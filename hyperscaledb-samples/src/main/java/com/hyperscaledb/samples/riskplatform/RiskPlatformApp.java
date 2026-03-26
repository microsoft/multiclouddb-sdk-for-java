// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.samples.riskplatform;

import com.hyperscaledb.api.Capability;
import com.hyperscaledb.api.HyperscaleDbClient;
import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.HyperscaleDbClientFactory;
import com.hyperscaledb.api.HyperscaleDbKey;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.QueryRequest;
import com.hyperscaledb.samples.riskplatform.data.DemoDataSeeder;
import com.hyperscaledb.samples.riskplatform.infra.ResourceProvisioner;
import com.hyperscaledb.samples.riskplatform.tenant.TenantManager;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Multi-Tenant Portfolio Risk Analysis Platform.
 * <p>
 * An executive-demo-quality sample application built on the Hyperscale DB SDK,
 * demonstrating database-per-tenant isolation across Azure Cosmos DB and
 * Amazon DynamoDB. Inspired by enterprise risk platforms serving capital
 * markets, investment banks, and hedge funds.
 * <p>
 * Features:
 * <ul>
 * <li>Multi-tenant isolation (database-per-tenant)</li>
 * <li>Portfolio management with real-time positions</li>
 * <li>Risk analytics: VaR, Sharpe, Beta, drawdown, volatility</li>
 * <li>Real-time market data feed</li>
 * <li>Risk alerting engine</li>
 * <li>Professional dark-themed financial dashboard</li>
 * <li>Portable across Cosmos DB and DynamoDB — zero code changes</li>
 * </ul>
 * <p>
 * Usage:
 * 
 * <pre>
 *   mvn -pl hyperscaledb-samples exec:java \
 *     -Dexec.mainClass="com.hyperscaledb.samples.riskplatform.RiskPlatformApp" \
 *     -Drisk.config=risk-platform-cosmos.properties
 * </pre>
 */
public class RiskPlatformApp {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_PORT = 8090;

    private final TenantManager tenantManager;

    public RiskPlatformApp(TenantManager tenantManager) {
        this.tenantManager = tenantManager;
    }

    // ── HTTP Server ─────────────────────────────────────────────────────────

    public void startServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // REST API endpoints
        server.createContext("/api/tenants", this::handleTenants);
        server.createContext("/api/portfolios", this::handlePortfolios);
        server.createContext("/api/positions", this::handlePositions);
        server.createContext("/api/risk", this::handleRiskMetrics);
        server.createContext("/api/alerts", this::handleAlerts);
        server.createContext("/api/market", this::handleMarketData);
        server.createContext("/api/dashboard", this::handleDashboard);
        server.createContext("/api/provider", this::handleProvider);

        // Static files
        server.createContext("/", this::handleStatic);

        server.setExecutor(null);
        server.start();

        String providerName  = tenantManager.getClient().providerId().displayName();
        String dashboardUrl  = "http://localhost:" + port;
        String apiUrl        = "http://localhost:" + port + "/api";

        // Inner width = number of chars between the two ║ border chars
        final int W = 59;

        System.out.println();
        System.out.println("╔" + "═".repeat(W) + "╗");
        System.out.println("║" + row("   RISK ANALYSIS PLATFORM -- Multi-Tenant Demo", W) + "║");
        System.out.println("╠" + "═".repeat(W) + "╣");
        System.out.println("║" + row("   Provider:  " + providerName, W) + "║");
        System.out.println("║" + row("   Dashboard: " + dashboardUrl, W) + "║");
        System.out.println("║" + row("   API:       " + apiUrl,       W) + "║");
        System.out.println("╠" + "═".repeat(W) + "╣");
        System.out.println("║" + row("   Tenants:   acme-capital, vanguard-partners, summit-wealth", W) + "║");
        System.out.println("╚" + "═".repeat(W) + "╝");
        System.out.println();
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
    }

    /** Left-aligns {@code s} in a field of exactly {@code width} chars, truncating if too long. */
    private String row(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    private String pad(String s, int width) {
        if (s.length() >= width)
            return s;
        return s + " ".repeat(width - s.length());
    }

    // ── REST: Tenants ───────────────────────────────────────────────────────

    private void handleTenants(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String tenantId = extractPathParam(path, "/api/tenants/");

            if (tenantId != null && !tenantId.isEmpty()) {
                JsonNode tenant = tenantManager.getTenant(tenantId);
                if (tenant == null) {
                    sendError(exchange, 404, "Tenant not found: " + tenantId);
                } else {
                    sendJson(exchange, 200, tenant);
                }
            } else {
                List<JsonNode> tenants = tenantManager.listTenants();
                sendJson(exchange, 200, toArray(tenants));
            }
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ── REST: Portfolios ────────────────────────────────────────────────────

    private void handlePortfolios(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String tenantId = getQueryParam(exchange, "tenant");

            if ("OPTIONS".equals(method)) {
                addCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (tenantId == null) {
                sendError(exchange, 400, "Missing 'tenant' query parameter");
                return;
            }

            if ("POST".equals(method)) {
                // Create new portfolio
                JsonNode body = readBody(exchange);
                String pid = "pf-" + shortId();
                String name = body.path("name").asText("New Portfolio");
                String type = body.path("type").asText("EQUITY");
                String currency = body.path("currency").asText("USD");
                String benchmark = body.path("benchmarkIndex").asText("S&P 500");

                ObjectNode doc = com.hyperscaledb.samples.riskplatform.model.Models
                        .portfolio(pid, name, type, currency, benchmark);
                tenantManager.upsert(tenantId, "portfolios", HyperscaleDbKey.of(pid, pid), doc);
                sendJson(exchange, 201, doc);
                return;
            }

            if ("DELETE".equals(method)) {
                String path = exchange.getRequestURI().getPath();
                String portfolioId = extractPathParam(path, "/api/portfolios/");
                if (portfolioId == null || portfolioId.isEmpty()) {
                    sendError(exchange, 400, "Missing portfolio ID in path");
                    return;
                }
                tenantManager.delete(tenantId, "portfolios", HyperscaleDbKey.of(portfolioId, portfolioId));
                ObjectNode resp = MAPPER.createObjectNode();
                resp.put("deleted", portfolioId);
                sendJson(exchange, 200, resp);
                return;
            }

            if (!"GET".equals(method)) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            List<JsonNode> portfolios = tenantManager.listAll(tenantId, "portfolios");

            // Enrich portfolios with summary stats
            ArrayNode result = MAPPER.createArrayNode();
            for (JsonNode p : portfolios) {
                String pid = p.path("portfolioId").asText();
                ObjectNode enriched = p.deepCopy();

                // Partition-scoped query: only positions belonging to this portfolio
                List<JsonNode> positions = tenantManager.queryByPartition(
                        tenantId, "positions", pid);
                double totalValue = 0;
                double totalPnL = 0;
                for (JsonNode pos : positions) {
                    totalValue += pos.path("marketValue").asDouble();
                    totalPnL += pos.path("unrealizedPnL").asDouble();
                }
                enriched.put("totalMarketValue", totalValue);
                enriched.put("totalUnrealizedPnL", totalPnL);
                enriched.put("positionCount", positions.size());

                result.add(enriched);
            }

            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ── REST: Positions ─────────────────────────────────────────────────────

    private void handlePositions(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String tenantId = getQueryParam(exchange, "tenant");

            if ("OPTIONS".equals(method)) {
                addCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (tenantId == null) {
                sendError(exchange, 400, "Missing 'tenant' query parameter");
                return;
            }

            if ("POST".equals(method)) {
                // Create new position
                JsonNode body = readBody(exchange);
                String posId = "pos-" + shortId();
                String portfolioId = body.path("portfolioId").asText();
                String symbol = body.path("symbol").asText("UNKNOWN");
                String assetClass = body.path("assetClass").asText("Equity");
                double quantity = body.path("quantity").asDouble(100);
                double avgCost = body.path("averageCost").asDouble(100.0);
                double currentPrice = body.path("currentPrice").asDouble(100.0);
                String sector = body.path("sector").asText("Technology");

                if (portfolioId.isEmpty()) {
                    sendError(exchange, 400, "Missing 'portfolioId' in request body");
                    return;
                }

                ObjectNode doc = com.hyperscaledb.samples.riskplatform.model.Models
                        .position(posId, portfolioId, symbol, assetClass,
                                quantity, avgCost, currentPrice, sector);
                tenantManager.upsert(tenantId, "positions", HyperscaleDbKey.of(portfolioId, posId), doc);
                sendJson(exchange, 201, doc);
                return;
            }

            if ("DELETE".equals(method)) {
                String path = exchange.getRequestURI().getPath();
                String positionId = extractPathParam(path, "/api/positions/");
                if (positionId == null || positionId.isEmpty()) {
                    sendError(exchange, 400, "Missing position ID in path");
                    return;
                }
                String portfolioIdParam = getQueryParam(exchange, "portfolio");
                if (portfolioIdParam == null || portfolioIdParam.isEmpty()) {
                    sendError(exchange, 400, "Missing 'portfolio' query parameter for position delete");
                    return;
                }
                tenantManager.delete(tenantId, "positions",
                        HyperscaleDbKey.of(portfolioIdParam, positionId));
                ObjectNode resp = MAPPER.createObjectNode();
                resp.put("deleted", positionId);
                sendJson(exchange, 200, resp);
                return;
            }

            if (!"GET".equals(method)) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            String portfolioId = getQueryParam(exchange, "portfolio");

            List<JsonNode> positions;

            // Partition-scoped query: only positions belonging to this portfolio
            if (portfolioId != null && !portfolioId.isEmpty()) {
                positions = tenantManager.queryByPartition(
                        tenantId, "positions", portfolioId);
            } else {
                positions = tenantManager.listAll(tenantId, "positions");
            }

            sendJson(exchange, 200, toArray(positions));
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ── REST: Risk Metrics ──────────────────────────────────────────────────

    private void handleRiskMetrics(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            String tenantId = getQueryParam(exchange, "tenant");
            String portfolioId = getQueryParam(exchange, "portfolio");

            if (tenantId == null) {
                sendError(exchange, 400, "Missing 'tenant' query parameter");
                return;
            }

            List<JsonNode> metrics;

            // Partition-scoped query: only metrics for this portfolio
            if (portfolioId != null && !portfolioId.isEmpty()) {
                metrics = tenantManager.queryByPartition(
                        tenantId, "risk_metrics", portfolioId);
            } else {
                metrics = tenantManager.listAll(tenantId, "risk_metrics");
            }

            sendJson(exchange, 200, toArray(metrics));
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ── REST: Alerts ────────────────────────────────────────────────────────

    private void handleAlerts(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String tenantId = getQueryParam(exchange, "tenant");

            if ("OPTIONS".equals(method)) {
                addCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (tenantId == null) {
                sendError(exchange, 400, "Missing 'tenant' query parameter");
                return;
            }

            if ("GET".equals(method)) {
                List<JsonNode> alerts = tenantManager.listAll(tenantId, "alerts");
                sendJson(exchange, 200, toArray(alerts));
            } else if ("POST".equals(method)) {
                // Create new alert
                JsonNode body = readBody(exchange);
                String alertId = "alert-" + shortId();
                String portfolioId = body.path("portfolioId").asText("");
                String severity = body.path("severity").asText("MEDIUM");
                String type = body.path("type").asText("CONCENTRATION");
                String message = body.path("message").asText("New risk alert");

                if (portfolioId.isEmpty()) {
                    sendError(exchange, 400, "Missing 'portfolioId' in request body");
                    return;
                }

                ObjectNode doc = com.hyperscaledb.samples.riskplatform.model.Models
                        .alert(alertId, portfolioId, severity, type, message);
                tenantManager.upsert(tenantId, "alerts", HyperscaleDbKey.of(portfolioId, alertId), doc);
                sendJson(exchange, 201, doc);
            } else if ("PUT".equals(method)) {
                // Acknowledge alert
                String path = exchange.getRequestURI().getPath();
                String alertId = extractPathParam(path, "/api/alerts/");
                String portfolioId = getQueryParam(exchange, "portfolio");
                if (alertId != null && alertId.endsWith("/acknowledge")) {
                    alertId = alertId.replace("/acknowledge", "");
                    if (portfolioId == null || portfolioId.isEmpty()) {
                        sendError(exchange, 400,
                                "Missing 'portfolio' query parameter for alert acknowledge");
                        return;
                    }
                    JsonNode existing = tenantManager.read(tenantId, "alerts",
                            HyperscaleDbKey.of(portfolioId, alertId));
                    if (existing != null) {
                        ObjectNode updated = existing.deepCopy();
                        updated.put("acknowledged", true);
                        updated.put("status", "ACKNOWLEDGED");
                        tenantManager.upsert(tenantId, "alerts",
                                HyperscaleDbKey.of(portfolioId, alertId), updated);
                        sendJson(exchange, 200, updated);
                    } else {
                        sendError(exchange, 404, "Alert not found");
                    }
                } else {
                    sendError(exchange, 400, "Invalid alert operation");
                }
            } else if ("DELETE".equals(method)) {
                String path = exchange.getRequestURI().getPath();
                String alertId = extractPathParam(path, "/api/alerts/");
                if (alertId == null || alertId.isEmpty()) {
                    sendError(exchange, 400, "Missing alert ID in path");
                    return;
                }
                String portfolioId = getQueryParam(exchange, "portfolio");
                if (portfolioId == null || portfolioId.isEmpty()) {
                    sendError(exchange, 400,
                            "Missing 'portfolio' query parameter for alert delete");
                    return;
                }
                tenantManager.delete(tenantId, "alerts", HyperscaleDbKey.of(portfolioId, alertId));
                ObjectNode resp = MAPPER.createObjectNode();
                resp.put("deleted", alertId);
                sendJson(exchange, 200, resp);
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ── REST: Market Data ───────────────────────────────────────────────────

    private void handleMarketData(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            List<JsonNode> marketData = tenantManager.listAll("_shared", "market_data");
            sendJson(exchange, 200, toArray(marketData));
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ── REST: Dashboard (aggregated view per tenant) ────────────────────────

    private void handleDashboard(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            String tenantId = getQueryParam(exchange, "tenant");
            if (tenantId == null) {
                sendError(exchange, 400, "Missing 'tenant' query parameter");
                return;
            }

            // Fetch all data for comprehensive dashboard
            List<JsonNode> portfolios = tenantManager.listAll(tenantId, "portfolios");
            List<JsonNode> positions = tenantManager.listAll(tenantId, "positions");
            List<JsonNode> riskMetrics = tenantManager.listAll(tenantId, "risk_metrics");
            List<JsonNode> alerts = tenantManager.listAll(tenantId, "alerts");
            JsonNode tenant = tenantManager.getTenant(tenantId);

            ObjectNode dashboard = MAPPER.createObjectNode();
            dashboard.set("tenant", tenant);

            // Summary KPIs
            ObjectNode summary = dashboard.putObject("summary");
            double totalAum = 0, totalPnL = 0, totalVaR = 0;
            for (JsonNode pos : positions) {
                totalAum += pos.path("marketValue").asDouble();
                totalPnL += pos.path("unrealizedPnL").asDouble();
            }
            for (JsonNode rm : riskMetrics) {
                totalVaR += rm.path("valueAtRisk95").asDouble();
            }
            summary.put("totalAUM", totalAum);
            summary.put("totalUnrealizedPnL", totalPnL);
            summary.put("totalValueAtRisk95", totalVaR);
            summary.put("portfolioCount", portfolios.size());
            summary.put("positionCount", positions.size());
            List<JsonNode> openAlerts = tenantManager.query(tenantId, "alerts",
                    QueryRequest.builder()
                            .expression("status = @status")
                            .parameters(Map.of("status", "OPEN"))
                            .maxPageSize(200)
                            .build());
            summary.put("openAlerts", openAlerts.size());

            // Sector allocation across all positions
            ObjectNode sectors = dashboard.putObject("sectorAllocation");
            for (JsonNode pos : positions) {
                String sector = pos.path("sector").asText("Unknown");
                double mv = pos.path("marketValue").asDouble();
                sectors.put(sector, sectors.path(sector).asDouble() + mv);
            }

            // Asset class breakdown
            ObjectNode assetClasses = dashboard.putObject("assetClassBreakdown");
            for (JsonNode pos : positions) {
                String ac = pos.path("assetClass").asText("Unknown");
                double mv = pos.path("marketValue").asDouble();
                assetClasses.put(ac, assetClasses.path(ac).asDouble() + mv);
            }

            dashboard.set("portfolios", toArray(portfolios));
            dashboard.set("riskMetrics", toArray(riskMetrics));
            dashboard.set("alerts", toArray(alerts));

            sendJson(exchange, 200, dashboard);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ── REST: Provider info ─────────────────────────────────────────────────

    private void handleProvider(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            HyperscaleDbClient client = tenantManager.getClient();
            ObjectNode info = MAPPER.createObjectNode();
            info.put("provider", client.providerId().displayName());
            info.put("providerId", client.providerId().id());
            info.put("isolation", "database-per-tenant");

            ArrayNode caps = info.putArray("capabilities");
            for (Capability cap : client.capabilities().all()) {
                ObjectNode c = MAPPER.createObjectNode();
                c.put("name", cap.name());
                c.put("supported", cap.supported());
                caps.add(c);
            }

            sendJson(exchange, 200, info);
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ── Static file serving ─────────────────────────────────────────────────

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        // Map root and all non-API paths to the risk platform SPA
        if ("/".equals(path) || "/index.html".equals(path) || !path.startsWith("/api/")) {
            serveResource(exchange, "/static/riskplatform/index.html", "text/html");
        } else {
            String body = "Not found";
            exchange.sendResponseHeaders(404, body.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
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

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] data = is.readAllBytes();
            if (data.length == 0)
                return MAPPER.createObjectNode();
            return MAPPER.readTree(data);
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] data = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message);
        err.put("status", status);
        sendJson(exchange, status, err);
    }

    private void addCorsHeaders(HttpExchange exchange) {
        var h = exchange.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private String extractPathParam(String path, String prefix) {
        if (path.length() > prefix.length()) {
            return path.substring(prefix.length());
        }
        return null;
    }

    private String getQueryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null)
            return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }

    private ArrayNode toArray(List<JsonNode> list) {
        ArrayNode arr = MAPPER.createArrayNode();
        list.forEach(arr::add);
        return arr;
    }

    // ── Main ────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("  Starting Risk Analysis Platform...");

        Properties props = loadProperties();
        HyperscaleDbClientConfig config = buildConfig(props);

        int port = Integer.parseInt(
                System.getProperty("risk.port", String.valueOf(DEFAULT_PORT)));

        HyperscaleDbClient client = HyperscaleDbClientFactory.create(config);

        // Provision databases/containers/tables (provider-specific)
        ResourceProvisioner provisioner = new ResourceProvisioner(client);
        provisioner.provision();

        TenantManager tenantManager = new TenantManager(client);

        // Seed demo data
        DemoDataSeeder seeder = new DemoDataSeeder(tenantManager);
        seeder.seedAll();

        // Start server
        RiskPlatformApp app = new RiskPlatformApp(tenantManager);
        app.startServer(port);

        // Block main thread
        Thread.currentThread().join();
    }

    private static Properties loadProperties() throws IOException {
        Properties props = new Properties();
        String propsFile = System.getProperty("risk.config",
                "risk-platform-cosmos.properties");

        try (InputStream is = RiskPlatformApp.class.getClassLoader()
                .getResourceAsStream(propsFile)) {
            if (is != null) {
                props.load(is);
                System.out.println("  Loaded config: " + propsFile);
            } else {
                System.out.println("  Config file not found: " + propsFile
                        + " — using system properties / env vars");
            }
        }

        // System properties override file properties
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
        }

        return builder.build();
    }
}
