# Credit Card Transaction Processing — AWS Java Demo

A working, runnable demonstration of a credit-card transaction pipeline built
on **Java 17 + AWS SDK for Java v2**, using real AWS services under a
least-privilege IAM design: **S3, DynamoDB, SQS, SNS, and KMS**.

This isn't a toy that logs to the console — it provisions and calls real
AWS resources in your own account, authenticates the way a production
service would (a scoped-down IAM user that assumes a role, never using
long-lived admin credentials), and includes both a CLI walkthrough and a
small web dashboard.

## Quick start

This project has four independent, runnable pieces. Pick what you need —
none of them require the others.

### 1. AWS demo (real S3/DynamoDB/SQS/SNS/KMS in your own account)

```bash
export AWS_REGION=us-east-1
./infrastructure/setup.sh                 # provisions everything, ~1 min

export AWS_ACCESS_KEY_ID=$(grep SERVICE_ACCOUNT_ACCESS_KEY_ID infrastructure/.env | cut -d= -f2-)
export AWS_SECRET_ACCESS_KEY=$(grep SERVICE_ACCOUNT_SECRET_ACCESS_KEY infrastructure/.env | cut -d= -f2-)
export AWS_REGION=us-east-1

./run-demo.sh                              # authorize → fraud-score → settle
```
Costs ~$1/month (the KMS key) until you run `./infrastructure/teardown.sh` — see **Cost** below.

### 2. Web dashboard (same AWS pipeline, browser UI)

```bash
PORT=8080 ./run-webapp.sh
# then open http://localhost:8080
```
Requires step 1 to have been run first (needs the same exported credentials).

### 3. Kafka Streams velocity check (100% local, free, no AWS at all)

```bash
docker compose up -d       # starts a local Kafka broker
./run-kafka-demo.sh         # publishes a burst of transactions, watch for a VELOCITY ALERT
docker compose down         # stop the broker when done
```

### 4. Capacity planning calculators (zero setup — just open them)

```bash
open tools/capacity-calculator.html            # JVM / Little's Law
open tools/storage-capacity-calculator.html    # storage tiers & cost
```

Everything below explains *why* each piece is built the way it is — the
architecture, the IAM design, the bugs found and fixed along the way, and
the full detail behind each command above.

## What this demonstrates

- **Authorization**: idempotent writes to DynamoDB (a retried request is
  safely rejected, never double-processed)
- **Encryption**: card numbers encrypted with a customer-managed KMS key
  before they're ever persisted — with a free, KMS-off mode for comparison
- **Asynchronous fraud scoring**: SQS decouples the authorization path from
  a background fraud-scoring worker
- **Notifications**: SNS publishes fraud alerts and settlement-batch summaries
- **Settlement**: a batch job that mirrors the real-world "authorize now,
  settle later" card lifecycle — querying a DynamoDB GSI for everything
  still `AUTHORIZED`, never a full-table scan
- **IAM least privilege**: a service-account user that can only assume a
  role; the role's policy is scoped to specific resource ARNs, specific
  actions, and (for KMS) a required encryption context

## Architecture

```
Your machine (Demo.java / WebApp.java)
        │  assumes role via STS
        ▼
IAM: svc-card-txn-demo (user) ──assumes──> CardTransactionServiceRole
        │
        ├── S3        (receipts/, settlements/)
        ├── DynamoDB  (transactions table + status-index GSI)
        ├── SQS       (fraud-check queue + dead-letter queue)
        ├── SNS       (fraud alerts + settlement notifications)
        └── KMS       (encrypts the card PAN; optional — see "KMS toggle" below)
```

The service account has almost no permissions of its own — its only job is
`sts:AssumeRole`. All actual work happens under the temporary credentials
issued for `CardTransactionServiceRole`, whose policy is scoped to exactly
the resources this app needs (no `dynamodb:Scan`, no `s3:DeleteObject`, no
wildcard resources).

## Repository layout

