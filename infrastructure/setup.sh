#!/usr/bin/env bash
#
# Provisions a demo "service account" IAM user, an IAM role it assumes,
# and the AWS resources (S3, DynamoDB, SQS, SNS, optionally KMS) used by
# the credit-card-aws-demo Java app.
#
# Requires: AWS CLI v2, configured with an identity that has IAM admin
# rights (this script itself is normally run by a human/admin, not by
# the service account it creates).
#
# Usage:
#   ./infrastructure/setup.sh                  # KMS mode (default, ~$1/month)
#   ENABLE_KMS=false ./infrastructure/setup.sh  # free mode — no KMS key created at all
#
# Everything created is prefixed/suffixed for easy identification and
# teardown. Resource identifiers are written to infrastructure/.env
# and src/main/resources/application.properties for the Java app to read.

set -euo pipefail

REGION="${AWS_REGION:-us-east-1}"
SUFFIX="${DEMO_SUFFIX:-$(date +%s | tail -c 6)}"
ENABLE_KMS="${ENABLE_KMS:-true}"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

BUCKET_NAME="cardco-txn-receipts-demo-${SUFFIX}"
TABLE_NAME="CardTransactions-demo-${SUFFIX}"
QUEUE_NAME="fraud-check-queue-demo-${SUFFIX}"
DLQ_NAME="fraud-check-dlq-demo-${SUFFIX}"
TOPIC_NAME="fraud-alerts-topic-demo-${SUFFIX}"
KMS_ALIAS="alias/card-txn-demo-${SUFFIX}"
ROLE_NAME="CardTransactionServiceRole"
USER_NAME="svc-card-txn-demo"
EXTERNAL_ID="card-txn-demo-external-id"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERED_DIR="${SCRIPT_DIR}/iam/rendered"
mkdir -p "${RENDERED_DIR}"

echo "== Account: ${ACCOUNT_ID}  Region: ${REGION}  Suffix: ${SUFFIX}  KMS: ${ENABLE_KMS} =="

# ---------------------------------------------------------------------------
# 1. KMS key for encrypting cardholder data and S3 objects (skipped if
#    ENABLE_KMS=false — this is the only piece of the demo that costs money)
# ---------------------------------------------------------------------------
KMS_KEY_ID=""
if [ "${ENABLE_KMS}" = "true" ]; then
  echo "-- Creating KMS key (this is the ~\$1/month resource)"
  KMS_KEY_ID=$(aws kms create-key \
    --description "Card transaction demo key" \
    --region "${REGION}" \
    --query 'KeyMetadata.KeyId' --output text)

  aws kms create-alias \
    --alias-name "${KMS_ALIAS}" \
    --target-key-id "${KMS_KEY_ID}" \
    --region "${REGION}"
else
  echo "-- Skipping KMS key creation (ENABLE_KMS=false) — S3 will use default AES256 encryption"
fi

# ---------------------------------------------------------------------------
# 2. S3 bucket for receipts — SSE-KMS if enabled, otherwise free SSE-S3
# ---------------------------------------------------------------------------
echo "-- Creating S3 bucket: ${BUCKET_NAME}"
if [ "${REGION}" = "us-east-1" ]; then
  aws s3api create-bucket --bucket "${BUCKET_NAME}" --region "${REGION}"
else
  aws s3api create-bucket --bucket "${BUCKET_NAME}" --region "${REGION}" \
    --create-bucket-configuration LocationConstraint="${REGION}"
fi

aws s3api put-public-access-block --bucket "${BUCKET_NAME}" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

aws s3api put-bucket-versioning --bucket "${BUCKET_NAME}" \
  --versioning-configuration Status=Enabled

if [ "${ENABLE_KMS}" = "true" ]; then
  aws s3api put-bucket-encryption --bucket "${BUCKET_NAME}" \
    --server-side-encryption-configuration '{
      "Rules": [{
        "ApplyServerSideEncryptionByDefault": {
          "SSEAlgorithm": "aws:kms",
          "KMSMasterKeyID": "'"${KMS_KEY_ID}"'"
        }
      }]
    }'
else
  aws s3api put-bucket-encryption --bucket "${BUCKET_NAME}" \
    --server-side-encryption-configuration '{
      "Rules": [{
        "ApplyServerSideEncryptionByDefault": {
          "SSEAlgorithm": "AES256"
        }
      }]
    }'
fi

# ---------------------------------------------------------------------------
# 3. DynamoDB table
# ---------------------------------------------------------------------------
echo "-- Creating DynamoDB table: ${TABLE_NAME} (with status-index GSI for settlement batches)"
aws dynamodb create-table \
  --table-name "${TABLE_NAME}" \
  --attribute-definitions \
      AttributeName=idempotencyKey,AttributeType=S \
      AttributeName=status,AttributeType=S \
      AttributeName=createdAt,AttributeType=S \
  --key-schema \
      AttributeName=idempotencyKey,KeyType=HASH \
  --global-secondary-indexes \
      '[{"IndexName":"status-index","KeySchema":[{"AttributeName":"status","KeyType":"HASH"},{"AttributeName":"createdAt","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST \
  --region "${REGION}" > /dev/null

