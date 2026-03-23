#!/bin/bash
set -euo pipefail

# Seed data script — creates sample orders to populate dashboards
BASE_URL="${1:-http://localhost:8081}"

echo "=== Seeding Orders to $BASE_URL ==="

CURRENCIES=("USD" "EUR" "GBP")
AMOUNTS=(50 100 200 500 750 1000 1500 2000)

for i in $(seq 1 20); do
  CURRENCY=${CURRENCIES[$((RANDOM % 3))]}
  AMOUNT=${AMOUNTS[$((RANDOM % 8))]}
  USER="seed-user-$i"

  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/orders" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$USER\",\"amount\":$AMOUNT,\"currency\":\"$CURRENCY\"}")

  HTTP_CODE=$(echo "$RESPONSE" | tail -1)
  BODY=$(echo "$RESPONSE" | head -1)

  if [ "$HTTP_CODE" = "201" ]; then
    STATUS=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])" 2>/dev/null || echo "?")
    echo "  [$i/20] $USER: $AMOUNT $CURRENCY → $STATUS"
  else
    echo "  [$i/20] $USER: FAILED ($HTTP_CODE)"
  fi

  sleep 0.2
done

echo ""
echo "=== Seeding Complete ==="
