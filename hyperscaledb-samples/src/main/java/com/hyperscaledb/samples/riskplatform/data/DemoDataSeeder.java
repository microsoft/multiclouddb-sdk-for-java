// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.samples.riskplatform.data;

import com.hyperscaledb.api.HyperscaleDbKey;
import com.hyperscaledb.samples.riskplatform.model.Models;
import com.hyperscaledb.samples.riskplatform.tenant.TenantManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Seeds realistic demo data for the multi-tenant risk analysis platform.
 * <p>
 * Creates three tenant firms with portfolios, positions, risk metrics,
 * market data, and alerts — enough for an impressive executive demo.
 */
public class DemoDataSeeder {

        private final TenantManager tenantManager;

        public DemoDataSeeder(TenantManager tenantManager) {
                this.tenantManager = tenantManager;
        }

        /**
         * Seed all demo data. Idempotent — overwrites existing data.
         */
        public void seedAll() {
                System.out.println("  Seeding demo data...");

                seedTenants();
                seedAcmeCapitalData();
                seedVanguardPartnersData();
                seedSummitWealthData();
                seedMarketData();

                System.out.println("  Demo data seeded successfully.");
        }

        // ── Tenants ─────────────────────────────────────────────────────────────

        private void seedTenants() {
                tenantManager.createTenant("acme-capital",
                                "Acme Capital Management", "ENTERPRISE", "Hedge Fund");
                tenantManager.createTenant("vanguard-partners",
                                "Vanguard Global Partners", "ENTERPRISE", "Investment Bank");
                tenantManager.createTenant("summit-wealth",
                                "Summit Wealth Advisors", "PROFESSIONAL", "Wealth Management");
        }

        // ── Acme Capital: Large hedge fund with aggressive equity strategies ────

        private void seedAcmeCapitalData() {
                String t = "acme-capital";

                // Portfolios
                putPortfolio(t, "acme-eq-alpha", "Alpha Equity Fund", "EQUITY", "USD", "S&P 500");
                putPortfolio(t, "acme-macro", "Global Macro Strategy", "MULTI_ASSET", "USD", "MSCI World");
                putPortfolio(t, "acme-tech", "Tech Innovation Fund", "EQUITY", "USD", "NASDAQ 100");

                // Positions — Alpha Equity Fund
                putPosition(t, "acme-eq-1", "acme-eq-alpha", "AAPL", "EQUITY", 15000, 142.50, 189.84, "Technology");
                putPosition(t, "acme-eq-2", "acme-eq-alpha", "MSFT", "EQUITY", 12000, 285.30, 378.91, "Technology");
                putPosition(t, "acme-eq-3", "acme-eq-alpha", "NVDA", "EQUITY", 8000, 450.15, 875.28, "Technology");
                putPosition(t, "acme-eq-4", "acme-eq-alpha", "JPM", "EQUITY", 10000, 148.20, 195.47, "Financials");
                putPosition(t, "acme-eq-5", "acme-eq-alpha", "JNJ", "EQUITY", 7500, 162.40, 155.80, "Healthcare");
                putPosition(t, "acme-eq-6", "acme-eq-alpha", "AMZN", "EQUITY", 6000, 128.90, 186.51,
                                "Consumer Discretionary");

                // Positions — Global Macro
                putPosition(t, "acme-mc-1", "acme-macro", "GLD", "COMMODITY", 20000, 178.50, 214.30, "Commodities");
                putPosition(t, "acme-mc-2", "acme-macro", "TLT", "FIXED_INCOME", 15000, 98.20, 92.45, "Treasuries");
                putPosition(t, "acme-mc-3", "acme-macro", "EEM", "EQUITY", 25000, 39.80, 43.15, "Emerging Markets");
                putPosition(t, "acme-mc-4", "acme-macro", "UUP", "CURRENCY", 30000, 28.50, 27.85, "Currency");

                // Positions — Tech Innovation
                putPosition(t, "acme-tc-1", "acme-tech", "META", "EQUITY", 5000, 295.40, 505.75, "Technology");
                putPosition(t, "acme-tc-2", "acme-tech", "GOOGL", "EQUITY", 7000, 125.80, 174.20, "Technology");
                putPosition(t, "acme-tc-3", "acme-tech", "CRM", "EQUITY", 4000, 215.60, 298.70, "Technology");
                putPosition(t, "acme-tc-4", "acme-tech", "AVGO", "EQUITY", 2000, 890.50, 1356.40, "Technology");

                // Risk Metrics
                putRiskMetrics(t, "risk-acme-eq", "acme-eq-alpha", 485000, 725000, 1.82, 1.15, -12.4, 18.7);
                putRiskMetrics(t, "risk-acme-mc", "acme-macro", 320000, 480000, 0.95, 0.68, -8.2, 14.2);
                putRiskMetrics(t, "risk-acme-tc", "acme-tech", 612000, 918000, 2.15, 1.45, -18.6, 24.3);

                // Alerts
                putAlert(t, "alert-acme-1", "acme-tech", "HIGH", "VAR_BREACH",
                                "VaR 99% exceeded daily limit by 12.3% — Tech Innovation Fund exposure to semiconductor sector at 48%");
                putAlert(t, "alert-acme-2", "acme-eq-alpha", "MEDIUM", "CONCENTRATION",
                                "Technology sector weight at 62% exceeds 50% concentration limit");
                putAlert(t, "alert-acme-3", "acme-macro", "LOW", "LIQUIDITY",
                                "TLT position liquidity below 30-day average — monitor for large redemptions");
        }

