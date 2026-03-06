package com.hyperscaledb.samples.riskplatform.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Domain model builders for the multi-tenant risk analysis platform.
 * <p>
 * All models are stored as {@link JsonNode} documents — the Hyperscale DB SDK's
 * portable document format. Builder methods produce pre-structured documents
 * ready for {@code client.upsert()}.
 */
public final class Models {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Models() {
    }

    // ── Tenant ──────────────────────────────────────────────────────────────

    /**
     * Build a tenant registration document.
     *
     * @param tenantId unique short name (e.g. "acme-capital")
     * @param name     display name
     * @param tier     subscription tier: ENTERPRISE, PROFESSIONAL, STARTER
     * @param industry industry vertical
     */
    public static ObjectNode tenant(String tenantId, String name, String tier, String industry) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("id", tenantId);
        doc.put("tenantId", tenantId);
        doc.put("name", name);
        doc.put("tier", tier);
        doc.put("industry", industry);
        doc.put("status", "ACTIVE");
        doc.put("createdAt", Instant.now().toString());
        doc.put("databaseName", tenantId + "-risk-db");
        return doc;
    }

    // ── Portfolio ───────────────────────────────────────────────────────────

    /**
     * Build a portfolio document.
     *
     * @param portfolioId    unique identifier
     * @param name           portfolio display name
     * @param type           EQUITY, FIXED_INCOME, MULTI_ASSET, DERIVATIVES,
     *                       ALTERNATIVES
     * @param currency       base currency (USD, EUR, GBP, JPY)
     * @param benchmarkIndex benchmark (e.g. "S&P 500", "MSCI World")
     */
    public static ObjectNode portfolio(String portfolioId, String name, String type,
            String currency, String benchmarkIndex) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("id", portfolioId);
        doc.put("portfolioId", portfolioId);
        doc.put("name", name);
        doc.put("type", type);
        doc.put("currency", currency);
        doc.put("benchmarkIndex", benchmarkIndex);
        doc.put("status", "ACTIVE");
        doc.put("inceptionDate", "2024-01-15");
        doc.put("updatedAt", Instant.now().toString());
        return doc;
    }

    // ── Position ────────────────────────────────────────────────────────────

    /**
     * Build a position (holding) within a portfolio.
     */
    public static ObjectNode position(String positionId, String portfolioId,
            String symbol, String assetClass,
            double quantity, double avgCost,
            double currentPrice, String sector) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("id", positionId);
        doc.put("positionId", positionId);
        doc.put("portfolioId", portfolioId);
        doc.put("symbol", symbol);
        doc.put("assetClass", assetClass);
        doc.put("quantity", quantity);
        doc.put("averageCost", avgCost);
        doc.put("currentPrice", currentPrice);
        doc.put("marketValue", quantity * currentPrice);
        doc.put("unrealizedPnL", quantity * (currentPrice - avgCost));
        doc.put("pnlPercent", ((currentPrice - avgCost) / avgCost) * 100.0);
        doc.put("sector", sector);
        doc.put("updatedAt", Instant.now().toString());
        return doc;
    }

    // ── Risk Metrics ────────────────────────────────────────────────────────

    /**
     * Build a risk metrics snapshot for a portfolio.
     *
     * @param metricsId   unique identifier
     * @param portfolioId owning portfolio
     * @param var95       Value-at-Risk at 95% confidence ($ amount)
     * @param var99       Value-at-Risk at 99% confidence ($ amount)
     * @param sharpeRatio Sharpe ratio (risk-adjusted return)
     * @param beta        portfolio beta vs benchmark
     * @param maxDrawdown maximum drawdown percentage
     * @param volatility  annualized volatility percentage
     */
    public static ObjectNode riskMetrics(String metricsId, String portfolioId,
            double var95, double var99,
            double sharpeRatio, double beta,
            double maxDrawdown, double volatility) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("id", metricsId);
        doc.put("metricsId", metricsId);
        doc.put("portfolioId", portfolioId);
        doc.put("valueAtRisk95", var95);
        doc.put("valueAtRisk99", var99);
        doc.put("sharpeRatio", sharpeRatio);
        doc.put("beta", beta);
        doc.put("maxDrawdown", maxDrawdown);
        doc.put("volatility", volatility);
        doc.put("treynorRatio", sharpeRatio * volatility / (beta != 0 ? beta : 1));
        doc.put("informationRatio", sharpeRatio * 0.7); // simplified
        doc.put("calculatedAt", Instant.now().toString());
        doc.put("methodology", "HISTORICAL_SIMULATION");
        doc.put("lookbackDays", 252);
        return doc;
    }

    // ── Market Data ─────────────────────────────────────────────────────────

    /**
     * Build a market data point.
     */
    public static ObjectNode marketData(String symbol, double price, double change,
            double changePercent, double dayHigh,
            double dayLow, long volume) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("id", symbol);
        doc.put("symbol", symbol);
        doc.put("price", price);
        doc.put("change", change);
        doc.put("changePercent", changePercent);
        doc.put("dayHigh", dayHigh);
        doc.put("dayLow", dayLow);
        doc.put("volume", volume);
        doc.put("updatedAt", Instant.now().toString());
        return doc;
    }

    // ── Alert ───────────────────────────────────────────────────────────────

    /**
     * Build a risk alert.
     *
     * @param alertId     unique identifier
     * @param portfolioId affected portfolio
     * @param severity    CRITICAL, HIGH, MEDIUM, LOW
     * @param type        VAR_BREACH, CONCENTRATION, LIQUIDITY, DRAWDOWN, REGULATORY
     * @param message     human-readable description
     */
    public static ObjectNode alert(String alertId, String portfolioId,
            String severity, String type, String message) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("id", alertId);
        doc.put("alertId", alertId);
        doc.put("portfolioId", portfolioId);
        doc.put("severity", severity);
        doc.put("type", type);
        doc.put("message", message);
        doc.put("status", "OPEN");
        doc.put("createdAt", Instant.now().toString());
        doc.put("acknowledged", false);
        return doc;
    }

    // ── Sector Exposure ─────────────────────────────────────────────────────

    /**
     * Build sector exposure details for portfolio allocation charts.
     */
    public static ObjectNode sectorExposure(String portfolioId,
            Map<String, Double> sectorWeights) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("id", portfolioId + "-sectors");
        doc.put("portfolioId", portfolioId);
        ObjectNode sectors = doc.putObject("sectors");
        sectorWeights.forEach(sectors::put);
        doc.put("updatedAt", Instant.now().toString());
        return doc;
    }
}
