#!/usr/bin/env bash
# Generates a self-signed CA, per-broker PKCS12 keystores, and a shared client
# truststore for the prod Kafka cluster.
#
# Usage: ./scripts/gen-kafka-certs.sh
#
# The keystore password is read from KAFKA_SSL_KEYSTORE_PASSWORD (default: changeit).
# Set KAFKA_SSL_KEYSTORE_PASSWORD and KAFKA_SSL_TRUSTSTORE_PASSWORD in your .env or
# shell before running, and never commit those values.
#
# In production: replace these self-signed certs with ones issued by the Acme
# internal CA. Pass KAFKA_SSL_KEYSTORE_PASSWORD and KAFKA_SSL_TRUSTSTORE_PASSWORD
# into containers via Vault (already wired in application.yml) rather than .env files.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CERTS_DIR="$REPO_ROOT/certs/prod"
KS_PASS="${KAFKA_SSL_KEYSTORE_PASSWORD:-changeit}"
TS_PASS="${KAFKA_SSL_TRUSTSTORE_PASSWORD:-changeit}"
VALIDITY_DAYS=365
BROKERS=("kafka1" "kafka2" "kafka3")

mkdir -p "$CERTS_DIR"

# ── 1. Certificate Authority ────────────────────────────────────────────────

echo "==> Generating CA key and self-signed certificate..."
openssl genrsa -out "$CERTS_DIR/ca.key" 4096 2>/dev/null
openssl req -new -x509 \
  -key  "$CERTS_DIR/ca.key" \
  -out  "$CERTS_DIR/ca.crt" \
  -days "$VALIDITY_DAYS" \
  -subj "/C=ZA/ST=Western Cape/L=Cape Town/O=Acme Bank/OU=Fraud Engine/CN=FraudEngine-KafkaCA"

# ── 2. Per-broker keypairs ───────────────────────────────────────────────────

echo "==> Generating per-broker keystores..."
for broker in "${BROKERS[@]}"; do
  echo "    $broker"

  # Private key
  openssl genrsa -out "$CERTS_DIR/$broker.key" 2048 2>/dev/null

  # Certificate signing request
  openssl req -new \
    -key "$CERTS_DIR/$broker.key" \
    -out "$CERTS_DIR/$broker.csr" \
    -subj "/C=ZA/O=Acme Bank/OU=Fraud Engine/CN=$broker"

  # Sign with CA — add SANs so the cert covers the Docker hostname and localhost
  openssl x509 -req \
    -in  "$CERTS_DIR/$broker.csr" \
    -CA  "$CERTS_DIR/ca.crt" \
    -CAkey "$CERTS_DIR/ca.key" \
    -CAcreateserial \
    -out "$CERTS_DIR/$broker.crt" \
    -days "$VALIDITY_DAYS" \
    -extfile <(printf "subjectAltName=DNS:%s,DNS:localhost,IP:127.0.0.1" "$broker")

  # Package into PKCS12 keystore (private key + signed cert + CA chain)
  openssl pkcs12 -export \
    -in     "$CERTS_DIR/$broker.crt" \
    -inkey  "$CERTS_DIR/$broker.key" \
    -CAfile "$CERTS_DIR/ca.crt" \
    -caname ca-root \
    -name   "$broker" \
    -out    "$CERTS_DIR/$broker.keystore.p12" \
    -passout "pass:$KS_PASS"
done

# ── 3. Client truststore ─────────────────────────────────────────────────────
# Contains only the CA cert — enough for any client to verify broker identity.

echo "==> Creating shared client truststore..."
keytool -importcert -noprompt \
  -alias   ca-root \
  -file    "$CERTS_DIR/ca.crt" \
  -keystore "$CERTS_DIR/kafka.truststore.p12" \
  -storetype PKCS12 \
  -storepass "$TS_PASS"

# ── 4. Cleanup intermediate files ───────────────────────────────────────────
# Private keys are embedded in the PKCS12 keystores; loose copies add no value.
rm -f "$CERTS_DIR"/*.key "$CERTS_DIR"/*.csr "$CERTS_DIR"/*.srl

echo ""
echo "  Certificates written to: $CERTS_DIR"
echo "  Keystore password  : \$KAFKA_SSL_KEYSTORE_PASSWORD  (current: $KS_PASS)"
echo "  Truststore password: \$KAFKA_SSL_TRUSTSTORE_PASSWORD (current: $TS_PASS)"
echo ""
echo "  Files:"
ls -1 "$CERTS_DIR"
echo ""
echo "  IMPORTANT: These are self-signed certs for showcase/dev purposes."
echo "  For production, obtain certs from the Acme internal CA and"
echo "  inject passwords via Vault. Never commit certs or passwords to git."
