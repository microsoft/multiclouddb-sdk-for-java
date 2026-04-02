# Release Process Reference

This document describes the release conventions for the Hyperscale DB SDK for Java.

## Publishable Modules

| Module | Artifact ID | Description |
|--------|-------------|-------------|
| API | `hyperscaledb-api` | Portable contracts, SPI, query model |
| Cosmos | `hyperscaledb-provider-cosmos` | Azure Cosmos DB adapter |
| DynamoDB | `hyperscaledb-provider-dynamo` | Amazon DynamoDB adapter |
| Spanner | `hyperscaledb-provider-spanner` | Google Cloud Spanner adapter |

## Dependency Order

```
hyperscaledb-api  ← must be released first if API changed
    ↑
    ├── hyperscaledb-provider-cosmos   ← independent of each other
    ├── hyperscaledb-provider-dynamo   ← independent of each other
    └── hyperscaledb-provider-spanner  ← independent of each other
```

Providers depend on a released version of `hyperscaledb-api`. If you change the
API, release it first, update the `hyperscaledb-api.version` property in the root
`pom.xml`, then release the providers.

## Tag Format

```
hyperscaledb-<module-name>-v<version>
```

| Pattern | Example | Use case |
|---------|---------|----------|
| Stable | `hyperscaledb-api-v1.0.0` | GA release |
| Beta | `hyperscaledb-provider-cosmos-v0.2.0-beta.1` | Pre-release |

Tags that will NOT trigger the release pipeline:
- `v1.0.0` — missing module prefix
- `hyperscaledb-conformance-v1.0.0` — not a publishable module
- `hyperscaledb-api-v1.0.0-rc1` — release candidates not supported

## Version Management

| Location | Purpose |
|----------|---------|
| Root `pom.xml` → `<hyperscaledb-api.version>` | API version used by all modules |
| Root `pom.xml` → `<hyperscaledb-provider-*.version>` | Each provider's version |
| Module `pom.xml` → `<version>` | References property from root |
| `<dependencyManagement>` | Uses properties for inter-module deps |

### Bumping a Version

1. Create a release branch from `main`:
   ```bash
   git checkout -b release/<version>
   ```
2. Update the property in root `pom.xml` (e.g., `hyperscaledb-api.version`)
3. The module POM picks it up automatically via `${hyperscaledb-api.version}`
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

## Troubleshooting

### "Version mismatch" error
Update the version property in root `pom.xml`, merge to `main`, delete old tag, re-tag.

### "Invalid sibling dependency version" error
Release the dependency first (usually `hyperscaledb-api`), update version property, retry.
Only beta (`X.Y.Z-beta.N`) and GA (`X.Y.Z`) versions are valid for release.

### Pipeline didn't trigger
- Verify tag matches `hyperscaledb-*-v<semver>` or `hyperscaledb-*-v<semver>-beta.<N>`
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
