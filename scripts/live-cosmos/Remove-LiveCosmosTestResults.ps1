param(
  [Parameter(Mandatory)]
  [string]$SourceDirectory
)

$ErrorActionPreference = 'Stop'
$reportDirectory = Join-Path $SourceDirectory `
  'multiclouddb-conformance/target/surefire-reports'
$sentinelReport = Join-Path $reportDirectory `
  'TEST-com.multiclouddb.conformance.LiveCosmosEntraAuthenticationTest.xml'
if (Test-Path -LiteralPath $reportDirectory) {
  Remove-Item -LiteralPath $reportDirectory -Recurse -Force -ErrorAction Stop
}
if ((Test-Path -LiteralPath $reportDirectory) `
    -or (Test-Path -LiteralPath $sentinelReport)) {
  throw 'Stale live Cosmos DB test results could not be removed.'
}
