# cleanup-cosmos.ps1 -- Delete all Cosmos DB databases created by the Risk Platform
#
# Usage (run from the repo root):
#   .\hyperscaledb-samples\scripts\cleanup-cosmos.ps1
#   .\hyperscaledb-samples\scripts\cleanup-cosmos.ps1 -CosmosAccount my-account -ResourceGroup my-rg

param(
    [string]$CosmosAccount  = "",
    [string]$ResourceGroup  = ""
)

$Props = "hyperscaledb-samples\src\main\resources\risk-platform-cosmos-cloud.properties"

# -- Helper: read a key from the properties file ------------------------------
function Get-Prop([string]$Key) {
    if (Test-Path $Props) {
        $line = Get-Content $Props | Where-Object { $_ -match "^${Key}=" }
        if ($line) { return ($line -split "=", 2)[1].Trim() }
    }
    return ""
}

# -- Cosmos account -----------------------------------------------------------
# Priority: 1) param  2) env var  3) properties file  4) prompt
if (-not $CosmosAccount) { $CosmosAccount = $env:COSMOS_ACCOUNT }
if (-not $CosmosAccount) {
    $endpoint = Get-Prop "hyperscaledb.connection.endpoint"
    if ($endpoint -match "https://([^.]+)\.documents\.azure\.com") {
        $CosmosAccount = $Matches[1]
    }
}
if (-not $CosmosAccount) { $CosmosAccount = Read-Host "Enter your Cosmos DB account name" }

if (-not $ResourceGroup) { $ResourceGroup = $env:RESOURCE_GROUP }
if (-not $ResourceGroup) { $ResourceGroup = Get-Prop "hyperscaledb.connection.resourceGroupName" }
if (-not $ResourceGroup) { $ResourceGroup = Read-Host "Enter your resource group name" }

if (-not $CosmosAccount -or -not $ResourceGroup) {
    Write-Error "Cosmos DB account name and resource group are required. Aborting."
    exit 1
}

Write-Host "Account        : $CosmosAccount"
Write-Host "Resource Group : $ResourceGroup"
Write-Host ""

# -- Databases ----------------------------------------------------------------
$Databases = @(
    "riskplatform-admin"
    "acme-capital-risk-db"
    "vanguard-partners-risk-db"
    "summit-wealth-risk-db"
    "_shared-risk-db"
)

# -- Preview ------------------------------------------------------------------
Write-Host "The following Cosmos DB databases will be deleted:"
foreach ($Db in $Databases) { Write-Host "  - $Db" }
Write-Host ""

$Confirm = Read-Host "Proceed? [y/N]"
if ($Confirm -ne "y") { Write-Host "Aborted."; exit 0 }

# -- Delete -------------------------------------------------------------------
Write-Host ""
$Errors = 0
foreach ($Db in $Databases) {
    az cosmosdb sql database delete `
        --account-name $CosmosAccount `
        --resource-group $ResourceGroup `
        --name $Db `
        --yes `
        --output none 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Deleted : $Db"
    } else {
        Write-Host "  Skipped : $Db (not found or already deleted)"
        $Errors++
    }
}

Write-Host ""
if ($Errors -eq 0) {
    Write-Host "All databases deleted successfully."
} else {
    Write-Host "Done. $Errors database(s) were not found (already deleted or never created)."
}

