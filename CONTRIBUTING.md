# Contributing to Hyperscale DB SDK for Java

Thank you for your interest in contributing! This document explains how to
participate in the project — from reporting bugs and requesting features to
submitting code changes.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Contributor License Agreement (CLA)](#contributor-license-agreement-cla)
- [Reporting Issues](#reporting-issues)
- [Requesting Features](#requesting-features)
- [Setting Up the Development Environment](#setting-up-the-development-environment)
- [Building and Testing](#building-and-testing)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Coding Guidelines](#coding-guidelines)
- [Documentation](#documentation)

---

## Code of Conduct

This project has adopted the
[Microsoft Open Source Code of Conduct](https://opensource.microsoft.com/codeofconduct/).
For more information see the [Code of Conduct FAQ](https://opensource.microsoft.com/codeofconduct/faq/)
or contact [opencode@microsoft.com](mailto:opencode@microsoft.com) with any
additional questions or comments.

See also: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)

---

## Contributor License Agreement (CLA)

This project requires that contributors sign a **Contributor License Agreement
(CLA)** before any pull request can be merged. The CLA grants Microsoft the
rights necessary to use your contribution.

When you submit a pull request, the **Microsoft CLA bot** will automatically
check whether you have a signed CLA on file. If you do not, it will add a
comment to the PR with instructions on how to sign. The process is quick and
only needs to be completed once — it covers all Microsoft open-source
repositories.

You can review and sign the Microsoft CLA at:
**<https://cla.opensource.microsoft.com>**

---

## Reporting Issues

> **Security vulnerabilities** — do NOT report security issues via GitHub
> Issues. See [SECURITY.md](SECURITY.md) for the responsible disclosure process.

Before opening a new issue:

1. **Search existing issues** to avoid duplicates.
2. Use the appropriate issue template when available.
3. Include the following in your report:
   - SDK version (Maven artifact version or commit SHA)
   - Provider being used (Cosmos DB, DynamoDB, Spanner)
   - Java version and OS
   - Minimal reproducible code snippet
   - Full exception stack trace (sanitised — remove credentials/keys)
   - Expected vs. actual behaviour

---

## Requesting Features

Open a GitHub issue with the `enhancement` label. Please describe:

- The use case and the problem you are solving
- The proposed API shape (method signatures, config keys, etc.)
- Which providers are expected to support the feature
- Whether the feature is portable (all providers) or provider-specific

---

## Setting Up the Development Environment

### Prerequisites

| Tool | Minimum Version |
|------|----------------|
| JDK  | 17 (LTS)        |
| Maven | 3.9+           |
| Docker | 20+ (optional — Spanner emulator) |

### Clone and build

```bash
# Fork the repository on GitHub first, then clone your fork:
git clone https://github.com/<your-username>/hyperscale-db-sdk-for-java.git
cd hyperscale-db-sdk-for-java
mvn clean install -DskipTests
```

### Local provider emulators (for integration tests)

| Provider | Emulator |
|----------|----------|
| Azure Cosmos DB | [Azure Cosmos DB Emulator](https://aka.ms/cosmosdb-emulator) |
| Amazon DynamoDB | [DynamoDB Local](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html) |
| Google Cloud Spanner | `docker run -p 9010:9010 gcr.io/cloud-spanner-emulator/emulator` |

---

## Building and Testing

```bash
# Build all modules (skip tests)
mvn clean install -DskipTests

# Run unit tests
mvn test

# Run unit tests for a specific module
mvn test -pl hyperscaledb-provider-cosmos

# Run a specific test class
mvn test -pl hyperscaledb-api -Dtest=CapabilityTest
```

Integration tests (against live emulators) are run via Maven profiles and
require the relevant emulator to be running locally. See the emulator table
above for setup commands.

---

## Submitting a Pull Request

1. **Fork** the repository and create a feature branch from `main`.
2. Make your changes, ensuring:
   - All existing tests pass (`mvn test`).
   - New behaviour is covered by unit tests.
   - Public API changes include Javadoc.
3. **Sign the CLA** (the bot will prompt you automatically).
4. Submit the PR against the `main` branch with a clear description of:
   - What was changed and why
   - Which issue(s) are addressed (use `Closes #N` to auto-close)
   - Any breaking API changes
5. Address review feedback in follow-up commits (do not force-push during
   review unless asked).

### PR checklist

- [ ] `mvn clean install` passes
- [ ] New public API methods have Javadoc
- [ ] Tests added or updated for the change
- [ ] CLA signed

---

## Coding Guidelines

- **Java 17** language level across all modules.
- Follow standard Java naming conventions.
- Prefer immutable value types: use `Map.copyOf()` / `List.copyOf()` for
  public API return values; document mutability clearly in Javadoc.
- Avoid Jackson or provider-specific types on the public API surface
  (`com.hyperscaledb.api.*`). Documents are `Map<String, Object>`.
- Keep the public API surface minimal. Only expose what has a demonstrated
  use case.
- Provider adapters must not bleed provider-specific details into the
  portable API layer.

---

## Documentation

- Update `docs/compatibility.md` for capability changes.
- Update `specs/001-clouddb-sdk/research.md` for design decisions.
- Update `README.md` for user-facing usage changes.

---

Questions? Open a GitHub issue or start a discussion in the repository.

