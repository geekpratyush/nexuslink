#!/usr/bin/env bash
# Bring up the local test stack and run every gated live integration test against it.
# Usage: ./test-env/run-live-its.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="docker compose -f ${REPO_ROOT}/test-env/docker-compose.yml"

echo "==> Starting test stack..."
${COMPOSE} up -d

echo "==> Waiting for services to become healthy (up to 90s)..."
for _ in $(seq 1 30); do
  unhealthy=$(${COMPOSE} ps --format '{{.Health}}' | grep -c 'starting' || true)
  [ "${unhealthy}" -eq 0 ] && break
  sleep 3
done
${COMPOSE} ps

echo "==> Running compose-backed live ITs (-Dnexuslink.it=true)..."
cd "${REPO_ROOT}"
mvn test -Dnexuslink.it=true -DfailIfNoTests=false \
  -Dtest='JdbcLiveIT,RedisLiveIT,KafkaLiveIT,RabbitMqLiveIT,MqttLiveIT,LdapLiveIT,SnmpLiveIT,S3LiveIT,SqsSnsLiveIT,JmsLiveIT,AzureLiveIT,SftpLiveIT,FtpLiveIT,RestLiveIT,VaultLiveIT'

echo "==> Running GCS live IT (needs STORAGE_EMULATOR_HOST)..."
STORAGE_EMULATOR_HOST=http://localhost:4443 \
  mvn -pl nexuslink-protocol-gcs test -Dnexuslink.it=true -DfailIfNoTests=false -Dtest=GcsLiveIT

echo "==> Running MongoDB IT (Testcontainers)..."
mvn -pl nexuslink-protocol-mongo test -DrunMongoIT=true

echo "==> All live integration tests passed."
echo "    (stack still running; 'docker compose -f test-env/docker-compose.yml down -v' to stop)"
