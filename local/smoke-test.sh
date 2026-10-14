#!/usr/bin/env bash
# Smoke test du stack local (docker compose) : appels de test + services vus par Jaeger.
# Usage : ./smoke-test.sh            (depuis le dossier local/, stack déjà démarré)
set -u
BASE="${BASE:-http://localhost:8080}"
JAEGER="${JAEGER:-http://localhost:16686}"

echo ">>> Attente du frontend ($BASE/health)"
for _ in $(seq 1 60); do
  curl -sf "$BASE/health" >/dev/null && break
  sleep 2
done

for item in laptop slow broken xyz mouse; do
  printf '\n== item=%s\n' "$item"
  curl -s -w "  [HTTP %{http_code} en %{time_total}s]\n" "$BASE/checkout?item=$item&qty=1"
done

echo
echo ">>> Attente de l'export des traces (10 s)"
sleep 10
echo ">>> Services connus de Jaeger :"
curl -s "$JAEGER/api/v3/services"
echo
echo "UI Jaeger : $JAEGER  (service = frontend, puis Find Traces)"
