#!/usr/bin/env bash
# cleanup-dynamo.sh -- Delete all DynamoDB tables created by the Risk Platform
#
# Usage (run from the repo root):
#   chmod +x hyperscaledb-samples/scripts/cleanup-dynamo.sh
#   ./hyperscaledb-samples/scripts/cleanup-dynamo.sh
#   AWS_REGION=eu-west-1 ./hyperscaledb-samples/scripts/cleanup-dynamo.sh

set -euo pipefail

# -- Region -------------------------------------------------------------------
# Priority: 1) AWS_REGION env var  2) aws configure default  3) prompt the user
REGION="${AWS_REGION:-}"

if [[ -z "$REGION" ]]; then
  REGION=$(aws configure get region 2>/dev/null || true)
fi

if [[ -z "$REGION" ]]; then
  read -rp "Enter your AWS region (e.g. us-east-1): " REGION
fi

if [[ -z "$REGION" ]]; then
  echo "ERROR: No AWS region specified. Aborting." >&2
  exit 1
fi

echo "Region : $REGION"
echo ""

# -- Tables -------------------------------------------------------------------
TABLES=(
  acme-capital-risk-db__portfolios
  acme-capital-risk-db__positions
  acme-capital-risk-db__risk_metrics
  acme-capital-risk-db__alerts
  vanguard-partners-risk-db__portfolios
  vanguard-partners-risk-db__positions
  vanguard-partners-risk-db__risk_metrics
  vanguard-partners-risk-db__alerts
  summit-wealth-risk-db__portfolios
  summit-wealth-risk-db__positions
  summit-wealth-risk-db__risk_metrics
  summit-wealth-risk-db__alerts
  riskplatform-admin__tenants
  _shared-risk-db__market_data
)

# -- Preview ------------------------------------------------------------------
echo "The following tables will be deleted in region '$REGION':"
for TABLE in "${TABLES[@]}"; do
  echo "  - $TABLE"
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
for TABLE in "${TABLES[@]}"; do
  if aws dynamodb delete-table --table-name "$TABLE" --region "$REGION" \
       --output text > /dev/null 2>&1; then
    echo "  Deleted : $TABLE"
  else
    echo "  Skipped : $TABLE (not found or already deleted)"
    ERRORS=$((ERRORS + 1))
  fi
done

echo ""
if [[ $ERRORS -eq 0 ]]; then
  echo "All tables deleted successfully."
else
  echo "Done. $ERRORS table(s) were not found (already deleted or never created)."
fi

