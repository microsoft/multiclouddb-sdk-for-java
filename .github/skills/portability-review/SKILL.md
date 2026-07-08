---
name: portability-review
description: >
  Cross-provider portability and documentation-alignment review skill for
  multiclouddb-sdk-for-java. Houses the canonical checklist (provider
  symmetry, conformance coverage, capability declarations, error
  normalization, cost-efficiency parity, doc/changelog/spec alignment), the
  cross-provider parity matrix template, and the PowerShell diff loader.
  Invoked by the `portability-review` agent (and its subagents) when
  reviewing a diff, a branch, or a PR.
allowed-tools: pwsh git gh read_file
---

# Portability Review

This skill is the **single source of truth** for the cross-provider
portability and documentation-alignment invariants that govern this
repository. The `.github/agents/portability-review.agent.md` orchestrator
and its subagents (`portability-context-builder`, `portability-parity-reviewer`,
`portability-doc-reviewer`, `portability-fresh-eyes`) all reference the
files in this skill rather than embedding rules inline. That keeps the
rules in one place and prevents the two-file drift that an earlier draft
of this work suffered from.

Path note: examples below assume the working directory is the repository
root (the same convention used by the existing `release.agent.md`). Within
this skill's own files, references to other skill files use paths relative
to this `SKILL.md` (e.g. `references/portability-checklist.md`).

## When to use this skill

- The portability-review agent (and its subagents) **must** read the
  relevant reference under `references/` before producing findings.
- A contributor preparing a PR may read the checklist directly to
  self-review.
- The GitHub Copilot PR-reviewer bot loads a slim pointer at
  `.github/instructions/portability.instructions.md`; that pointer
  references this skill for the full detail.

## Files in this skill

| Path | Purpose |
|---|---|
| `references/portability-checklist.md` | Canonical checklist — provider symmetry, conformance coverage, capability declaration, error normalization, cost-efficiency parity, doc alignment, spec conformance. |
| `references/parity-matrix-template.md` | Cross-provider parity matrix and cost-efficiency sub-matrix templates, with explicit "placeholders only — do not copy literally" warning. |
| `scripts/load-diff.ps1` | PowerShell diff loader. Auto-detects the base ref (`upstream/main` → `origin/main` → repo default branch), `git fetch`es it explicitly, supports `staged` / `branch` / `pr` scopes, writes diff + diff-stat to temp files. |

## Severity tiers (used everywhere)

| Tier | Meaning |
|---|---|
| 🔴 **Blocking** | Must fix before merge. Correctness, portability violation (silent cross-provider divergence), missing required doc/changelog, spec conformance gap. |
| 🟡 **Recommendation** | Should address. Design concern, missing test coverage, partial provider symmetry, cost-efficiency asymmetry that isn't severe-and-unbounded. |
| 🟢 **Suggestion** | Nice to have. Doesn't block merge. |
| 💬 **Observation** | Informational. No action required. |

Correctness and portability violations (silent divergence across providers,
unhandled error path in one adapter, missing `CapabilitySet` declaration)
are **always 🔴 Blocking** — never downgraded to suggestions.

## Diff loader usage

```powershell
# Default: current branch vs canonical main (auto-detects upstream/main → origin/main → default)
./.github/skills/portability-review/scripts/load-diff.ps1

# Staged only
./.github/skills/portability-review/scripts/load-diff.ps1 -Scope staged

# A specific PR
./.github/skills/portability-review/scripts/load-diff.ps1 -Scope pr -Pr 80

# Override the base ref explicitly (e.g. comparing against a feature branch)
./.github/skills/portability-review/scripts/load-diff.ps1 -Base origin/release/0.2
```

The script always `git fetch`es the resolved base before computing the diff,
so the comparison is against an up-to-date ref. It writes the diff and a
stat summary to temp files and prints their paths to stdout. The calling
agent reads those temp files via `read_file`.

## Modules

| Module | Role |
|---|---|
| `multiclouddb-api/` | Portable contracts, SPI, query model |
| `multiclouddb-provider-cosmos/` | Azure Cosmos DB adapter |
| `multiclouddb-provider-dynamo/` | Amazon DynamoDB adapter |
| `multiclouddb-provider-spanner/` | Google Cloud Spanner adapter |
| `multiclouddb-conformance/` | Provider-agnostic conformance tests |
| `multiclouddb-e2e/` | Cross-provider end-to-end harness |
| `docs/`, `specs/<NNN-feature>/` | User-facing & design docs |

## Output disclosure

Any review text that ends up pasted into a GitHub PR comment must carry the
AI-generated disclosure footer:

```
---
<sub>⚠️ AI-generated review — may be incorrect. Agree? → resolve the
conversation. Disagree? → reply with your reasoning.</sub>
```
