# Vault server configuration — prod docker-compose environment.
#
# Storage: file backend at /vault/data (Docker volume vault_data_prod).
# Secrets survive container restarts, unlike dev mode which is purely in-memory.
#
# TLS: disabled at the Vault listener level. In the Capitec deployment topology,
# TLS is terminated at the internal load balancer / reverse proxy in front of
# Vault, and all intra-cluster traffic stays on a private network segment.
# To terminate TLS at Vault itself, add tls_cert_file and tls_key_file under the
# listener block and remove tls_disable.
#
# Auto-unseal: not configured here (requires an external KMS — AWS KMS, Azure Key
# Vault, or GCP CKMS). The vault-init service performs Shamir unseal on startup.
# For a production deployment, wire in Vault's seal stanza pointing at the
# Capitec-managed KMS so the cluster unseals automatically after a restart.

storage "file" {
  path = "/vault/data"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = "true"
}

api_addr     = "http://vault:8200"
cluster_addr = "http://vault:8201"
ui           = false
