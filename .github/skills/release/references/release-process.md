# Release Process Reference

This document describes the release conventions for the Multicloud DB SDK for Java.

## Publishable Modules

| Module | Artifact ID | Description |
|--------|-------------|-------------|
| API | `multiclouddb-api` | Portable contracts, SPI, query model |
| Cosmos | `multiclouddb-provider-cosmos` | Azure Cosmos DB adapter |
| DynamoDB | `multiclouddb-provider-dynamo` | Amazon DynamoDB adapter |
| Spanner | `multiclouddb-provider-spanner` | Google Cloud Spanner adapter |

## Dependency Order

```
multiclouddb-api  ← must be released first if API changed
    ↑
    ├── multiclouddb-provider-cosmos   ← independent of each other
    ├── multiclouddb-provider-dynamo   ← independent of each other
    └── multiclouddb-provider-spanner  ← independent of each other
```

Providers depend on a released version of `multiclouddb-api`. If you change the
API, release it first, update the `multiclouddb-api.version` property in the root
`pom.xml`, then release the providers.

## Tag Format

```
multiclouddb-<module-name>-v<version>
```

| Pattern | Example | Use case |
|---------|---------|----------|
| Stable | `multiclouddb-api-v1.0.0` | GA release |
| Beta | `multiclouddb-provider-cosmos-v0.2.0-beta.1` | Pre-release |

Tags that will NOT trigger the release pipeline:
- `v1.0.0` — missing module prefix
- `multiclouddb-conformance-v1.0.0` — not a publishable module
- `multiclouddb-api-v1.0.0-rc1` — release candidates not supported

## Version Management

| Location | Purpose |
|----------|---------|
| Root `pom.xml` → `<multiclouddb-api.version>` | API version used by all modules |
| Root `pom.xml` → `<multiclouddb-provider-*.version>` | Each provider's version |
| Module `pom.xml` → `<version>` | References property from root |
| `<dependencyManagement>` | Uses properties for inter-module deps |

### Bumping a Version

1. Create a release branch from `main`:
   ```bash
   git checkout -b release/<version>
   ```
2. Update the property in root `pom.xml` (e.g., `multiclouddb-api.version`)
3. The module POM picks it up automatically via `${multiclouddb-api.version}`
4. Update changelogs: stamp `[Unreleased]` with version and date, then add a new `## [Unreleased]` section with a blank line above the released section
5. Commit and push to origin (fork)
6. Create a PR against `upstream/main` — the release PR
7. After PR merge, create and push release tags to upstream

### Versioning Policy (Semantic Versioning)

- **Major** (`X.0.0`) — breaking API changes
- **Minor** (`0.Y.0`) — new features, backward-compatible
- **Patch** (`0.0.Z`) — bug fixes, backward-compatible
- **Beta** (`X.Y.Z-beta.N`) — pre-release, may have breaking changes

## Changelog Format

Each module maintains its own `CHANGELOG.md` in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format:

```markdown
## [Unreleased]

### Added
- New feature description

## [0.1.0-beta.1] — 2026-03-30

Initial release.

### Added
- Feature list...
```

The `[Unreleased]` section is renamed to the version being released with the
release date appended.

## Release Workflow

### Step 1: Prepare release PR

All version and changelog changes go through a PR against `upstream/main`:

1. Create a `release/<version>` branch on your fork
2. Update POM version properties in root `pom.xml`
3. Stamp changelogs with the release version and date
4. Push to fork and create a PR against `upstream/main`
5. Wait for CI to pass and PR to be reviewed and merged

**Never push version/changelog changes directly to `main`.**

### Step 2: Create release tags

After the release PR is merged:

1. Sync local main with upstream: `git fetch upstream && git rebase upstream/main`
2. Create and push tags **one at a time** to upstream:
   ```bash
   git tag -a "<module>-v<version>" -m "Release <module> v<version>"
   git push upstream "<module>-v<version>"
   ```
3. Wait for the workflow run to appear before pushing the next tag

**Critical:** Push each tag individually. Batched tag pushes silently fail to
trigger GitHub Actions workflows.

### Step 3: Release pipeline

The `release.yml` workflow is triggered by pushing a matching tag (one tag per
`git push`) or via `workflow_dispatch`. It:

1. Parses module name + version from the tag
2. Runs unit tests
3. Verifies POM version matches tag
4. Verifies sibling dependency versions are valid releases
5. Builds reactor, deploys only the target module
6. Creates GitHub Release with JARs

The `production` environment has a manual approval gate.

### Step 4: Azure Partner Drops handoff (manual, outside this repo)

The `release.yml` workflow stops after creating the GitHub Release. Publishing
to **Maven Central** happens through the Azure SDK **Partner Release
Pipeline** in the `azure-sdk/internal` ADO project — see the "Azure Partner
Release Pipeline" section below.

## Azure Partner Release Pipeline

> **Microsoft-internal.** Canonical wiki:
> <https://aka.ms/azsdk/partner-release-pipeline>

### What this pipeline does

