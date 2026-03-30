# Changelog

Each publishable module maintains its own changelog:

| Module | Changelog |
|--------|-----------|
| `hyperscaledb-api` | [hyperscaledb-api/CHANGELOG.md](hyperscaledb-api/CHANGELOG.md) |
| `hyperscaledb-provider-cosmos` | [hyperscaledb-provider-cosmos/CHANGELOG.md](hyperscaledb-provider-cosmos/CHANGELOG.md) |
| `hyperscaledb-provider-dynamo` | [hyperscaledb-provider-dynamo/CHANGELOG.md](hyperscaledb-provider-dynamo/CHANGELOG.md) |
| `hyperscaledb-provider-spanner` | [hyperscaledb-provider-spanner/CHANGELOG.md](hyperscaledb-provider-spanner/CHANGELOG.md) |

## Project-Wide Changes

Changes that affect CI/CD, build infrastructure, or the project as a whole
(not specific to any single module) are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

### [Unreleased]

#### Added

- Independent module versioning — each publishable module has its own version
  and release cadence ([#39])
- Release pipeline triggered by per-module version tags (`hyperscaledb-<module>-v<version>`)
  with conditional test gates per module ([#39])
- GitHub Actions CI pipeline with unit tests, Cosmos DB emulator integration
  tests, DynamoDB Local integration tests, and release artifact verification ([#28], [#30])
- Community health files: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `CLA.md`,
  `SUPPORT.md`, PR and issue templates ([#19])

#### Changed

- DynamoDB Local CI job now uses manual `docker pull` with retry and exponential
  backoff instead of `services:` block to avoid Docker Hub rate limits ([#39])
- Pinned DynamoDB Local image to `2.5.3` instead of `:latest` for
  reproducibility ([#39])

#### Fixed

- CI: import Cosmos DB emulator Root CA (not leaf cert) into JDK truststore to
  fix SSL "signature check failed" errors ([#36])
- CI: create test database and container before Cosmos DB emulator tests ([#34])
- CI: improve emulator setup reliability with retry logic ([#32], [#33])
- Restored MIT LICENSE file lost during merge ([#26])

[#19]: https://github.com/microsoft/hyperscaledb-sdk-for-java/pull/19
[#26]: https://github.com/microsoft/hyperscaledb-sdk-for-java/pull/26
[#28]: https://github.com/microsoft/hyperscaledb-sdk-for-java/pull/28
[#30]: https://github.com/microsoft/hyperscaledb-sdk-for-java/pull/30
[#32]: https://github.com/microsoft/hyperscaledb-sdk-for-java/pull/32
[#33]: https://github.com/microsoft/hyperscaledb-sdk-for-java/pull/33
[#34]: https://github.com/microsoft/hyperscaledb-sdk-for-java/pull/34
[#36]: https://github.com/microsoft/hyperscaledb-sdk-for-java/pull/36
[#39]: https://github.com/microsoft/hyperscaledb-sdk-for-java/pull/39