```
credit-card-aws-demo/
├── pom.xml                          Maven project (Java 17, AWS SDK v2 BOM)
├── run-demo.sh                      Wrapper: runs the CLI walkthrough
├── run-webapp.sh                    Wrapper: runs the web dashboard
├── infrastructure/
│   ├── setup.sh                     Provisions everything in AWS (idempotent)
│   ├── teardown.sh                  Deletes everything setup.sh created
│   └── iam/
│       ├── trust-policy-service-role.json
│       ├── service-account-assume-role-policy.json
│       ├── permissions-boundary.json
│       ├── card-service-permissions-policy-kms.json
│       └── card-service-permissions-policy-no-kms.json
└── src/main/
    ├── java/com/cardco/
    │   ├── Demo.java                 CLI entrypoint
    │   ├── WebApp.java                Dashboard entrypoint
    │   ├── auth/                     STS assume-role credential handling
    │   ├── client/                   Cached AWS SDK client factory
    │   ├── config/                   Reads infrastructure/setup.sh's output
    │   ├── model/                    CardTransaction (DynamoDB bean)
    │   ├── repository/               DynamoDB access (idempotent writes, GSI query)
    │   ├── security/                 KMS PAN encryption (toggleable)
    │   ├── storage/                  S3 receipt archiving
    │   ├── messaging/                SQS fraud-check queue
    │   ├── notification/             SNS publishing
    │   ├── service/                  Business logic: authorization + settlement
    │   └── web/                      Dashboard backend (Javalin REST API)
    └── resources/
        ├── application.properties    Generated by setup.sh — do not commit real values
        └── public/                   Dashboard frontend (HTML/CSS/JS)
```

## Prerequisites

| Tool | Check | Notes |
|---|---|---|
| Java 17+ | `java -version` | |
| Maven 3.8+ | `mvn -version` | |
| AWS CLI v2 | `aws --version` | run `aws configure` first |
| `python3` | `python3 --version` | used by `setup.sh` to parse JSON |
| An AWS account | | your CLI identity needs IAM admin rights to *provision* — this is separate from the scoped-down identity the app actually runs as |

## Setup

```bash
git clone <this-repo>
cd credit-card-aws-demo
export AWS_REGION=us-east-1   # or your preferred region
./infrastructure/setup.sh
```

This creates a KMS key, an S3 bucket, a DynamoDB table (with a `status-index`
GSI for settlement queries), an SQS queue + dead-letter queue, an SNS topic,
the `svc-card-txn-demo` IAM user, and the `CardTransactionServiceRole` it
assumes — then writes the real resource names into `infrastructure/.env`
and `src/main/resources/application.properties`. Safe to re-run if a
previous attempt failed partway through.

**Free mode**: if you'd rather not pay the ~$1/month KMS key costs, run:
```bash
ENABLE_KMS=false ./infrastructure/setup.sh
```
This skips creating a KMS key entirely; S3 uses its free default `AES256`
encryption instead, and the card PAN is simply never persisted (not
"weakly encrypted" — genuinely not stored at all). See **KMS toggle**
below for how this plays out at runtime.

Export the service-account credentials the script prints:
```bash
export AWS_ACCESS_KEY_ID=$(grep SERVICE_ACCOUNT_ACCESS_KEY_ID infrastructure/.env | cut -d= -f2-)
export AWS_SECRET_ACCESS_KEY=$(grep SERVICE_ACCOUNT_SECRET_ACCESS_KEY infrastructure/.env | cut -d= -f2-)
export AWS_REGION=us-east-1
```
(Note the `-f2-`, not `-f2` — secret keys can contain `=` characters, which
a plain `-f2` would silently truncate.)

## Running it

```bash
./run-demo.sh      # CLI walkthrough: authorize, retry, fraud-check, settle
./run-webapp.sh     # Web dashboard at http://localhost:7000
```

Both wrap `mvn compile exec:exec` (a forked JVM process) rather than
`exec:java`, so the app shuts down cleanly with no lingering AWS SDK
background threads or Maven warnings on exit.

Useful variants:
```bash
EXTRA_JAVA_OPTS="-Dkms.enabled=false" ./run-demo.sh   # force no-KMS mode
PORT=8080 ./run-webapp.sh                              # dashboard on a different port
```

### What `./run-demo.sh` does