The Azure SDK Partner Release Pipeline is the ingestion point for partner-team
SDKs (like this one) into Maven Central. For Java the
[`java - partner-release`](https://dev.azure.com/azure-sdk/internal/_build?definitionId=1809&_a=summary)
pipeline:

1. Reads the unsigned drop from the `azuresdkpartnerdrops` storage account
2. Signs the jars / POM
3. Stages to OSSRH (`oss.sonatype.org`) under the `azuresdk` account
4. Releases from OSSRH to Maven Central
   (or only stages, if `StageOnly=true`)

This repo does **not** sign artifacts and does **not** push to Sonatype. Both
are owned by the partner pipeline.

### What this repo produces today

The `publish` job in `release.yml`:

- Builds and `mvn deploy`s the target module to a **local `file://` staging
  directory** on the GitHub Actions runner
- Uploads that `staging/` tree as a GitHub Actions artifact named
  `maven-staging-<MODULE>-<VERSION>` (90-day retention) — this contains the
  `.pom` plus the three jars in standard Maven layout
- Attaches `<module>-<version>.jar`, `<module>-<version>-sources.jar`, and
  `<module>-<version>-javadoc.jar` to the GitHub Release (the `.pom` is
  currently NOT attached to the release — pull it from the staging artifact)

### Prerequisites

- Membership in the **Azure SDK Partners** security group
  (request via <https://aka.ms/azsdk/join/azuresdkpartners>; manager approval
  required). Grants both blob upload and ADO pipeline run rights.
- First ever release of a new Maven coordinate: chat with an Azure SDK
  architect first (per the wiki), to confirm Azure SDK Design Guidelines.
- First release end-to-end through this pipeline: it is recommended to
  coordinate via the
  [Partner Release Pipelines](https://teams.microsoft.com/l/channel/19%3ac89f67e32f5941d78a3710a692cf7717%40thread.skype/Partner%2520Release%2520Pipelines?groupId=3e17dcb0-4257-4a30-b843-77f47f1d4121&tenantId=72f988bf-86f1-41af-91ab-2d7cd011db47)
  Teams channel.

### Manual handoff procedure (per release)

For each module released by Phase 5:

1. **Gather the four required files** (all **unsigned**):

   | File | Where to get it |
   |---|---|
   | `<artifactId>-<version>.pom` | From `maven-staging-<MODULE>-<VERSION>` GitHub Actions artifact, or rename a copy of `pom.xml` from the tagged commit |
   | `<artifactId>-<version>.jar` | GitHub Release (or staging artifact) |
   | `<artifactId>-<version>-sources.jar` | GitHub Release (or staging artifact) |
   | `<artifactId>-<version>-javadoc.jar` | GitHub Release (or staging artifact) |

   `<artifactId>` and `<version>` follow the standard Maven naming Maven
   produces for the module (e.g. `multiclouddb-api-0.1.0-beta.1.pom`).

2. **Upload to the partner blob container** using your own credentials
   (Azure Portal, [`azcopy`](https://learn.microsoft.com/en-us/azure/storage/common/storage-use-azcopy-v10),
   or [Azure Storage Explorer](https://azure.microsoft.com/en-us/products/storage/storage-explorer/)):

   - Container: `https://azuresdkpartnerdrops.blob.core.windows.net/drops`
   - Path: `<team>/java/<version>/`
     (e.g. `adp/java/1.0.3/`)
   - Put **only** the files you want published in that folder — the pipeline
     publishes _everything_ at that path.
   - If you don't know the `<team>` prefix to use for this SDK yet, ask in
     the Partner Release Pipelines Teams channel before uploading.

3. **Run the [`java - partner-release`](https://dev.azure.com/azure-sdk/internal/_build?definitionId=1809&_a=summary)
   pipeline** in `azure-sdk/internal`. Click **Run pipeline** and set:

   | Variable | Value |
   |---|---|
   | `BlobPath` | The relative path you uploaded to (e.g. `adp/java/1.0.3`) |
   | `StageOnly` | `false` for a normal release. `true` to stage in OSSRH and inspect/promote manually — see the wiki for how to capture the staging id |

4. **Verify publish to Maven Central** once the pipeline finishes:
   `https://repo.maven.apache.org/maven2/<groupId-as-path>/<artifactId>/<version>/`

   Note that Maven Central propagation can take a while; OSSRH → Central
   indexing is typically a few minutes but can be longer under load.

### Troubleshooting the partner pipeline

Maven Central / OSSRH is occasionally unhealthy under load and the pipeline
can fail with messages like:

- `SEVERE: A message body reader for ... StagingProfileRepositoryDTO ... was not found`
- `[WARNING] TIMEOUT after 300.5 s`

When that happens, check <https://status.maven.org/> ("Staging Operation
Duration - oss.sonatype.org" graph). Typical average is ~60s; under high
load it can be ~600s. The fix is to retry the partner pipeline run, with
reasonable spacing between retries.

## Troubleshooting

### "Version mismatch" error
Update the version property in root `pom.xml`, merge to `main`, delete old tag, re-tag.

### "Invalid sibling dependency version" error
Release the dependency first (usually `multiclouddb-api`), update version property, retry.
Only beta (`X.Y.Z-beta.N`) and GA (`X.Y.Z`) versions are valid for release.

### Pipeline didn't trigger
- Verify tag matches `multiclouddb-*-v<semver>` or `multiclouddb-*-v<semver>-beta.<N>`
- Check tag points to a commit on `main`
- **Push tags one at a time.** Pushing multiple tags in a single `git push` command
  may cause GitHub to silently skip workflow triggers. Always push each tag with a
  separate `git push upstream <tag>` command.
- The workflow also supports `workflow_dispatch` as a fallback — manually trigger
  from the Actions tab with the tag name as input.

### Approval is stuck
Go to **Actions → Release → (your run)** and check if the publish job shows
"Waiting for review". Ensure you are listed as a reviewer in
**Settings → Environments → production**.
