#!/usr/bin/env bash
#
# Removes everything created by setup.sh. Reads infrastructure/.env for
# resource identifiers, so run this from a checkout where setup.sh was
# already executed successfully.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"

if [ ! -f "${ENV_FILE}" ]; then
  echo "No infrastructure/.env found — nothing to tear down (or run manually)."
  exit 1
fi

# shellcheck disable=SC1090
set -a; source "${ENV_FILE}"; set +a

ROLE_NAME="CardTransactionServiceRole"
USER_NAME="svc-card-txn-demo"

echo "-- Deleting S3 bucket contents + bucket: ${RECEIPTS_BUCKET}"
aws s3 rm "s3://${RECEIPTS_BUCKET}" --recursive --region "${AWS_REGION}" || true
# Empty all versions (versioning was enabled in setup.sh)
aws s3api list-object-versions --bucket "${RECEIPTS_BUCKET}" --region "${AWS_REGION}" \
  --query '{Objects: Versions[].{Key:Key,VersionId:VersionId}}' --output json 2>/dev/null \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(json.dumps(d)) if d.get('Objects') else None" > /tmp/versions.json || true
if [ -s /tmp/versions.json ] && [ "$(cat /tmp/versions.json)" != "null" ]; then
  aws s3api delete-objects --bucket "${RECEIPTS_BUCKET}" --delete "file:///tmp/versions.json" --region "${AWS_REGION}" || true
fi
aws s3api delete-bucket --bucket "${RECEIPTS_BUCKET}" --region "${AWS_REGION}" || true

echo "-- Deleting DynamoDB table: ${DYNAMODB_TABLE}"
aws dynamodb delete-table --table-name "${DYNAMODB_TABLE}" --region "${AWS_REGION}" || true

echo "-- Deleting SQS queue: ${SQS_QUEUE_URL}"
aws sqs delete-queue --queue-url "${SQS_QUEUE_URL}" --region "${AWS_REGION}" || true

echo "-- Deleting SNS topic: ${SNS_TOPIC_ARN}"
aws sns delete-topic --topic-arn "${SNS_TOPIC_ARN}" --region "${AWS_REGION}" || true

if [ "${ENABLE_KMS:-true}" = "true" ]; then
  echo "-- Scheduling KMS key deletion (alias ${KMS_KEY_ALIAS})"
  KEY_ID=$(aws kms describe-key --key-id "${KMS_KEY_ALIAS}" --region "${AWS_REGION}" \
    --query 'KeyMetadata.KeyId' --output text 2>/dev/null || echo "")
  if [ -n "${KEY_ID}" ]; then
    aws kms delete-alias --alias-name "${KMS_KEY_ALIAS}" --region "${AWS_REGION}" || true
    aws kms schedule-key-deletion --key-id "${KEY_ID}" --pending-window-in-days 7 --region "${AWS_REGION}" || true
  else
    echo "   (no KMS key found — nothing to delete)"
  fi
else
  echo "-- Skipping KMS teardown (ENABLE_KMS=false — no key was ever created)"
fi

echo "-- Removing IAM role policy + role: ${ROLE_NAME}"
aws iam delete-role-policy --role-name "${ROLE_NAME}" --policy-name CardServicePermissions || true
aws iam delete-role --role-name "${ROLE_NAME}" || true

echo "-- Removing IAM user policy, access keys, and user: ${USER_NAME}"
aws iam delete-user-policy --user-name "${USER_NAME}" --policy-name AssumeCardServiceRoleOnly || true
for key in $(aws iam list-access-keys --user-name "${USER_NAME}" --query 'AccessKeyMetadata[].AccessKeyId' --output text 2>/dev/null || true); do
  aws iam delete-access-key --user-name "${USER_NAME}" --access-key-id "${key}" || true
done
aws iam delete-user --user-name "${USER_NAME}" || true

echo "-- Removing permissions boundary policy"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
aws iam delete-policy --policy-arn "arn:aws:iam::${ACCOUNT_ID}:policy/CardTxnDemoBoundary" || true

echo "== Teardown complete =="
