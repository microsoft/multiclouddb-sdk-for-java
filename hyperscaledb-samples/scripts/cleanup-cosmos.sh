#!/usr/bin/env bash
# cleanup-cosmos.sh -- Delete all Cosmos DB databases created by the Risk Platform
#
# Usage (run from the repo root):
#   chmod +x hyperscaledb-samples/scripts/cleanup-cosmos.sh
#   ./hyperscaledb-samples/scripts/cleanup-cosmos.sh
#
# The script reads COSMOS_ACCOUNT and RESOURCE_GROUP from the properties file
# automatically, or you can override them via environment variables:
#   COSMOS_ACCOUNT=my-account RESOURCE_GROUP=my-rg ./hyperscaledb-samples/scripts/cleanup-cosmos.sh

set -euo pipefail

PROPS="hyperscaledb-samples/src/main/resources/risk-platform-cosmos-cloud.properties"

# -- Cosmos account -----------------------------------------------------------
# Priority: 1) env var  2) properties file  3) prompt
resolve_from_props() {
  local key="$1"
  if [[ -f "$PROPS" ]]; then
    grep -E "^${key}=" "$PROPS" | sed 's/^[^=]*=//' | tr -d '[:space:]'
  fi
}

if [[ -z "${COSMOS_ACCOUNT:-}" ]]; then
  ENDPOINT=$(resolve_from_props "hyperscaledb.connection.endpoint")
  # Extract account name from https://<account>.documents.azure.com:443/
  COSMOS_ACCOUNT=$(echo "$ENDPOINT" | sed -E 's|https://([^.]+)\.documents\.azure\.com.*|\1|')
fi

if [[ -z "${RESOURCE_GROUP:-}" ]]; then
  RESOURCE_GROUP=$(resolve_from_props "hyperscaledb.connection.resourceGroupName")
fi

if [[ -z "${COSMOS_ACCOUNT:-}" ]]; then
  read -rp "Enter your Cosmos DB account name: " COSMOS_ACCOUNT
fi

if [[ -z "${RESOURCE_GROUP:-}" ]]; then
  read -rp "Enter your resource group name: " RESOURCE_GROUP
fi

if [[ -z "$COSMOS_ACCOUNT" || -z "$RESOURCE_GROUP" ]]; then
  echo "ERROR: Cosmos DB account name and resource group are required. Aborting." >&2
  exit 1
fi

echo "Account        : $COSMOS_ACCOUNT"
echo "Resource Group : $RESOURCE_GROUP"
echo ""

# -- Databases ----------------------------------------------------------------
DATABASES=(
  riskplatform-admin
  acme-capital-risk-db
  vanguard-partners-risk-db
  summit-wealth-risk-db
  _shared-risk-db
)

# -- Preview ------------------------------------------------------------------
echo "The following Cosmos DB databases will be deleted:"
for DB in "${DATABASES[@]}"; do
  echo "  - $DB"
done
echo ""

read -rp "Proceed? [y/N] " CONFIRM
if [[ "$(echo "$CONFIRM" | tr '[:upper:]' '[:lower:]')" != "y" ]]; then
  echo "Aborted."
  exit 0
fi

# -- Delete -------------------------------------------------------------------
echo ""
ERRORS=0
for DB in "${DATABASES[@]}"; do
  if az cosmosdb sql database delete \
       --account-name "$COSMOS_ACCOUNT" \
       --resource-group "$RESOURCE_GROUP" \
       --name "$DB" \
       --yes \
       --output none 2>/dev/null; then
    echo "  Deleted : $DB"
  else
    echo "  Skipped : $DB (not found or already deleted)"
    ERRORS=$((ERRORS + 1))
  fi
done

echo ""
if [[ $ERRORS -eq 0 ]]; then
  echo "All databases deleted successfully."
else
  echo "Done. $ERRORS database(s) were not found (already deleted or never created)."
fi

