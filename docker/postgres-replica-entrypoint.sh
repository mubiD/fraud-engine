#!/usr/bin/env bash
# Wraps the stock postgres image's entrypoint: on an empty data volume, clones the primary
# via pg_basebackup (in standby mode, -R) before handing off, instead of letting the
# image's normal entrypoint run initdb and bootstrap a fresh, empty, unrelated cluster.
# docker-entrypoint-initdb.d doesn't cover this case: those scripts only run *after*
# initdb, not instead of it, hence a custom entrypoint rather than an init script.
#
# On every later restart the data directory already exists (with standby.signal, written
# by pg_basebackup's -R flag), so the stock entrypoint starts it directly in standby/
# streaming-replication mode. This script only does anything on the very first start.
set -euo pipefail

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
PRIMARY_HOST="${PRIMARY_HOST:-postgres}"
REPLICATION_USER="${REPLICATION_USER:-replicator}"
REPLICATION_PASSWORD="${REPLICATION_PASSWORD:-replicator}"

if [ -z "$(ls -A "$PGDATA" 2>/dev/null)" ]; then
    echo "==> [postgres-replica] Empty data directory — waiting for primary ($PRIMARY_HOST)..."

    until PGPASSWORD="$REPLICATION_PASSWORD" pg_isready -h "$PRIMARY_HOST" -U "$REPLICATION_USER" >/dev/null 2>&1; do
        sleep 2
    done

    echo "==> [postgres-replica] Cloning primary via pg_basebackup..."
    PGPASSWORD="$REPLICATION_PASSWORD" pg_basebackup \
        -h "$PRIMARY_HOST" \
        -D "$PGDATA" \
        -U "$REPLICATION_USER" \
        -Fp -Xs -P -R

    chmod 700 "$PGDATA"
    echo "==> [postgres-replica] Base backup complete — starting in standby mode."
else
    echo "==> [postgres-replica] Existing data directory found — starting normally (standby.signal already present)."
fi

exec docker-entrypoint.sh postgres
