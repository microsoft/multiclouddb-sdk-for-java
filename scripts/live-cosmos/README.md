# Live Cosmos pipeline scripts

These scripts back `azure-pipelines-live-cosmos.yml`.

- `Invoke-LiveCosmosPreflight.ps1` runs before Azure authentication and writes
  the validated Maven argument manifest into the supplied temporary directory.
- `Invoke-LiveCosmosTests.ps1` runs inside the same `AzureCLI@2` task that
  establishes the Workload Identity Federation session. It compiles and
  validates SDK-owned API, SPI, and provider class origins, then executes the
  live sentinel.
- `Remove-LiveCosmosTestResults.ps1` removes stale sentinel reports before the
  build and fails if cleanup does not complete.
- `Test-LiveCosmosResults.ps1` requires a passing, non-skipped sentinel result
  after Azure DevOps publishes the JUnit report.
- `LiveCosmos.Common.ps1` contains only shared output filtering and
  Maven/JVM-configuration guards. It performs no authentication or Maven work.

The preflight and test entry points require explicit `-SourceDirectory` and
`-TempDirectory` arguments and fail if the common helper is unavailable. The
report scripts require `-SourceDirectory`.

The no-live-auth regression harness requires PowerShell 7.4 or later, Java 17,
Maven 3, and a POSIX environment that provides `/bin/sh` and `chmod` (the
GitHub Actions job uses `ubuntu-latest`). Compile the reactor outputs that the
provider-origin checks inspect, then run the harness from the repository root:

```powershell
mvn -B -pl multiclouddb-conformance -am test-compile -DskipTests
pwsh -File scripts/live-cosmos/tests/Invoke-LiveCosmosRegression.ps1
```

The fixture pins `maven-compiler-plugin` 3.12.1, matching this repository. Its
separate fixture API module models the canonical reactor API output so the
origin checks are exercised without test-output API or SPI shadows. The
harness uses fake Azure CLI authentication and an in-memory test client. It
does not contact Azure or Cosmos DB.
