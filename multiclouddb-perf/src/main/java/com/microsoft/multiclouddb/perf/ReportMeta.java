// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

/**
 * Header metadata shared by the Markdown and HTML report renderers.
 *
 * @param baseline the provider treated as the migration <em>source</em> for the thread-parity
 *                 analysis (goal 3). May be {@code null}; renderers resolve a default.
 */
record ReportMeta(String title, String generatedUtc, String sourceLabel, String baseline,
                  double invalidThrottleRate) {
}
