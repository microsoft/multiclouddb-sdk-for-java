param(
  [Parameter(Mandatory)]
  [string]$SourceDirectory
)

$ErrorActionPreference = 'Stop'
$reportDirectory = Join-Path $SourceDirectory `
  'multiclouddb-conformance/target/surefire-reports'
$report = Join-Path $reportDirectory `
  'TEST-com.multiclouddb.conformance.LiveCosmosEntraAuthenticationTest.xml'
if (-not (Test-Path -LiteralPath $report -PathType Leaf)) {
  throw 'The dedicated LiveCosmosEntraAuthenticationTest report was not produced.'
}

[xml]$result = Get-Content -LiteralPath $report -Raw
$sentinelMethod = 'entraAuthenticationPerformsCreateReadDelete'
$cases = @($result.SelectNodes(
  "//testcase[" +
  "@classname='com.multiclouddb.conformance.LiveCosmosEntraAuthenticationTest' and " +
  "(@name='$sentinelMethod' or @name='$sentinelMethod()')]"))
$total = 0
$failed = 0
$skipped = 0
foreach ($case in $cases) {
  $total++
  $failed += @($case.SelectNodes('./failure | ./error')).Count
  $skipped += @($case.SelectNodes('./skipped')).Count
}

$passed = $total - $failed - $skipped
Write-Host "total=$total passed=$passed failed=$failed skipped=$skipped"
if ($total -lt 1 -or $passed -lt 1 -or $failed -ne 0 -or $skipped -ne 0) {
  throw "LiveCosmosEntraAuthenticationTest#$sentinelMethod must have at least one passing case and no failed or skipped cases."
}
