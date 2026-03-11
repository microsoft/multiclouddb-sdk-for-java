# cleanup-dynamo.ps1 -- Delete all DynamoDB tables created by the Risk Platform
#
# Usage (run from the repo root):
#   .\hyperscaledb-samples\scripts\cleanup-dynamo.ps1
#   .\hyperscaledb-samples\scripts\cleanup-dynamo.ps1 -Region eu-west-1

param([string]$Region = "")

# -- Region -------------------------------------------------------------------
# Priority: 1) -Region param  2) AWS_REGION env var  3) aws configure  4) prompt
if (-not $Region) { $Region = $env:AWS_REGION }
if (-not $Region) { $Region = (aws configure get region 2>$null) }
if (-not $Region) { $Region = Read-Host "Enter your AWS region (e.g. us-east-1)" }

if (-not $Region) {
    Write-Error "No AWS region specified. Aborting."
    exit 1
}

Write-Host "Region : $Region"
Write-Host ""

# -- Tables -------------------------------------------------------------------
$Tables = @(
    "acme-capital-risk-db__portfolios"
    "acme-capital-risk-db__positions"
    "acme-capital-risk-db__risk_metrics"
    "acme-capital-risk-db__alerts"
    "vanguard-partners-risk-db__portfolios"
    "vanguard-partners-risk-db__positions"
    "vanguard-partners-risk-db__risk_metrics"
    "vanguard-partners-risk-db__alerts"
    "summit-wealth-risk-db__portfolios"
    "summit-wealth-risk-db__positions"
    "summit-wealth-risk-db__risk_metrics"
    "summit-wealth-risk-db__alerts"
    "riskplatform-admin__tenants"
    "_shared-risk-db__market_data"
)

# -- Preview ------------------------------------------------------------------
Write-Host "The following tables will be deleted in region '$Region':"
foreach ($Table in $Tables) { Write-Host "  - $Table" }
Write-Host ""

$Confirm = Read-Host "Proceed? [y/N]"
if ($Confirm -ne "y") { Write-Host "Aborted."; exit 0 }

# -- Delete -------------------------------------------------------------------
Write-Host ""
$Errors = 0
foreach ($Table in $Tables) {
    aws dynamodb delete-table --table-name $Table --region $Region --output text 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Deleted : $Table"
    } else {
        Write-Host "  Skipped : $Table (not found or already deleted)"
        $Errors++
    }
}

Write-Host ""
if ($Errors -eq 0) {
    Write-Host "All tables deleted successfully."
} else {
    Write-Host "Done. $Errors table(s) were not found (already deleted or never created)."
}

