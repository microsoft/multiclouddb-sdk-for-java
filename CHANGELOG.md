# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Release pipeline triggered by version tags (`v*`) with unit test, Cosmos DB
  emulator, and DynamoDB Local gates before publishing ([#39])
- GitHub Actions CI pipeline with unit tests, Cosmos DB emulator integration
  tests, DynamoDB Local integration tests, and release artifact verification ([#28], [#30])
- Diagnostic support for provider operations
- Community health files: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `CLA.md`,
  `SUPPORT.md`, PR and issue templates ([#19])

### Changed

- Consolidated empty `hyperscaledb-spi` module into `hyperscaledb-api` — reduces
  published artifacts from 5 to 4 with zero code changes ([#39])
- DynamoDB Local CI job now uses manual `docker pull` with retry and exponential
  backoff instead of `services:` block to avoid Docker Hub rate limits ([#39])
- Pinned DynamoDB Local image to `2.5.3` instead of `:latest` for
  reproducibility ([#39])
- Upgraded Azure SDK dependencies to latest stable versions

### Fixed

- CI: import Cosmos DB emulator Root CA (not leaf cert) into JDK truststore to
  fix SSL "signature check failed" errors ([#36])
- CI: create test database and container before Cosmos DB emulator tests ([#34])
- CI: improve emulator setup reliability with retry logic ([#32], [#33])
- Restored MIT LICENSE file lost during merge ([#26])

[#19]: https://github.com/microsoft/hyperscale-db-sdk-for-java/pull/19
[#26]: https://github.com/microsoft/hyperscale-db-sdk-for-java/pull/26
[#28]: https://github.com/microsoft/hyperscale-db-sdk-for-java/pull/28
[#30]: https://github.com/microsoft/hyperscale-db-sdk-for-java/pull/30
[#32]: https://github.com/microsoft/hyperscale-db-sdk-for-java/pull/32
[#33]: https://github.com/microsoft/hyperscale-db-sdk-for-java/pull/33
[#34]: https://github.com/microsoft/hyperscale-db-sdk-for-java/pull/34
[#36]: https://github.com/microsoft/hyperscale-db-sdk-for-java/pull/36
[#39]: https://github.com/microsoft/hyperscale-db-sdk-for-java/pull/39