        // ── Vanguard Partners: Investment bank with diversified funds ───────────

        private void seedVanguardPartnersData() {
                String t = "vanguard-partners";

                // Portfolios
                putPortfolio(t, "vgp-balanced", "Balanced Growth Fund", "MULTI_ASSET", "USD", "70/30 Benchmark");
                putPortfolio(t, "vgp-fi", "Fixed Income Plus", "FIXED_INCOME", "EUR", "Bloomberg Agg");
                putPortfolio(t, "vgp-esg", "Sustainable Leaders Fund", "EQUITY", "USD", "MSCI ESG Leaders");

                // Positions — Balanced Growth
                putPosition(t, "vgp-bg-1", "vgp-balanced", "VTI", "EQUITY", 40000, 205.60, 238.90, "US Equity Broad");
                putPosition(t, "vgp-bg-2", "vgp-balanced", "VXUS", "EQUITY", 25000, 52.10, 57.80, "Intl Equity");
                putPosition(t, "vgp-bg-3", "vgp-balanced", "BND", "FIXED_INCOME", 30000, 72.40, 70.15,
                                "US Bond Aggregate");
                putPosition(t, "vgp-bg-4", "vgp-balanced", "VNQ", "EQUITY", 10000, 82.30, 86.45, "Real Estate");

                // Positions — Fixed Income Plus
                putPosition(t, "vgp-fi-1", "vgp-fi", "BNDX", "FIXED_INCOME", 50000, 49.80, 48.95, "Intl Bond");
                putPosition(t, "vgp-fi-2", "vgp-fi", "LQD", "FIXED_INCOME", 35000, 108.50, 112.30, "Investment Grade");
                putPosition(t, "vgp-fi-3", "vgp-fi", "HYG", "FIXED_INCOME", 20000, 74.60, 77.85, "High Yield");
                putPosition(t, "vgp-fi-4", "vgp-fi", "TIP", "FIXED_INCOME", 25000, 108.90, 106.40, "TIPS");

                // Positions — Sustainable Leaders
                putPosition(t, "vgp-esg-1", "vgp-esg", "MSFT", "EQUITY", 8000, 310.40, 378.91, "Technology");
                putPosition(t, "vgp-esg-2", "vgp-esg", "NEE", "EQUITY", 12000, 72.50, 68.90, "Clean Energy");
                putPosition(t, "vgp-esg-3", "vgp-esg", "TSLA", "EQUITY", 3000, 185.30, 248.42, "EV/Clean Transport");
                putPosition(t, "vgp-esg-4", "vgp-esg", "ENPH", "EQUITY", 5000, 125.60, 118.45, "Solar");

                // Risk Metrics
                putRiskMetrics(t, "risk-vgp-bg", "vgp-balanced", 245000, 367000, 1.35, 0.82, -6.8, 11.5);
                putRiskMetrics(t, "risk-vgp-fi", "vgp-fi", 128000, 192000, 0.72, 0.25, -4.2, 6.8);
                putRiskMetrics(t, "risk-vgp-esg", "vgp-esg", 380000, 570000, 1.48, 1.08, -15.3, 21.2);

                // Alerts
                putAlert(t, "alert-vgp-1", "vgp-fi", "MEDIUM", "DRAWDOWN",
                                "Fixed Income Plus drawdown approaching -4.2% threshold — rising rate environment impacting duration");
                putAlert(t, "alert-vgp-2", "vgp-esg", "HIGH", "CONCENTRATION",
                                "Solar/clean energy exposure at 35% — ESG concentration exceeds diversification target");
        }

        // ── Summit Wealth: Wealth management with conservative strategies ──────

