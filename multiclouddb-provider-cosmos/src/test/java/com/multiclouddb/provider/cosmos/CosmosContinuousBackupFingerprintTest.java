// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the {@link CosmosConstants#CONTINUOUS_BACKUP_FINGERPRINTS} list so a
 * future refactor cannot accidentally drop or rename a fingerprint that
 * {@code CosmosProviderClient#maybeContinuousBackupRequired(...)} depends on.
 * <p>
 * Each row maps a known Cosmos error message to the canonical fingerprint
 * it must match; together the rows define the contract the remap helper
 * promises to callers.
 */
class CosmosContinuousBackupFingerprintTest {

    @Test
    void listIsNonEmptyAndAllLowercase() {
        assertFalse(CosmosConstants.CONTINUOUS_BACKUP_FINGERPRINTS.isEmpty(),
                "fingerprint list must not be empty — remap would never fire");
        for (String needle : CosmosConstants.CONTINUOUS_BACKUP_FINGERPRINTS) {
            assertEquals(needle, needle.toLowerCase(Locale.ROOT),
                    "needle '" + needle + "' must be pre-lowercased — remap compares against lower(msg)");
            assertFalse(needle.isBlank(), "needle must not be blank — would match every message");
        }
    }

    @Test
    void coversKnownCosmosErrorWordings() {
        // Sampled from real Cosmos error messages across SDK versions.
        // Updating wording? Update the constant first, then this list.
        String[] sampleMessages = new String[] {
                "AllVersionsAndDeletes change feed mode requires Continuous Backup to be enabled on the account",
                "ContinuousBackup must be enabled",
                "Point In Time Restore is not enabled",
                "PITR not configured on this account"
        };
        for (String raw : sampleMessages) {
            String lower = raw.toLowerCase(Locale.ROOT);
            boolean matched = CosmosConstants.CONTINUOUS_BACKUP_FINGERPRINTS.stream()
                    .anyMatch(lower::contains);
            assertTrue(matched, "sample '" + raw + "' must match at least one fingerprint");
        }
    }

    @Test
    void unrelatedErrorsDoNotMatch() {
        String[] unrelated = new String[] {
                "Request rate is large",
                "Resource Not Found",
                "Partition key kind mismatch"
        };
        for (String raw : unrelated) {
            String lower = raw.toLowerCase(Locale.ROOT);
            boolean matched = CosmosConstants.CONTINUOUS_BACKUP_FINGERPRINTS.stream()
                    .anyMatch(lower::contains);
            assertFalse(matched, "unrelated '" + raw + "' must not match continuous-backup fingerprints");
        }
    }
}