1. Authorizes a transaction (writes to DynamoDB, encrypts the PAN, publishes to SQS, archives a receipt to S3)
2. Retries the *same* transaction — proves the idempotency guard works (same transaction ID comes back, marked `DUPLICATE`, no second receipt)
3. Authorizes a second, high-value transaction
4. Polls SQS and fraud-scores whatever's waiting — publishes an SNS alert if the amount is high-risk
5. Runs a settlement batch — queries the `status-index` GSI for everything still `AUTHORIZED`, marks it `SETTLED`, archives a batch summary to S3, publishes an SNS notification
6. Proves the KMS round-trip (or explains why it's skipped, in no-KMS mode)

### What `./run-webapp.sh` gives you

A small dashboard: a form to authorize transactions, a live ledger table
that updates as transactions move through the pipeline (recorded → queued →
scored → alerted), and a "Run settlement batch" button. Backed by the exact
same AWS resources as the CLI demo — this isn't a separate mock.

## The KMS toggle, in more detail

| | KMS mode (default) | No-KMS mode |
|---|---|---|
| Card PAN in DynamoDB | Encrypted (KMS ciphertext) | Not stored at all |
| S3 receipt encryption | `SSE-KMS` (your key) | `SSE-S3` (`AES256`, AWS-managed) |
| IAM policy | Includes a `kms:Encrypt`/`Decrypt` statement, scoped to one key + a required encryption context | No KMS statement at all |
| Cost | ~$1/month for the key | $0 |

Switching modes after the fact requires re-running `setup.sh` with a
different `ENABLE_KMS` value (it provisions different resources), then
setting `kms.enabled` to match in `application.properties` or via
`-Dkms.enabled=`.

## Understanding the IAM design

- **`svc-card-txn-demo` (IAM user)** — the long-lived identity. Its only
  permission is `sts:AssumeRole` on `CardTransactionServiceRole`. If these
  credentials ever leaked, the blast radius is "can ask to become the
  role" — not direct access to any data.
- **`CardTransactionServiceRole` (IAM role)** — assumed via STS, producing
  temporary credentials that auto-expire (1 hour). All actual AWS calls in
  the app run under this identity, never the user's own.
- **Trust policy** — restricts who can assume the role to that one user
  ARN, plus a required `sts:ExternalId` (a defense against the
  "confused deputy" problem, standard practice even though this demo is
  single-account).
- **Permissions policy** — scoped to specific resource ARNs and actions:
  `s3:PutObject`/`GetObject` only under `receipts/*` and `settlements/*` in
  one bucket; `dynamodb:PutItem`/`GetItem`/`UpdateItem`/`Query` only on one
  table (plus a separate `Query` grant for the GSI specifically);
  `sqs:SendMessage`/`ReceiveMessage`/`DeleteMessage` only on one queue;
  `sns:Publish` only on one topic; `kms:Encrypt`/`Decrypt`/`GenerateDataKey`
  only on one key, only with a matching encryption context. Notably absent:
  `dynamodb:Scan`, `s3:DeleteObject`, `sns:Subscribe`, any wildcard resource.
- **Permissions boundary** — a hard ceiling on the role, independent of
  whatever its attached policy says, so a future accidental over-broad
  policy attachment still can't exceed a fixed action list.

## Seeing the evidence directly

- **DynamoDB**: open the table in the AWS console → Explore table items —
  you'll see the `encryptedPan` field as ciphertext, never the real card
  number.
- **Decrypt it yourself**: `aws kms decrypt --ciphertext-blob <value> --encryption-context purpose=card-authorization --query Plaintext --output text | base64 --decode`
- **S3**: the `receipts/` and `settlements/` prefixes hold JSON copies of
  each transaction / batch summary, encrypted at rest.
- **CloudTrail**: filter Event history by event name `Decrypt` (or anything
  else) to see exactly which identity made each call — you can tell the
  app's calls (`userIdentity.type: AssumedRole`, an `ASIA`-prefixed key,
  `sessionIssuer.arn` pointing at the role) apart from your own manual CLI
  calls (`IAMUser`, `AKIA`-prefixed key, no `invokedBy`) or console actions
  (`IAMUser`, `ASIA`-prefixed, `invokedBy: fas.s3.amazonaws.com`).
- **CloudWatch**: `AWS/DynamoDB` → Table Operation Metrics shows real
  write/latency graphs from your own test runs.

## Cost

The only resource here with an ongoing cost is the **KMS key**, at
**~$1/month**, prorated hourly, for as long as it exists (run in no-KMS
mode to avoid this entirely). S3, DynamoDB (pay-per-request), SQS, and SNS
are effectively free at demo scale — a few cents at most even with heavy
testing. IAM is always free.

**Important**: `teardown.sh`'s KMS deletion has a mandatory 7-day waiting
period (`schedule-key-deletion`) — the key keeps billing during that
window, so tear down as soon as you're done rather than right before a
billing cycle closes.

## Bonus: Kafka Streams velocity check (local only, no AWS cost)

This is a genuinely separate addition from the AWS pipeline above — it runs
against a local Kafka broker via Docker, touches no AWS resources, and
costs nothing. It demonstrates a fraud-detection pattern the SQS-based
pipeline structurally cannot express: a **windowed velocity check** — "has
this card authorized 3+ transactions in the last 30 seconds?" SQS can only
ever look at one message at a time; Kafka Streams maintains a running,
time-bounded count per card automatically.

