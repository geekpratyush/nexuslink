#!/usr/bin/env bash
# Provision the local CyberArk Conjur OSS fixture so ConjurLiveIT can run.
#
#   docker compose -f test-env/docker-compose.yml --profile conjur up -d
#   test-env/conjur/setup.sh
#   mvn -pl nexuslink-protocol-secrets test -Dnexuslink.it=true -Dtest=ConjurLiveIT \
#       -Dconjur.account=myConjurAccount -Dconjur.apiKey="$(cat test-env/conjur/.admin-api-key)"
#
# Creates account "myConjurAccount", loads a policy declaring variable nexus/db/password,
# sets its value, and writes the admin API key to test-env/conjur/.admin-api-key.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ACCOUNT="myConjurAccount"
CID="nexuslink-test-conjur-1"

echo "==> Waiting for Conjur to answer /info ..."
for _ in $(seq 1 40); do
  if curl -sf http://localhost:8083/info >/dev/null 2>&1; then break; fi
  sleep 2
done

echo "==> Creating account ${ACCOUNT} (idempotent) ..."
API_KEY="$(docker exec "${CID}" conjurctl account create "${ACCOUNT}" 2>/dev/null \
  | awk -F': ' '/API key for admin/ {print $2}')"
if [ -z "${API_KEY}" ]; then
  echo "    account already exists; rotating admin API key to recover it ..."
  # If the account already exists, authenticate is impossible without the key; recreate cleanly.
  docker exec "${CID}" conjurctl account delete "${ACCOUNT}" >/dev/null 2>&1 || true
  API_KEY="$(docker exec "${CID}" conjurctl account create "${ACCOUNT}" 2>/dev/null \
    | awk -F': ' '/API key for admin/ {print $2}')"
fi
[ -n "${API_KEY}" ] || { echo "!! could not obtain admin API key"; exit 1; }
echo "${API_KEY}" > "${DIR}/.admin-api-key"
echo "    admin API key written to test-env/conjur/.admin-api-key"

echo "==> Loading root policy (declares variable nexus/db/password) ..."
TOKEN="$(curl -sf --data "${API_KEY}" \
  "http://localhost:8083/authn/${ACCOUNT}/admin/authenticate" | base64 | tr -d '\r\n')"
curl -sf -X POST \
  -H "Authorization: Token token=\"${TOKEN}\"" \
  -H "Content-Type: text/yaml" \
  --data-binary @"${DIR}/policy.yml" \
  "http://localhost:8083/policies/${ACCOUNT}/policy/root" >/dev/null

echo "==> Setting secret value ..."
curl -sf -X POST \
  -H "Authorization: Token token=\"${TOKEN}\"" \
  --data "s3cr3t-value" \
  "http://localhost:8083/secrets/${ACCOUNT}/variable/nexus%2Fdb%2Fpassword" >/dev/null

echo "==> Done. Account=${ACCOUNT}  variable=nexus/db/password"