        private void seedSummitWealthData() {
                String t = "summit-wealth";

                // Portfolios
                putPortfolio(t, "swm-conserv", "Conservative Income", "MULTI_ASSET", "USD", "40/60 Benchmark");
                putPortfolio(t, "swm-growth", "Growth Allocation", "EQUITY", "USD", "S&P 500");

                // Positions — Conservative Income
                putPosition(t, "swm-ci-1", "swm-conserv", "AGG", "FIXED_INCOME", 60000, 99.50, 97.80,
                                "US Aggregate Bond");
                putPosition(t, "swm-ci-2", "swm-conserv", "VYM", "EQUITY", 20000, 108.50, 115.20, "Dividend Equity");
                putPosition(t, "swm-ci-3", "swm-conserv", "SCHD", "EQUITY", 15000, 72.30, 78.60, "Dividend Growth");
                putPosition(t, "swm-ci-4", "swm-conserv", "SHY", "FIXED_INCOME", 40000, 81.20, 81.85,
                                "Short-Term Treasury");

                // Positions — Growth Allocation
                putPosition(t, "swm-gr-1", "swm-growth", "QQQ", "EQUITY", 8000, 365.40, 485.20, "Nasdaq 100");
                putPosition(t, "swm-gr-2", "swm-growth", "VOO", "EQUITY", 12000, 412.60, 478.35, "S&P 500");
                putPosition(t, "swm-gr-3", "swm-growth", "ARKK", "EQUITY", 5000, 42.80, 48.90, "Innovation");
                putPosition(t, "swm-gr-4", "swm-growth", "SMH", "EQUITY", 4000, 198.50, 245.60, "Semiconductors");

                // Risk Metrics
                putRiskMetrics(t, "risk-swm-ci", "swm-conserv", 85000, 127000, 0.65, 0.35, -3.5, 5.8);
                putRiskMetrics(t, "risk-swm-gr", "swm-growth", 310000, 465000, 1.62, 1.12, -14.8, 19.4);

                // Alerts
                putAlert(t, "alert-swm-1", "swm-growth", "MEDIUM", "VAR_BREACH",
                                "Growth Allocation VaR 95% nearing daily limit — review semiconductor exposure");
        }

        // ── Shared Market Data (stored in admin database) ──────────────────────

        private void seedMarketData() {
                putMarket("AAPL", 189.84, 2.45, 1.31, 191.20, 187.50, 62_400_000);
                putMarket("MSFT", 378.91, -1.23, -0.32, 381.50, 376.20, 24_800_000);
                putMarket("NVDA", 875.28, 15.63, 1.82, 882.40, 858.90, 48_200_000);
                putMarket("META", 505.75, 8.42, 1.69, 510.80, 498.30, 18_500_000);
                putMarket("GOOGL", 174.20, -0.85, -0.49, 176.40, 173.10, 22_100_000);
                putMarket("AMZN", 186.51, 3.21, 1.75, 188.90, 184.20, 35_700_000);
                putMarket("JPM", 195.47, 1.85, 0.96, 197.10, 193.80, 12_300_000);
                putMarket("TSLA", 248.42, -5.18, -2.04, 255.60, 246.30, 82_100_000);
                putMarket("VTI", 238.90, 1.42, 0.60, 240.10, 237.50, 4_200_000);
                putMarket("BND", 70.15, -0.22, -0.31, 70.45, 69.90, 8_100_000);
                putMarket("GLD", 214.30, 3.85, 1.83, 215.60, 210.40, 11_500_000);
                putMarket("QQQ", 485.20, 4.68, 0.97, 488.30, 481.40, 38_900_000);
        }

        // ── Helper methods ──────────────────────────────────────────────────────
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

        private void putPortfolio(String tenant, String id, String name, String type,
                        String currency, String benchmark) {
                ObjectNode doc = Models.portfolio(id, name, type, currency, benchmark);
                tenantManager.upsert(tenant, "portfolios", HyperscaleDbKey.of(id, id), MAPPER.convertValue(doc, MAP_TYPE));
        }

        private void putPosition(String tenant, String id, String portfolioId,
                        String symbol, String assetClass, double qty,
                        double avgCost, double price, String sector) {
                ObjectNode doc = Models.position(id, portfolioId, symbol, assetClass,
                                qty, avgCost, price, sector);
                tenantManager.upsert(tenant, "positions", HyperscaleDbKey.of(portfolioId, id), MAPPER.convertValue(doc, MAP_TYPE));
        }

        private void putRiskMetrics(String tenant, String id, String portfolioId,
                        double var95, double var99, double sharpe,
                        double beta, double maxDD, double vol) {
                ObjectNode doc = Models.riskMetrics(id, portfolioId, var95, var99,
                                sharpe, beta, maxDD, vol);
                tenantManager.upsert(tenant, "risk_metrics", HyperscaleDbKey.of(portfolioId, id), MAPPER.convertValue(doc, MAP_TYPE));
        }

        private void putAlert(String tenant, String id, String portfolioId,
                        String severity, String type, String message) {
                ObjectNode doc = Models.alert(id, portfolioId, severity, type, message);
                tenantManager.upsert(tenant, "alerts", HyperscaleDbKey.of(portfolioId, id), MAPPER.convertValue(doc, MAP_TYPE));
        }

        private void putMarket(String symbol, double price, double change,
                        double changePct, double high, double low, long volume) {
                ObjectNode doc = Models.marketData(symbol, price, change, changePct, high, low, volume);
                var addr = tenantManager.addressFor("_shared", "market_data");
                tenantManager.getClient().upsert(addr, HyperscaleDbKey.of(symbol, symbol), MAPPER.convertValue(doc, MAP_TYPE));
        }
}