```bash
docker compose up -d      # starts a single-node Kafka broker (KRaft mode)
./run-kafka-demo.sh        # builds the topology, publishes a burst of same-card
                            # transactions, and shows a VELOCITY ALERT fire live
docker compose down        # stop the broker when done
```

See `src/main/java/com/cardco/kafka/` — `VelocityCheckTopology.java` is the
actual Kafka Streams DSL (`groupByKey().windowedBy(...).count()`), and
`VelocityCheckDemo.java` runs it end-to-end. In a real system, the
authorization service would publish to both SQS (existing fraud-scoring/
settlement flow) and this Kafka topic (velocity checks) side by side —
this demo is deliberately kept separate rather than wired into
`CardAuthorizationService`, so the two stories don't get tangled together.

## Bonus: capacity planning calculators

Two standalone, dependency-free reference tools (open either directly in
any browser, no server needed):

- **`tools/capacity-calculator.html`** — applies Little's Law
  (`concurrency = throughput × latency`) to compare three I/O models side
  by side: blocking platform threads, blocking virtual threads (Java 21+),
  and non-blocking/async. Bakes in the caveats around claiming an
  instance-count reduction from virtual threads — downstream dependency
  capacity, CPU-bound vs I/O-bound work, blast radius, and the need for
  real load testing.
- **`tools/storage-capacity-calculator.html`** — projects total storage
  footprint over a retention period (accounting for growth, secondary-index
  overhead, and replication), then compares monthly cost across a hot
  (DynamoDB), warm (S3 Standard), and cold (S3 Glacier Deep Archive)
  storage tier — directly relevant to this project's own `status-index`
  GSI and `receipts`/`settlements` S3 prefixes.

## Starting fresh between runs

By default, every run adds to what's already there — DynamoDB accumulates
transactions, S3 accumulates receipts, and SQS can carry a backlog of
unprocessed messages from earlier runs into your next one. This is realistic
(a real system doesn't wipe itself between requests) but can make a single
demo run confusing to reason about — e.g., a settlement batch settling more
transactions than you just created, since it correctly picks up every
`AUTHORIZED` transaction, including old ones.

To wipe all **data** (not infrastructure) and start clean:

```bash
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY   # needs admin, not the service account
./infrastructure/reset-data.sh
```

This deletes and recreates the DynamoDB table (same schema + GSI), purges
the SQS queue, and clears the S3 `receipts/`/`settlements/` prefixes. It
does **not** touch the IAM role/user, KMS key, or SNS topic — no need to
re-run `setup.sh` afterward, just re-export the service-account credentials
and run the demo again.

(The dashboard's ledger table resets automatically just by restarting
`./run-webapp.sh` — that data only ever lived in memory, not AWS.)

## Tearing down

```bash
./infrastructure/teardown.sh
```

Deletes everything `setup.sh` created — bucket (and its contents), table,
queue, topic, KMS key (scheduled), role, user, and its access keys. Safe to
run even if some resources don't exist.

## Troubleshooting notes from building this

A few things that came up during development, in case they help:

- **Port 7000/5000 already in use on macOS**: these are commonly claimed by
  the system's AirPlay Receiver service, which respawns if killed. Just run
  on a different port: `PORT=8080 ./run-webapp.sh`.
- **AWS CLI output seems to hang**: check you haven't landed inside a pager
  (`less`) — press `q` to exit, or set `export AWS_PAGER=""` for the session.
- **Adding a GSI to an existing table can take longer than expected**
  (sometimes several minutes even for a tiny table) — this is normal AWS
  provisioning variance, not a sign of failure. Watch `IndexStatus` via
  `describe-table`, not `ItemCount` (which only refreshes roughly every 6
  hours and isn't a live progress indicator).
- **`SignatureDoesNotMatch` or auth errors after exporting credentials**:
  double check you used `cut -d= -f2-` (trailing dash) and not `-f2` — AWS
  secret keys can contain `=` characters that a plain `-f2` truncates.

## A note on scope

This is an interview-prep / portfolio project, not a production payments
system. A few things a real system would add that this deliberately
doesn't: PCI-DSS-scope network isolation, real card-network integration
(this uses hardcoded test PANs), multi-region failover, provisioned
(rather than pay-per-request) capacity planning, and proper observability
(structured logging, alarms, dashboards) beyond what's shown here for
demonstration purposes.
