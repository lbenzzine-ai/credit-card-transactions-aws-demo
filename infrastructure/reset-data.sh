#!/usr/bin/env bash
#
# Wipes all DEMO DATA (DynamoDB items, SQS messages, S3 receipts/settlements)
# so you can start clean, without tearing down and re-provisioning the whole
# stack (IAM role/user, KMS key, SNS topic all stay exactly as they are).
#
# Requires admin credentials (deleting/recreating a table, purging a queue,
# and deleting S3 objects are all things the least-privilege service account
# is deliberately NOT allowed to do).
#
# Usage:
#   unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY   # make sure you're on your admin identity
#   ./infrastructure/reset-data.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"

if [ ! -f "${ENV_FILE}" ]; then
  echo "No infrastructure/.env found — run infrastructure/setup.sh first."
  exit 1
fi

# shellcheck disable=SC1090
set -a; source "${ENV_FILE}"; set +a

CALLER=$(aws sts get-caller-identity --query Arn --output text)
if [[ "${CALLER}" == *"svc-card-txn-demo"* ]]; then
  echo "ERROR: you're authenticated as the service account (${CALLER})."
  echo "This script needs admin permissions. Run:"
  echo "  unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY"
  echo "then try again."
  exit 1
fi

echo "== Resetting demo data (identity: ${CALLER}) =="
echo ""

# ---------------------------------------------------------------------------
# 1. DynamoDB — delete and recreate the table with the same schema + GSI.
#    Simplest reliable way to wipe all items without needing dynamodb:Scan.
# ---------------------------------------------------------------------------
echo "-- Recreating DynamoDB table: ${DYNAMODB_TABLE}"
aws dynamodb delete-table --table-name "${DYNAMODB_TABLE}" --region "${AWS_REGION}" > /dev/null
aws dynamodb wait table-not-exists --table-name "${DYNAMODB_TABLE}" --region "${AWS_REGION}"

aws dynamodb create-table \
  --table-name "${DYNAMODB_TABLE}" \
  --attribute-definitions \
      AttributeName=idempotencyKey,AttributeType=S \
      AttributeName=status,AttributeType=S \
      AttributeName=createdAt,AttributeType=S \
  --key-schema \
      AttributeName=idempotencyKey,KeyType=HASH \
  --global-secondary-indexes \
      '[{"IndexName":"status-index","KeySchema":[{"AttributeName":"status","KeyType":"HASH"},{"AttributeName":"createdAt","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST \
  --region "${AWS_REGION}" > /dev/null

aws dynamodb wait table-exists --table-name "${DYNAMODB_TABLE}" --region "${AWS_REGION}"

echo "   waiting for status-index GSI to become active (a fresh table with 0 items is usually quick)..."
while true; do
  gsi_status=$(aws dynamodb describe-table --table-name "${DYNAMODB_TABLE}" --region "${AWS_REGION}" \
    --query 'Table.GlobalSecondaryIndexes[0].IndexStatus' --output text)
  if [ "${gsi_status}" = "ACTIVE" ]; then
    break
  fi
  sleep 5
done
echo "   table + GSI ready"

# ---------------------------------------------------------------------------
# 2. SQS — purge both the main queue and the dead-letter queue
# ---------------------------------------------------------------------------
echo "-- Purging SQS queue"
aws sqs purge-queue --queue-url "${SQS_QUEUE_URL}" --region "${AWS_REGION}" || \
  echo "   (purge may be rate-limited to once per 60s — if this failed, wait a minute and re-run)"

# ---------------------------------------------------------------------------
# 3. S3 — delete objects under receipts/ and settlements/, keep the bucket
# ---------------------------------------------------------------------------
echo "-- Clearing S3 receipts/ and settlements/ prefixes in ${RECEIPTS_BUCKET}"
aws s3 rm "s3://${RECEIPTS_BUCKET}/receipts/" --recursive --region "${AWS_REGION}" || true
aws s3 rm "s3://${RECEIPTS_BUCKET}/settlements/" --recursive --region "${AWS_REGION}" || true

# Bucket has versioning enabled — clear old versions too, or storage (still
# essentially free at this scale) quietly accumulates.
aws s3api list-object-versions --bucket "${RECEIPTS_BUCKET}" --region "${AWS_REGION}" \
  --query '{Objects: Versions[].{Key:Key,VersionId:VersionId}}' --output json 2>/dev/null \
  > /tmp/reset-versions.json || true
if [ -s /tmp/reset-versions.json ] && grep -q '"Objects"' /tmp/reset-versions.json; then
  aws s3api delete-objects --bucket "${RECEIPTS_BUCKET}" --delete "file:///tmp/reset-versions.json" \
    --region "${AWS_REGION}" > /dev/null || true
fi

echo ""
echo "== Reset complete =="
echo "DynamoDB table, SQS queue, and S3 receipts/settlements are all empty."
echo "IAM role/user, KMS key, and SNS topic are untouched — no need to re-run setup.sh."
echo ""
echo "Next: switch back to the service account and run the demo:"
echo "  export AWS_ACCESS_KEY_ID=\$(grep SERVICE_ACCOUNT_ACCESS_KEY_ID ${ENV_FILE} | cut -d= -f2-)"
echo "  export AWS_SECRET_ACCESS_KEY=\$(grep SERVICE_ACCOUNT_SECRET_ACCESS_KEY ${ENV_FILE} | cut -d= -f2-)"
echo "  export AWS_REGION=${AWS_REGION}"
echo "  ./run-demo.sh"