aws dynamodb wait table-exists --table-name "${TABLE_NAME}" --region "${REGION}"

# ---------------------------------------------------------------------------
# 4. SQS queue + dead-letter queue
# ---------------------------------------------------------------------------
echo "-- Creating SQS DLQ + queue"
DLQ_URL=$(aws sqs create-queue --queue-name "${DLQ_NAME}" --region "${REGION}" \
  --query 'QueueUrl' --output text)
DLQ_ARN=$(aws sqs get-queue-attributes --queue-url "${DLQ_URL}" \
  --attribute-names QueueArn --region "${REGION}" \
  --query 'Attributes.QueueArn' --output text)

REDRIVE_POLICY=$(printf '{"deadLetterTargetArn":"%s","maxReceiveCount":"5"}' "${DLQ_ARN}")

QUEUE_URL=$(aws sqs create-queue --queue-name "${QUEUE_NAME}" --region "${REGION}" \
  --attributes "{\"RedrivePolicy\":\"$(echo "${REDRIVE_POLICY}" | sed 's/"/\\"/g')\"}" \
  --query 'QueueUrl' --output text)
QUEUE_ARN=$(aws sqs get-queue-attributes --queue-url "${QUEUE_URL}" \
  --attribute-names QueueArn --region "${REGION}" \
  --query 'Attributes.QueueArn' --output text)

# ---------------------------------------------------------------------------
# 5. SNS topic
# ---------------------------------------------------------------------------
echo "-- Creating SNS topic: ${TOPIC_NAME}"
TOPIC_ARN=$(aws sns create-topic --name "${TOPIC_NAME}" --region "${REGION}" \
  --query 'TopicArn' --output text)

# ---------------------------------------------------------------------------
# 6. IAM service account (user) with NO direct resource permissions
# ---------------------------------------------------------------------------
echo "-- Creating IAM service account user: ${USER_NAME}"
aws iam create-user --user-name "${USER_NAME}" \
  --tags Key=purpose,Value=card-txn-demo Key=managed-by,Value=setup-script \
  || echo "   (user already exists, continuing)"

# ---------------------------------------------------------------------------
# 7. IAM role the service account assumes, scoped to just-created resources.
#    The permissions policy has two variants — with and without the KMS
#    statement — selected based on ENABLE_KMS.
# ---------------------------------------------------------------------------
echo "-- Rendering IAM policy documents"
sed \
  -e "s/__AWS_ACCOUNT_ID__/${ACCOUNT_ID}/g" \
  "${SCRIPT_DIR}/iam/trust-policy-service-role.json" \
  > "${RENDERED_DIR}/trust-policy-service-role.json"

if [ "${ENABLE_KMS}" = "true" ]; then
  POLICY_SOURCE="${SCRIPT_DIR}/iam/card-service-permissions-policy-kms.json"
else
  POLICY_SOURCE="${SCRIPT_DIR}/iam/card-service-permissions-policy-no-kms.json"
fi

sed \
  -e "s/__AWS_ACCOUNT_ID__/${ACCOUNT_ID}/g" \
  -e "s/__AWS_REGION__/${REGION}/g" \
  -e "s/__RECEIPTS_BUCKET__/${BUCKET_NAME}/g" \
  -e "s/__DYNAMODB_TABLE__/${TABLE_NAME}/g" \
  -e "s/__SQS_QUEUE__/${QUEUE_NAME}/g" \
  -e "s/__SNS_TOPIC__/${TOPIC_NAME}/g" \
  -e "s/__KMS_KEY_ID__/${KMS_KEY_ID}/g" \
  "${POLICY_SOURCE}" \
  > "${RENDERED_DIR}/card-service-permissions-policy.json"

sed \
  -e "s/__AWS_ACCOUNT_ID__/${ACCOUNT_ID}/g" \
  "${SCRIPT_DIR}/iam/service-account-assume-role-policy.json" \
  > "${RENDERED_DIR}/service-account-assume-role-policy.json"

echo "-- Ensuring permissions boundary policy exists: CardTxnDemoBoundary"
BOUNDARY_ARN="arn:aws:iam::${ACCOUNT_ID}:policy/CardTxnDemoBoundary"
if aws iam get-policy --policy-arn "${BOUNDARY_ARN}" >/dev/null 2>&1; then
  echo "   (boundary policy already exists, reusing)"
else
  aws iam create-policy \
    --policy-name CardTxnDemoBoundary \
    --policy-document "file://${SCRIPT_DIR}/iam/permissions-boundary.json" > /dev/null
fi

echo "-- Ensuring IAM role exists: ${ROLE_NAME}"
if aws iam get-role --role-name "${ROLE_NAME}" >/dev/null 2>&1; then
  echo "   (role already exists — updating its trust policy instead of creating)"
  aws iam update-assume-role-policy \
    --role-name "${ROLE_NAME}" \
    --policy-document "file://${RENDERED_DIR}/trust-policy-service-role.json"
else
  aws iam create-role \
    --role-name "${ROLE_NAME}" \
    --assume-role-policy-document "file://${RENDERED_DIR}/trust-policy-service-role.json" \
    --max-session-duration 3600 \
    --permissions-boundary "${BOUNDARY_ARN}" > /dev/null
