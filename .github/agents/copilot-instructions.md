# multiclouddb-sdk Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-08-31

## Active Technologies
- Python 3.11+ (support 3.10 if required by environment constraints) (001-clouddb-sdk)
- Provider SDKs: azure-cosmos, boto3/botocore, google-cloud-spanner/google-api-core (001-clouddb-sdk)
- Testing: pytest (001-clouddb-sdk)
- Java 17 (LTS) (001-clouddb-sdk)
- N/A (client SDK; uses provider services/emulators) (001-clouddb-sdk)
- Java 17 LTS (Eclipse Adoptium Temurin-17.0.10.7-hotspot) + Jackson 2.17.0, SLF4J 2.0.12, Azure Cosmos SDK 4.82.0, AWS SDK v2 2.25.16 (DynamoDB + DynamoDB Enhanced), Google Cloud Spanner 6.62.0 (001-clouddb-sdk)
- Cosmos DB (NoSQL), DynamoDB, Spanner (via provider SDKs) (001-clouddb-sdk)
- Java 17 (LTS) + Jackson `JsonNode`, SLF4J, Azure Cosmos SDK v4, AWS SDK v2, Google Cloud Spanner Java client (users/allekim/feature/issue_25)
- N/A (SDK, not a data store) (users/allekim/feature/issue_25)
- Java 17 LTS + Azure Cosmos Java SDK 4.82.0, Azure Identity 1.18.2 (feat/cosmos-gateway-defaults)
- N/A; construction-time provider configuration only (feat/cosmos-gateway-defaults)

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
- feat/cosmos-gateway-defaults: Added Java 17 LTS + Azure Cosmos Java SDK 4.82.0, Azure Identity 1.18.2
- users/allekim/feature/issue_25: Added Java 17 (LTS) + Jackson `JsonNode`, SLF4J, Azure Cosmos SDK v4, AWS SDK v2, Google Cloud Spanner Java client
- 001-clouddb-sdk: Added Java 17 LTS (Eclipse Adoptium Temurin-17.0.10.7-hotspot) + Jackson 2.17.0, SLF4J 2.0.12, Azure Cosmos SDK 4.60.0, AWS SDK v2 2.25.16 (DynamoDB + DynamoDB Enhanced), Google Cloud Spanner 6.62.0


<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
