#!/usr/bin/env bash
# Prints an access token for one of the demo clients in dev/keycloak/ledger-realm.json.
# Usage: dev/token.sh ledger-agent agent-secret
set -euo pipefail
client_id="${1:?client id}"
client_secret="${2:?client secret}"
issuer="${LEDGER_MCP_ISSUER_URI:-http://localhost:8180/realms/ledger}"
curl -fsS -X POST "$issuer/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d "client_id=$client_id" -d "client_secret=$client_secret" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
