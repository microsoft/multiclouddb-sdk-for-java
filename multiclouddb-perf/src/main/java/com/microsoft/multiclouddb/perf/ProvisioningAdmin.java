// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.ThroughputProperties;
import com.azure.cosmos.models.ThroughputResponse;
import com.microsoft.multiclouddb.e2e.ConfigLoader;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputDescription;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Opt-in, best-effort provisioning admin used only when the operator passes the
 * corresponding CLI flags.
 */
final class ProvisioningAdmin {

    private ProvisioningAdmin() {
    }

    /** Sets Cosmos throughput while preserving the resource's current manual/autoscale mode. */
    static void ensureCosmosThroughput(ConfigLoader.AppConfig cfg, String database,
                                       String collection, int targetRu) {
        String endpoint = cfg.get("multiclouddb.connection.endpoint", "");
        String key = cfg.get("multiclouddb.connection.key", "");
        if (endpoint.isBlank()) {
            System.out.println("!! cosmos throughput admin skipped — no endpoint in config");
            return;
        }
        CosmosClientBuilder builder = new CosmosClientBuilder().endpoint(endpoint).gatewayMode();
        if (!key.isBlank()) {
            builder.key(key);
        }
        try (CosmosClient client = builder.buildClient()) {
            CosmosDatabase db = client.getDatabase(database);
            CosmosContainer container = db.getContainer(collection);
            ThroughputResponse before;
            try {
                before = container.readThroughput();
            } catch (Throwable containerLevel) {
                before = db.readThroughput();
                ThroughputProperties target = targetThroughput(before, targetRu);
                ThroughputResponse after = db.replaceThroughput(target);
                System.out.printf(Locale.ROOT,
                        "-- cosmos throughput: database %d -> %d RU/s (%s, shared)%n",
                        throughput(before), throughput(after), throughputMode(after));
                return;
            }
            ThroughputProperties target = targetThroughput(before, targetRu);
            ThroughputResponse after = container.replaceThroughput(target);
            System.out.printf(Locale.ROOT,
                    "-- cosmos throughput: container %d -> %d RU/s (%s); "
                            + "partition split may take minutes%n",
                    throughput(before), throughput(after), throughputMode(after));
        } catch (Throwable t) {
            System.out.printf(Locale.ROOT,
                    "!! cosmos throughput admin failed (%s: %s) — leaving provisioning unchanged%n",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    static void ensureDynamoProvisionedCapacity(ConfigLoader.AppConfig cfg, String database,
                                                String collection, int targetRcu, int targetWcu) {
        String table = database + "__" + collection;
        try (DynamoDbClient ddb = buildDynamoClient(cfg)) {
            if (ddb == null) {
                return;
            }
            DescribeTableResponse desc = ddb.describeTable(b -> b.tableName(table));
            ProvisionedThroughputDescription current = desc.table().provisionedThroughput();
            BillingMode currentMode = desc.table().billingModeSummary() != null
                    && desc.table().billingModeSummary().billingMode() != null
                    ? desc.table().billingModeSummary().billingMode()
                    : current != null ? BillingMode.PROVISIONED : null;
            Long currentRcu = current != null ? current.readCapacityUnits() : null;
            Long currentWcu = current != null ? current.writeCapacityUnits() : null;
            boolean alreadyProvisioned = BillingMode.PROVISIONED.equals(currentMode)
                    && Long.valueOf(targetRcu).equals(currentRcu)
                    && Long.valueOf(targetWcu).equals(currentWcu);
            if (alreadyProvisioned) {
                System.out.printf(Locale.ROOT,
                        "-- dynamo capacity already PROVISIONED on %s (rcu=%d wcu=%d)%n",
                        table, targetRcu, targetWcu);
                return;
            }
            ddb.updateTable(b -> b.tableName(table)
                    .billingMode(BillingMode.PROVISIONED)
                    .provisionedThroughput(ProvisionedThroughput.builder()
                            .readCapacityUnits((long) targetRcu)
                            .writeCapacityUnits((long) targetWcu)
                            .build()));
            System.out.printf(Locale.ROOT,
                    "-- dynamo capacity: %s -> PROVISIONED rcu=%d wcu=%d on %s ...%n",
                    currentMode != null ? currentMode : "unknown", targetRcu, targetWcu, table);
            waitActive(ddb, table, "capacity update");
        } catch (Throwable t) {
            System.out.printf(Locale.ROOT,
                    "!! dynamo capacity admin failed (%s: %s) — leaving provisioning unchanged%n",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /** Enables a {@code NEW_AND_OLD_IMAGES} stream on the Dynamo table and waits for ACTIVE. */
    static void ensureDynamoStreams(ConfigLoader.AppConfig cfg, String database, String collection) {
        String table = database + "__" + collection;
        try (DynamoDbClient ddb = buildDynamoClient(cfg)) {
            if (ddb == null) {
                return;
            }
            DescribeTableResponse desc = ddb.describeTable(b -> b.tableName(table));
            StreamSpecification spec = desc.table().streamSpecification();
            boolean enabled = spec != null && Boolean.TRUE.equals(spec.streamEnabled())
                    && spec.streamViewType() == StreamViewType.NEW_AND_OLD_IMAGES;
            if (enabled) {
                System.out.printf(Locale.ROOT,
                        "-- dynamo streams already enabled on %s (NEW_AND_OLD_IMAGES)%n", table);
                return;
            }
            ddb.updateTable(b -> b.tableName(table).streamSpecification(
                    StreamSpecification.builder()
                            .streamEnabled(true)
                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                            .build()));
            System.out.printf(Locale.ROOT,
                    "-- dynamo streams: enabling NEW_AND_OLD_IMAGES on %s ...%n", table);
            waitActive(ddb, table, "stream update");
        } catch (Throwable t) {
            System.out.printf(Locale.ROOT,
                    "!! dynamo streams admin failed (%s: %s) — change feed will record a skip row%n",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    private static ThroughputProperties targetThroughput(ThroughputResponse current, int targetRu) {
        return "autoscale max".equals(throughputMode(current))
                ? ThroughputProperties.createAutoscaledThroughput(targetRu)
                : ThroughputProperties.createManualThroughput(targetRu);
    }

    private static int throughput(ThroughputResponse r) {
        try {
            int autoscale = r.getProperties().getAutoscaleMaxThroughput();
            if (autoscale > 0) {
                return autoscale;
            }
            return r.getProperties().getManualThroughput();
        } catch (Throwable ignore) {
            return -1;
        }
    }

    private static String throughputMode(ThroughputResponse r) {
        try {
            return r.getProperties().getAutoscaleMaxThroughput() > 0 ? "autoscale max" : "manual";
        } catch (Throwable ignore) {
            return "unknown";
        }
    }

    private static DynamoDbClient buildDynamoClient(ConfigLoader.AppConfig cfg) {
        String region = cfg.get("multiclouddb.connection.region", cfg.get("multiclouddb.region", ""));
        String accessKey = cfg.get("multiclouddb.auth.accessKeyId", "");
        String secretKey = cfg.get("multiclouddb.auth.secretAccessKey", "");
        if (region.isBlank()) {
            System.out.println("!! dynamo admin skipped — no region in config");
            return null;
        }
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }

    private static void waitActive(DynamoDbClient ddb, String table, String reason) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            DescribeTableResponse d = ddb.describeTable(b -> b.tableName(table));
            if (d.table().tableStatus() == TableStatus.ACTIVE) {
                System.out.printf(Locale.ROOT, "-- dynamo %s active on %s%n", reason, table);
                return;
            }
        }
        System.out.printf(Locale.ROOT,
                "!! dynamo %s on %s did not reach ACTIVE within 5 min — continuing anyway%n",
                reason, table);
    }
}
