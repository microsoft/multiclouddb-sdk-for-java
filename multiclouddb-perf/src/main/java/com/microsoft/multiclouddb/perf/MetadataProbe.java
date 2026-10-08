// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosDiagnostics;
import com.azure.cosmos.models.ThroughputProperties;
import com.azure.cosmos.models.ThroughputResponse;
import com.microsoft.multiclouddb.e2e.ConfigLoader;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.BillingModeSummary;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.OnDemandThroughput;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputDescription;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

import java.util.Locale;
import java.util.Set;

/**
 * Read-only provider metadata probe.
 */
final class MetadataProbe {

    private MetadataProbe() {
    }

    record Meta(String region, String provisionedCapacity, String billingMode,
                Double sharedCapacityLimit, Double readCapacityLimit, Double writeCapacityLimit) {
    }

    static Meta probe(String providerId, ConfigLoader.AppConfig cfg,
                      String database, String collection,
                      String cfgRegion, String cfgProvisioned) {
        String region = blankToNull(cfgRegion);
        String provisioned = blankToNull(cfgProvisioned);
        String billingMode = "unknown";
        Double sharedCapacity = null;
        Double readCapacity = null;
        Double writeCapacity = null;
        try {
            Meta m = switch (providerId) {
                case "cosmos" -> probeCosmos(cfg, database, collection);
                case "dynamo" -> probeDynamo(cfg, database, collection, cfgRegion, cfgProvisioned);
                default -> new Meta(region, provisioned, "unknown", null, null, null);
            };
            if (m.region() != null) {
                region = m.region();
            }
            if (m.provisionedCapacity() != null) {
                provisioned = m.provisionedCapacity();
            }
            if (m.billingMode() != null && !m.billingMode().isBlank()) {
                billingMode = m.billingMode();
            }
            if (m.sharedCapacityLimit() != null) {
                sharedCapacity = m.sharedCapacityLimit();
            }
            if (m.readCapacityLimit() != null) {
                readCapacity = m.readCapacityLimit();
            }
            if (m.writeCapacityLimit() != null) {
                writeCapacity = m.writeCapacityLimit();
            }
        } catch (Throwable t) {
            System.out.printf(Locale.ROOT,
                    "!! metadata probe skipped for %s (%s); using config values%n",
                    providerId, t.getClass().getSimpleName());
        }
        return new Meta(orUnknown(region), provisioned == null ? "" : provisioned,
                billingMode == null || billingMode.isBlank() ? "unknown" : billingMode,
                sharedCapacity, readCapacity, writeCapacity);
    }

    private static Meta probeCosmos(ConfigLoader.AppConfig cfg, String database, String collection) {
        String endpoint = cfg.get("multiclouddb.connection.endpoint", "");
        String key = cfg.get("multiclouddb.connection.key", "");
        if (endpoint.isBlank()) {
            return new Meta(null, null, "unknown", null, null, null);
        }
        CosmosClientBuilder builder = new CosmosClientBuilder().endpoint(endpoint).gatewayMode();
        if (!key.isBlank()) {
            builder.key(key);
        }
        String region = null;
        String provisioned = null;
        String billingMode = "unknown";
        Double capacityLimit = null;
        try (CosmosClient client = builder.buildClient()) {
            CosmosDatabase db = client.getDatabase(database);
            CosmosContainer container = db.getContainer(collection);
            try {
                ThroughputResponse tr = container.readThroughput();
                ThroughputInfo info = throughputInfo(tr.getProperties(), "container");
                provisioned = info.description();
                billingMode = info.billingMode();
                capacityLimit = info.capacityLimit();
                region = fromDiagnostics(tr.getDiagnostics());
            } catch (Throwable containerLevel) {
                try {
                    ThroughputResponse tr = db.readThroughput();
                    ThroughputInfo info = throughputInfo(tr.getProperties(), "database");
                    provisioned = info.description();
                    billingMode = info.billingMode();
                    capacityLimit = info.capacityLimit();
                    region = fromDiagnostics(tr.getDiagnostics());
                } catch (Throwable databaseLevel) {
                    provisioned = "serverless/shared (no dedicated RU/s visible)";
                    billingMode = "unknown";
                }
            }
            if (region == null) {
                try {
                    region = fromDiagnostics(container.read().getDiagnostics());
                } catch (Throwable ignore) {
                    // region stays unknown; not fatal
                }
            }
        }
        return new Meta(region, provisioned, billingMode, capacityLimit, null, null);
    }

