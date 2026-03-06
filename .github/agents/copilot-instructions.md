# hyperscaledb-sdk Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-01-23

## Active Technologies
- Python 3.11+ (support 3.10 if required by environment constraints) (001-clouddb-sdk)
- Provider SDKs: azure-cosmos, boto3/botocore, google-cloud-spanner/google-api-core (001-clouddb-sdk)
- Testing: pytest (001-clouddb-sdk)
- Java 17 (LTS) (001-clouddb-sdk)
- N/A (client SDK; uses provider services/emulators) (001-clouddb-sdk)
- Java 17 LTS (Eclipse Adoptium Temurin-17.0.10.7-hotspot) + Jackson 2.17.0, SLF4J 2.0.12, Azure Cosmos SDK 4.60.0, AWS SDK v2 2.25.16 (DynamoDB + DynamoDB Enhanced), Google Cloud Spanner 6.62.0 (001-clouddb-sdk)
- Cosmos DB (NoSQL), DynamoDB, Spanner (via provider SDKs) (001-clouddb-sdk)

## Project Structure

```text
src/
tests/
```

## Commands

pytest

## Code Style

Python 3.11+: Follow standard conventions

## Recent Changes
- 001-clouddb-sdk: Added Java 17 LTS (Eclipse Adoptium Temurin-17.0.10.7-hotspot) + Jackson 2.17.0, SLF4J 2.0.12, Azure Cosmos SDK 4.60.0, AWS SDK v2 2.25.16 (DynamoDB + DynamoDB Enhanced), Google Cloud Spanner 6.62.0
- 001-clouddb-sdk: Added Java 17 (LTS)
- 001-clouddb-sdk: Added Python 3.11+ (support 3.10 if required by environment constraints)


<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
