#!/usr/bin/env bash
# Runs once, on the primary, only against a fresh (empty) data volume — standard
# docker-entrypoint-initdb.d behaviour for the official postgres image. Creates the
# REPLICATION role postgres-replica-entrypoint.sh's pg_basebackup authenticates as, and
# opens pg_hba.conf to replication connections from anywhere on the compose network
# (0.0.0.0/0 is fine here: this is an internal Docker bridge network, not exposed beyond
# the host — the same trust boundary every other service on this network already has).
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE ${POSTGRES_REPLICATION_USER:-replicator}
        WITH REPLICATION LOGIN PASSWORD '${POSTGRES_REPLICATION_PASSWORD:-replicator}';
EOSQL

echo "host replication ${POSTGRES_REPLICATION_USER:-replicator} 0.0.0.0/0 md5" >> "$PGDATA/pg_hba.conf"