    private static Meta probeDynamo(ConfigLoader.AppConfig cfg, String database, String collection,
                                    String cfgRegion, String cfgProvisioned) {
        String region = blankToNull(cfg.get("multiclouddb.connection.region", cfgRegion));
        String accessKey = cfg.get("multiclouddb.auth.accessKeyId", "");
        String secretKey = cfg.get("multiclouddb.auth.secretAccessKey", "");
        if (region == null) {
            return new Meta(null, blankToNull(cfgProvisioned), "unknown", null, null, null);
        }
        String table = database + "__" + collection;
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        try (DynamoDbClient ddb = builder.build()) {
            DescribeTableResponse response = ddb.describeTable(b -> b.tableName(table));
            TableDescription tableDescription = response.table();
            BillingModeSummary summary = tableDescription.billingModeSummary();
            ProvisionedThroughputDescription provisioned = tableDescription.provisionedThroughput();
            BillingMode billingMode = summary != null && summary.billingMode() != null
                    ? summary.billingMode()
                    : provisioned != null ? BillingMode.PROVISIONED : null;
            String billingModeText = billingMode != null ? billingMode.toString() : "unknown";

            Double provisionedRead = provisioned != null && provisioned.readCapacityUnits() != null
                    ? provisioned.readCapacityUnits().doubleValue() : null;
            Double provisionedWrite = provisioned != null && provisioned.writeCapacityUnits() != null
                    ? provisioned.writeCapacityUnits().doubleValue() : null;

            OnDemandThroughput onDemand = tableDescription.onDemandThroughput();
            Double maxRead = onDemand != null && onDemand.maxReadRequestUnits() != null
                    ? onDemand.maxReadRequestUnits().doubleValue() : null;
            Double maxWrite = onDemand != null && onDemand.maxWriteRequestUnits() != null
                    ? onDemand.maxWriteRequestUnits().doubleValue() : null;

            Double readCapacity = BillingMode.PROVISIONED.equals(billingMode) ? provisionedRead : maxRead;
            Double writeCapacity = BillingMode.PROVISIONED.equals(billingMode) ? provisionedWrite : maxWrite;
            return new Meta(region,
                    formatDynamoCapacity(billingModeText, provisionedRead, provisionedWrite, maxRead, maxWrite),
                    billingModeText,
                    null,
                    readCapacity,
                    writeCapacity);
        }
    }

    private record ThroughputInfo(String billingMode, Double capacityLimit, String description) {
    }

    private static ThroughputInfo throughputInfo(ThroughputProperties tp, String scope) {
        if (tp == null) {
            return new ThroughputInfo("unknown", null, null);
        }
        int autoscale = 0;
        int manual = 0;
        try {
            autoscale = tp.getAutoscaleMaxThroughput();
        } catch (Throwable ignore) {
            // not autoscale
        }
        try {
            manual = tp.getManualThroughput();
        } catch (Throwable ignore) {
            // not manual
        }
        if (autoscale > 0) {
            return new ThroughputInfo("autoscale", (double) autoscale,
                    autoscale + " RU/s (autoscale max, " + scope + ")");
        }
        if (manual > 0) {
            return new ThroughputInfo("manual", (double) manual,
                    manual + " RU/s (manual, " + scope + ")");
        }
        return new ThroughputInfo("unknown", null, "unknown throughput mode (" + scope + ")");
    }

    private static String formatDynamoCapacity(String billingMode, Double readCapacity, Double writeCapacity,
                                               Double maxRead, Double maxWrite) {
        if (BillingMode.PROVISIONED.toString().equals(billingMode)) {
            if (readCapacity != null && writeCapacity != null) {
                return String.format(Locale.ROOT, "PROVISIONED rcu=%.0f wcu=%.0f", readCapacity, writeCapacity);
            }
            return "PROVISIONED (capacity unknown)";
        }
        if (BillingMode.PAY_PER_REQUEST.toString().equals(billingMode)) {
            if (maxRead != null || maxWrite != null) {
                return String.format(Locale.ROOT, "PAY_PER_REQUEST maxRead=%s maxWrite=%s",
                        numOrUnknown(maxRead), numOrUnknown(maxWrite));
            }
            return "PAY_PER_REQUEST";
        }
        StringBuilder out = new StringBuilder("unknown");
        if (readCapacity != null || writeCapacity != null || maxRead != null || maxWrite != null) {
            out.append(" (");
            boolean first = true;
            if (readCapacity != null) {
                out.append("rcu=").append(numOrUnknown(readCapacity));
                first = false;
            }
            if (writeCapacity != null) {
                if (!first) out.append(' ');
                out.append("wcu=").append(numOrUnknown(writeCapacity));
                first = false;
            }
            if (maxRead != null) {
                if (!first) out.append(' ');
                out.append("maxRead=").append(numOrUnknown(maxRead));
                first = false;
            }
            if (maxWrite != null) {
                if (!first) out.append(' ');
                out.append("maxWrite=").append(numOrUnknown(maxWrite));
            }
            out.append(')');
        }
        return out.toString();
    }

    private static String numOrUnknown(Double value) {
        return value == null ? "unknown" : String.format(Locale.ROOT, "%.0f", value);
    }

    private static String fromDiagnostics(CosmosDiagnostics diagnostics) {
        if (diagnostics == null) {
            return null;
        }
        Set<String> regions = diagnostics.getContactedRegionNames();
        if (regions == null || regions.isEmpty()) {
            return null;
        }
        return String.join(",", regions);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