fi

aws iam put-role-policy \
  --role-name "${ROLE_NAME}" \
  --policy-name CardServicePermissions \
  --policy-document "file://${RENDERED_DIR}/card-service-permissions-policy.json"

ROLE_ARN=$(aws iam get-role --role-name "${ROLE_NAME}" --query 'Role.Arn' --output text)

echo "-- Attaching assume-role policy to service account user"
aws iam put-user-policy \
  --user-name "${USER_NAME}" \
  --policy-name AssumeCardServiceRoleOnly \
  --policy-document "file://${RENDERED_DIR}/service-account-assume-role-policy.json"

# ---------------------------------------------------------------------------
# 8. Access keys for the service account (used as the app's BASE identity;
#    the app immediately exchanges these for temporary role credentials)
# ---------------------------------------------------------------------------
echo "-- Ensuring access key for ${USER_NAME}"
EXISTING_KEY_COUNT=$(aws iam list-access-keys --user-name "${USER_NAME}" \
  --query 'length(AccessKeyMetadata)' --output text)

if [ "${EXISTING_KEY_COUNT}" -ge 2 ]; then
  OLDEST_KEY_ID=$(aws iam list-access-keys --user-name "${USER_NAME}" \
    --query 'sort_by(AccessKeyMetadata, &CreateDate)[0].AccessKeyId' --output text)
  echo "   ${USER_NAME} already has 2 access keys (IAM's max) — rotating out the oldest: ${OLDEST_KEY_ID}"
  aws iam delete-access-key --user-name "${USER_NAME}" --access-key-id "${OLDEST_KEY_ID}"
fi

KEY_JSON=$(aws iam create-access-key --user-name "${USER_NAME}")
ACCESS_KEY_ID=$(echo "${KEY_JSON}" | python3 -c "import json,sys;print(json.load(sys.stdin)['AccessKey']['AccessKeyId'])")
SECRET_ACCESS_KEY=$(echo "${KEY_JSON}" | python3 -c "import json,sys;print(json.load(sys.stdin)['AccessKey']['SecretAccessKey'])")

# ---------------------------------------------------------------------------
# 9. Write config for the Java app
# ---------------------------------------------------------------------------
ENV_FILE="${SCRIPT_DIR}/.env"
PROPS_FILE="$(cd "${SCRIPT_DIR}/.." && pwd)/src/main/resources/application.properties"

cat > "${ENV_FILE}" <<EOF
# Generated by setup.sh — do not commit this file
AWS_REGION=${REGION}
AWS_ACCOUNT_ID=${ACCOUNT_ID}
ENABLE_KMS=${ENABLE_KMS}
CARD_SERVICE_ROLE_ARN=${ROLE_ARN}
CARD_SERVICE_EXTERNAL_ID=${EXTERNAL_ID}
RECEIPTS_BUCKET=${BUCKET_NAME}
DYNAMODB_TABLE=${TABLE_NAME}
SQS_QUEUE_URL=${QUEUE_URL}
SNS_TOPIC_ARN=${TOPIC_ARN}
KMS_KEY_ALIAS=${KMS_ALIAS}
SERVICE_ACCOUNT_ACCESS_KEY_ID=${ACCESS_KEY_ID}
SERVICE_ACCOUNT_SECRET_ACCESS_KEY=${SECRET_ACCESS_KEY}
EOF

cat > "${PROPS_FILE}" <<EOF
# Generated by infrastructure/setup.sh
aws.region=${REGION}
card.service.role.arn=${ROLE_ARN}
card.service.external.id=${EXTERNAL_ID}
receipts.bucket=${BUCKET_NAME}
dynamodb.table=${TABLE_NAME}
sqs.queue.url=${QUEUE_URL}
sns.topic.arn=${TOPIC_ARN}
kms.key.alias=${KMS_ALIAS}
kms.enabled=${ENABLE_KMS}
EOF

echo ""
echo "== Setup complete (KMS: ${ENABLE_KMS}) =="
echo "Resource config written to:"
echo "  ${ENV_FILE}"
echo "  ${PROPS_FILE}"
echo ""
echo "If SERVICE_ACCOUNT_ACCESS_KEY_ID is populated in .env, export it before running the demo:"
echo "  export AWS_ACCESS_KEY_ID=\$(grep SERVICE_ACCOUNT_ACCESS_KEY_ID ${ENV_FILE} | cut -d= -f2)"
echo "  export AWS_SECRET_ACCESS_KEY=\$(grep SERVICE_ACCOUNT_SECRET_ACCESS_KEY ${ENV_FILE} | cut -d= -f2)"
echo "  export AWS_REGION=${REGION}"
echo ""
echo "Then run: ./run-demo.sh   (or ./run-webapp.sh for the dashboard)"
echo "(kms.enabled is already baked into application.properties from this run;"
echo " override per-run anytime with -Dkms.enabled=true|false without re-running setup.sh"
echo " — though if KMS was never created, -Dkms.enabled=true will fail at runtime.)"
