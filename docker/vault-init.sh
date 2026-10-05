#!/usr/bin/env sh
# Runs as a one-shot container after Vault starts (see docker-compose.prod.yml: vault-init).
# Seeds application credentials into Vault so Spring Cloud Vault can inject them at startup.
#
# Token auth is used for local Docker Compose simulation. In a real deployment, AppRole
# (VAULT_AUTH_METHOD=APPROLE) with dynamically-generated role/secret IDs injected by the
# CI/CD secrets manager would replace the static VAULT_TOKEN approach here.

set -e

export VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
export VAULT_TOKEN="${VAULT_TOKEN:-dev-root-token}"

echo "==> [vault-init] Waiting for Vault..."
until vault status >/dev/null 2>&1; do sleep 1; done

# Write DB credentials. Spring Cloud Vault reads these as property names, so
# db.username / db.password map directly to ${db.username} / ${db.password}
# in application-prod.yml.
#
# Spring Cloud Vault loads both:
#   secret/{default-context} (application-level defaults)
#   secret/{default-context}/{profile} (profile-specific overrides)
# So we seed both the base path and the prod profile-specific path.
vault kv put secret/fraud-rule-engine \
  db.username=fraud \
  db.password=fraud

# Profile-specific secret for prod environment
vault kv put secret/fraud-rule-engine/prod \
  db.username=fraud \
  db.password=fraud

echo "==> [vault-init] Done. Seeded secret/fraud-rule-engine and secret/fraud-rule-engine/prod."
