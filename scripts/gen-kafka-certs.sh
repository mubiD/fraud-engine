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

# On Git Bash/MSYS (Windows), a -subj value like "/C=ZA/ST=.../CN=..." isn't a file path, but
# MSYS's argv translation can't tell and mangles it into something openssl's DN parser can't
# read. MSYS2_ARG_CONV_EXCL="/C=" tells MSYS to leave just that one argument (any value
# starting with "/C=") untouched, while every genuine path argument in the same command
# (-out/-in/-key/-CA/...) still gets translated normally — unlike MSYS_NO_PATHCONV=1, which
# disables translation for the whole command and broke every absolute path argument instead
# (openssl.exe is a native, non-MSYS binary and can't resolve a raw untranslated POSIX path
# like "/c/Users/.../ca.key"). Found and fixed live 2026-09-08.
export MSYS2_ARG_CONV_EXCL="/C="

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

  # Sign with CA — add SANs so the cert covers the Docker hostname and localhost.
  # A real temp file, not <(process substitution): the latter passes openssl a
  # "/proc/<pid>/fd/<n>"-style path, which MSYS's normal path translation also mangles,
  # the same class of problem as the -subj value above but with no equivalent workaround
  # (it's not an argument we control the prefix of). Found live 2026-09-08.
  EXTFILE="$(mktemp)"
  printf "subjectAltName=DNS:%s,DNS:localhost,IP:127.0.0.1" "$broker" > "$EXTFILE"
  openssl x509 -req \
    -in  "$CERTS_DIR/$broker.csr" \
    -CA  "$CERTS_DIR/ca.crt" \
    -CAkey "$CERTS_DIR/ca.key" \
    -CAcreateserial \
    -out "$CERTS_DIR/$broker.crt" \
    -days "$VALIDITY_DAYS" \
    -extfile "$EXTFILE"
  rm -f "$EXTFILE"

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

# cp-kafka's own SSL setup ("dub ensure") requires KAFKA_SSL_KEYSTORE_FILENAME/
# KAFKA_SSL_KEYSTORE_CREDENTIALS (a *file* containing the password, referenced by filename
# relative to /etc/kafka/secrets) once SASL_SSL is in the listener security protocol map —
# the KAFKA_SSL_KEYSTORE_LOCATION/_PASSWORD vars alone aren't enough to satisfy that
# pre-flight check ("KAFKA_SSL_KEYSTORE_FILENAME is required", found live 2026-09-08). Same
# password for every broker's keystore and the shared truststore, so one file each suffices.
printf '%s' "$KS_PASS" > "$CERTS_DIR/keystore_creds"
printf '%s' "$KS_PASS" > "$CERTS_DIR/key_creds"
printf '%s' "$TS_PASS" > "$CERTS_DIR/truststore_creds"

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
