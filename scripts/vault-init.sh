#!/bin/sh
# One-time Vault initialisation for the prod environment.
# Runs as the vault-init service in docker-compose.prod.yml.
#
# IDEMPOTENT — safe to run on every compose up:
#   First run  : init → unseal → configure AppRole → seed secrets
#   Later runs : read saved unseal key → unseal only (if sealed after restart)
#
# Output: /vault/init/init-output.txt
#   Contains the unseal key, root token, role-id, and secret-id.
#   This file is mounted to a Docker volume (never written to the repo).
#
# SECURITY NOTE (key-shares=1 is a showcase simplification):
#   In production, use -key-shares=5 -key-threshold=3 and distribute the five
#   unseal key shards to separate operator workstations. No single person should
#   hold a quorum. Alternatively, replace Shamir unseal entirely with Vault's
#   auto-unseal pointing at the Capitec-managed KMS.

set -e

VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
INIT_FILE="/vault/init/init-output.txt"
export VAULT_ADDR

# ── Wait for Vault API to respond ────────────────────────────────────────────

echo "==> Waiting for Vault API at $VAULT_ADDR..."
retries=30
until vault status 2>/dev/null; ret=$?; [ "$ret" -eq 0 ] || [ "$ret" -eq 2 ]; do
  retries=$((retries - 1))
  if [ "$retries" -le 0 ]; then
    echo "ERROR: Vault did not become reachable in time." >&2
    exit 1
  fi
  echo "    not ready (exit $ret), retrying in 3s..."
  sleep 3
done
echo "    Vault API is up."

# ── Already initialised — unseal only ────────────────────────────────────────

if vault status 2>/dev/null | grep -q "Initialized.*true"; then
  echo "==> Vault already initialised."
  if vault status 2>/dev/null | grep -q "Sealed.*true"; then
    echo "==> Vault is sealed — unsealing from saved key..."
    if [ ! -f "$INIT_FILE" ]; then
      echo "ERROR: Vault is sealed but $INIT_FILE is missing." >&2
      echo "       Provide the unseal key manually: vault operator unseal <key>" >&2
      exit 1
    fi
    UNSEAL_KEY=$(grep "Unseal Key 1:" "$INIT_FILE" | awk '{print $NF}')
    vault operator unseal "$UNSEAL_KEY"
    echo "==> Vault unsealed."
  else
    echo "==> Vault is already unsealed."
  fi
  echo "==> Init complete (idempotent run)."
  exit 0
fi

# ── First-time initialisation ─────────────────────────────────────────────────

echo "==> Initialising Vault (key-shares=1; use ≥3 in production)..."
mkdir -p "$(dirname "$INIT_FILE")"
vault operator init -key-shares=1 -key-threshold=1 > "$INIT_FILE"

UNSEAL_KEY=$(grep "Unseal Key 1:"      "$INIT_FILE" | awk '{print $NF}')
ROOT_TOKEN=$(grep "Initial Root Token:" "$INIT_FILE" | awk '{print $NF}')

echo "==> Unsealing Vault..."
vault operator unseal "$UNSEAL_KEY"

export VAULT_TOKEN="$ROOT_TOKEN"

# ── KV v2 secrets engine ──────────────────────────────────────────────────────

echo "==> Enabling KV v2 secrets engine at secret/..."
vault secrets enable -version=2 -path=secret kv 2>/dev/null || \
  echo "    (already enabled)"

# ── Seed fraud-engine secrets ─────────────────────────────────────────────────
# Placeholder values — replace with real secrets before go-live or supply them
# as env vars (DB_PASSWORD, KAFKA_CLIENT_PASSWORD, etc.) injected by the operator.

echo "==> Seeding secret/fraud-engine..."
vault kv put secret/fraud-engine \
  db-password="${DB_PASSWORD:-CHANGE_ME_DB_PASSWORD}" \
  kafka-client-password="${KAFKA_CLIENT_PASSWORD:-CHANGE_ME_KAFKA_PASSWORD}" \
  kafka-ssl-truststore-password="${KAFKA_SSL_TRUSTSTORE_PASSWORD:-CHANGE_ME_TS_PASSWORD}" \
  idp-base-uri="${FRAUD_IDP_URI:-https://idp.capitecbank.co.za/oauth2/default}"

# ── AppRole authentication ────────────────────────────────────────────────────
# AppRole gives the fraud-engine a non-root, policy-scoped identity.
# The role-id is static and semi-public; the secret-id is the authenticating credential.

echo "==> Enabling AppRole auth method..."
vault auth enable approle 2>/dev/null || echo "    (already enabled)"

echo "==> Writing fraud-engine policy (read-only access to its own secret path)..."
vault policy write fraud-engine - <<'POLICY'
path "secret/data/fraud-engine" {
  capabilities = ["read"]
}
path "secret/data/fraud-engine/*" {
  capabilities = ["read"]
}
POLICY

echo "==> Creating fraud-engine AppRole role..."
vault write auth/approle/role/fraud-engine \
  token_policies="fraud-engine" \
  token_ttl=1h \
  token_max_ttl=4h \
  secret_id_ttl=0

ROLE_ID=$(vault read   -field=role_id   auth/approle/role/fraud-engine/role-id)
SECRET_ID=$(vault write -f -field=secret_id auth/approle/role/fraud-engine/secret-id)

printf 'Role ID  : %s\n' "$ROLE_ID"   >> "$INIT_FILE"
printf 'Secret ID: %s\n' "$SECRET_ID" >> "$INIT_FILE"

echo ""
echo "┌──────────────────────────────────────────────────────────────┐"
echo "│  Vault initialisation complete                                │"
echo "├──────────────────────────────────────────────────────────────┤"
printf "│  Role ID   : %-49s│\n" "$ROLE_ID"
printf "│  Secret ID : %-49s│\n" "$SECRET_ID"
echo "├──────────────────────────────────────────────────────────────┤"
echo "│  Export these before starting fraud-engine:                  │"
echo "│    export VAULT_ROLE_ID=<role-id>                            │"
echo "│    export VAULT_SECRET_ID=<secret-id>                        │"
echo "│  Full output saved to: /vault/init/init-output.txt           │"
echo "│  SECURE that file — it contains the unseal key + root token. │"
echo "└──────────────────────────────────────────────────────────────┘"
