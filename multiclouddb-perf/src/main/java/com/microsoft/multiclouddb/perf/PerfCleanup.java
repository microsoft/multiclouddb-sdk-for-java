// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import com.microsoft.multiclouddb.e2e.ConfigLoader;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deletes every document the perf harness created in the configured container, to stop
 * ongoing live-account storage cost (e.g. after an interrupted run left items behind).
 *
 * <p>Only rows carrying the {@link ScenarioRunner#MARKER_TAG} marker are removed — data not
 * written by the harness is never touched. The exact key is reconstructed from the
 * {@link ScenarioRunner#MARKER_PK}/{@link ScenarioRunner#MARKER_SK} fields the harness stamps on
 * every doc, so deletes are correct across providers regardless of partition layout.
 *
 * <p>Invoked via {@code multiclouddb-perf/perf.sh cleanup} (the {@link PerfMain} {@code cleanup} command).
 * Config selection is identical to
 * {@link ScenarioRunner} (the {@code multiclouddb.config} properties file). Set
 * {@code -Dperf.dryRun=true} to only count what would be deleted.
 *
 * <p><b>Note:</b> this clears items (storage). It does not deprovision the container's
 * throughput — the portable API exposes no container-drop. To fully stop cost, delete the
 * perf database/container in the provider console.
 */
public final class PerfCleanup {

    private PerfCleanup() {}

    public static void main(String[] args) throws Exception {
        boolean dryRun = Boolean.parseBoolean(System.getProperty("perf.dryRun", "false"));
        int pageSize   = intProp("perf.pageSize", 1000);

        ConfigLoader.AppConfig cfg = ConfigLoader.load(configPath());
        String provider   = cfg.sdk().provider().id();
        String database   = cfg.get("multiclouddb.database", "perfdb");
        String collection = cfg.get("multiclouddb.collection", "perf");
        ResourceAddress address = new ResourceAddress(database, collection);

        System.out.printf(Locale.ROOT,
                "== perf cleanup ==%n provider=%s db=%s coll=%s dryRun=%b%n",
                provider, database, collection, dryRun);

        try (MulticloudDbClient client = MulticloudDbClientFactory.create(cfg.sdk())) {
            client.ensureDatabase(database);
            client.ensureContainer(address);

            long scanned = 0, deleted = 0, failed = 0;
            String continuation = null;
            do {
                QueryRequest.Builder qb = QueryRequest.builder().maxPageSize(pageSize);
                if (continuation != null) {
                    qb.continuationToken(continuation);
                }
                QueryPage page = client.query(address, qb.build());
                for (Map<String, Object> item : page.items()) {
                    scanned++;
                    Object tag = item.get(ScenarioRunner.MARKER_TAG);
                    Object pk  = item.get(ScenarioRunner.MARKER_PK);
                    if (tag == null || pk == null) {
                        continue;   // not a perf-created doc — leave it alone
                    }
                    Object sk = item.get(ScenarioRunner.MARKER_SK);
                    MulticloudDbKey key = (sk == null || sk.toString().isEmpty())
                            ? MulticloudDbKey.of(pk.toString())
                            : MulticloudDbKey.of(pk.toString(), sk.toString());
                    if (dryRun) {
                        deleted++;
                        continue;
                    }
                    try {
                        client.delete(address, key);
                        deleted++;
                    } catch (MulticloudDbException e) {
                        failed++;
                        System.out.printf(Locale.ROOT, "   ! failed to delete pk=%s sk=%s (%s)%n",
                                pk, sk, e.error().category().getValue());
                    }
                }
                continuation = page.continuationToken();
            } while (continuation != null);

            System.out.printf(Locale.ROOT,
                    "== done == scanned=%d %s=%d failed=%d%n",
                    scanned, dryRun ? "would-delete" : "deleted", deleted, failed);
        }
    }

    static String configPath() {
        return System.getProperty("multiclouddb.config", "cosmos.properties");
    }

    private static int intProp(String key, int def) {
        try {
            String v = System.getProperty(key);
            return (v == null || v.isBlank()) ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
