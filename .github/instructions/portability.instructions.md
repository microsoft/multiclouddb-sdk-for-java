---
applyTo: "{multiclouddb-api,multiclouddb-provider-cosmos,multiclouddb-provider-dynamo,multiclouddb-provider-spanner,multiclouddb-conformance,multiclouddb-e2e,docs,specs}/**"
---

# Portability & doc-alignment review (auto-loaded checklist)

This is a slim, scoped pointer for the GitHub Copilot PR reviewer and
for the IDE Copilot when working under the paths above. **The full
checklist, rule rationale, parity matrix template, and PowerShell
diff loader live in
[`.github/skills/portability-review/`](../skills/portability-review/SKILL.md)
— this file points there to avoid drift.**

This SDK exposes one portable contract over Cosmos DB, DynamoDB, and
Spanner. Two invariants govern this repo:

1. **Cross-provider portability** — every behaviour the portable
   contract asserts must hold identically across all three providers,
   or be explicitly declared unsupported via `CapabilitySet` +
   `MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY`.
2. **Documentation alignment** — code that ships without the matching
   `docs/` / per-module `CHANGELOG.md` / `specs/<NNN-feature>/`
   update is incomplete.

**Severity tiers** (used in any review):

| Tier | Meaning |
|---|---|
| 🔴 Blocking | Correctness, portability violation, missing required doc/changelog, spec conformance gap. |
| 🟡 Recommendation | Design concern, partial provider symmetry, missing test coverage, non-severe cost asymmetry. |
| 🟢 Suggestion | Nice to have. Doesn't block merge. |
| 💬 Observation | Informational. |

Silent cross-provider divergence not gated by `CapabilitySet` is
**always 🔴** — never downgraded.

For the detailed rules (provider symmetry, conformance coverage,
capability declaration, error normalization, cost-efficiency parity
with per-provider anti-pattern lists, the doc-alignment matrix, and
the severity self-challenge), read
[`portability-checklist.md`](../skills/portability-review/references/portability-checklist.md).

For deep review with a parity matrix and suggested fixes, the
`portability-review` agent (`.github/agents/portability-review.agent.md`)
orchestrates focused subagents — invoke it locally.

**Review-only scope.** Cite the gap, quote the code or doc, explain
**why it matters** (tie to portability or doc-alignment), and offer
a concrete suggestion when possible. Style / formatting / naming
nits are out of scope unless they obscure portability.
