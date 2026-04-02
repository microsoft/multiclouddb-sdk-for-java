---
name: release
description: >
  Manages releases for hyperscaledb-sdk-for-java modules. Validates release
  readiness, updates POM versions and changelogs, prepares a release branch
  for a PR against upstream/main, and after merge creates and pushes
  per-module version tags that trigger the automated release pipeline.
allowed-tools: bash git gh read_file edit
arguments:
  module:
    type: string
    required: true
    description: >
      Module to release. One of: hyperscaledb-api, hyperscaledb-provider-cosmos,
      hyperscaledb-provider-dynamo, hyperscaledb-provider-spanner.
  version:
    type: string
    required: true
    description: >
      Version to release following semver (e.g. 0.1.0-beta.1, 1.0.0).
  date:
    type: string
    required: false
    default: today
    description: >
      Release date in YYYY-MM-DD format. Defaults to today.
argument-hints:
  module:
    - hyperscaledb-api
    - hyperscaledb-provider-cosmos
    - hyperscaledb-provider-dynamo
    - hyperscaledb-provider-spanner
  version:
    - 0.1.0-beta.1
    - 0.1.0
    - 1.0.0
  date:
    - "2026-04-02"
---

# Release

Orchestrates the release of hyperscaledb-sdk-for-java modules through a
PR-based workflow: prepare a release branch with version bumps and changelog
updates, merge via PR, then create tags to trigger the release pipeline.

Core scripts are in `<THIS_SKILL_DIRECTORY>/scripts/` for deterministic behavior.
Reference material is in `<THIS_SKILL_DIRECTORY>/references/release-process.md`.

Path note: All script paths are relative to this skill directory (this SKILL.md
file), not the repository root.

## Prerequisites

- `git` configured with push access to the fork (origin) and upstream repository
- Working directory is the repository root
- On the `main` branch with a clean working tree, up to date with origin/main

## Workflow

Execute these phases in order. Stop and report if any phase fails.

### Phase 1: List Modules

Run the list-modules script to show current state:

```bash
<THIS_SKILL_DIRECTORY>/scripts/list-modules.sh
```

Present the output to the user. If the user hasn't specified a module or version,
use this information to help them decide.

### Phase 2: Validate Release Readiness

Run the validation script for the target module:

```bash
<THIS_SKILL_DIRECTORY>/scripts/validate-release.sh --module <MODULE> --version <VERSION>
```

If validation fails (non-zero exit), show all failures and stop. Provide the fix
instructions from the output. Do NOT proceed to Phase 3 until all checks pass.

### Phase 3: Create Release Branch and PR

Create a feature branch on the fork with all release preparation changes:

1. Create and switch to a new branch:
   ```bash
   git checkout -b release/<VERSION>
   ```

2. **Update POM versions** — If the version in root `pom.xml` properties differs
   from the target release version, update the module-specific property in root
   `pom.xml` using the `<module-name>.version` naming scheme (for example,
   `<hyperscaledb-api.version>` or `<hyperscaledb-provider-cosmos.version>`).
   The module POMs inherit via `${property}`.

3. **Update changelogs** — For each module being released:
   a. Read `<MODULE>/CHANGELOG.md`
   b. Find the `## [Unreleased]` header line
   c. Replace it with `## [<VERSION>] — <DATE>` (use the provided date or today)
   d. Insert a new `## [Unreleased]` section above the renamed section with an
      empty line after it

4. **Commit** all changes with message:
   `release: prepare <VERSION> — update POM versions and changelogs`

5. **Push** the branch to origin (fork):
   ```bash
   git push origin release/<VERSION>
   ```

6. **Generate PR description** and copy to clipboard. The user will create the
   PR manually against `upstream/main`.

7. **Wait for PR merge** — Do NOT proceed to Phase 4 until the user confirms
   the PR has been merged. The release tags must point to a commit on
   upstream/main that includes the version and changelog updates.

### Phase 4: Create and Push Tags

After the release PR is merged into upstream/main:

1. Switch to main and sync:
   ```bash
   git checkout main && git fetch upstream && git rebase upstream/main
   ```

2. Create and push tags **one at a time** for each module being released.
   Push to upstream, not origin:
   ```bash
   <THIS_SKILL_DIRECTORY>/scripts/create-release-tag.sh --module <MODULE> --version <VERSION>
   ```
   Or manually:
   ```bash
   git tag -a "<MODULE>-v<VERSION>" -m "Release <MODULE> v<VERSION>"
   git push upstream "<MODULE>-v<VERSION>"
   ```

   **Critical:** Push each tag with a separate `git push` command. Pushing
   multiple tags in a single command silently fails to trigger GitHub Actions.

3. **Wait** for the workflow run to appear in the Actions tab before pushing
   the next tag.

### Phase 5: Monitor and Report

After each tag is pushed:

1. Report:
   - The tag name and commit SHA
   - Link to the Actions workflow run
   - Remind the user that the `production` environment requires manual approval:
     **Actions → Release → (the run) → Review deployments → Approve**

## Multi-Module Release

When releasing multiple modules, enforce dependency order:

1. Release `hyperscaledb-api` FIRST if it is in the release set
2. Then release providers in any order (they are independent of each other)
3. All modules can share a single release branch and PR (Phase 3)
4. In Phase 4, push tags one at a time in dependency order

**Critical:** Push each tag individually with a separate `git push upstream <tag>`
command. Pushing multiple tags in a single `git push` silently fails to trigger
GitHub Actions workflows. Wait for each workflow run to appear in the Actions tab
before pushing the next tag.

If the API version changed and providers depend on the new version, the provider
POM properties must be updated in the same release PR before merging.

## Troubleshooting

Read `<THIS_SKILL_DIRECTORY>/references/release-process.md` for detailed
troubleshooting guidance covering version mismatches, dependency versions,
pipeline trigger failures, and approval issues.
