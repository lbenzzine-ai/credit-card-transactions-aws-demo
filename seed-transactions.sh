#!/usr/bin/env bash
#
# Posts a batch of realistic, varied transactions to the running dashboard
# (./run-webapp.sh), so the ledger fills up with something more interesting
# than a handful of manual test transactions.
#
# Usage:
#   ./seed-transactions.sh                 # 20 transactions against http://localhost:8080
#   COUNT=50 ./seed-transactions.sh         # a different count
#   PORT=7000 ./seed-transactions.sh        # a different port
#
# Requires the dashboard to already be running (PORT=8080 ./run-webapp.sh).

set -euo pipefail

PORT="${PORT:-8080}"
COUNT="${COUNT:-20}"
BASE_URL="http://localhost:${PORT}"

MERCHANTS=(
  "merchant-coffee-shop-01"
  "merchant-electronics-99"
  "merchant-grocery-12"
  "merchant-bookstore-07"
  "merchant-gas-station-22"
  "merchant-restaurant-33"
  "merchant-pharmacy-44"
  "merchant-clothing-55"
  "merchant-hardware-18"
  "merchant-pet-supplies-29"
)

PANS=(
  "4111111111111234"
  "4111111111119876"
  "4111111111115555"
  "4111111111112222"
  "4111111111118888"
)

echo "Seeding ${COUNT} transactions against ${BASE_URL} ..."

if ! curl -s -o /dev/null -w "" "${BASE_URL}/api/health" 2>/dev/null; then
  echo "ERROR: dashboard doesn't seem to be running at ${BASE_URL}."
  echo "Start it first: PORT=${PORT} ./run-webapp.sh"
  exit 1
fi

for i in $(seq 1 "${COUNT}"); do
  merchant="${MERCHANTS[$((RANDOM % ${#MERCHANTS[@]}))]}"
  pan="${PANS[$((RANDOM % ${#PANS[@]}))]}"

  # ~15% of transactions are high-value (>2500) to trigger fraud alerts;
  # the rest are ordinary small purchases.
  if (( RANDOM % 100 < 15 )); then
    amount=$(awk -v seed="$RANDOM" 'BEGIN{srand(seed); printf "%.2f", 2600 + rand() * 5000}')
  else
    amount=$(awk -v seed="$RANDOM" 'BEGIN{srand(seed); printf "%.2f", 2 + rand() * 150}')
  fi

  response=$(curl -s -X POST "${BASE_URL}/api/transactions" \
    -H "Content-Type: application/json" \
    -d "{\"merchantId\":\"${merchant}\",\"pan\":\"${pan}\",\"amount\":\"${amount}\",\"currency\":\"USD\"}")

  echo "[$i/${COUNT}] ${merchant}  \$${amount}  -> ${response}"

  sleep 0.4
done

echo ""
echo "Done. Open ${BASE_URL} to watch them move through the pipeline,"
echo "or run the settlement batch from the dashboard once they've been scored."
