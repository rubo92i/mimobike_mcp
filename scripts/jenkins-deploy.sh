#!/usr/bin/env bash
#
# Deploy script for the Mimo knowledge MCP server (same conventions as the
# previous /opt/services/mcp deployment: rsync + systemd + host-owned .env).
#
# Run from the checked-out repo root (Jenkins workspace or manually on the host).
# Requires: JDK 21 on the build machine, systemd on the target host.
#
# Overridable env vars:
#   APP_DIR     - deploy target      (default: /opt/services/mcp)
#   SERVICE_DIR - systemd unit dir   (default: /etc/systemd/system)
#   SKIP_BUILD  - set to 1 to deploy an already-built target/*.jar

set -euo pipefail

APP_DIR="${APP_DIR:-/opt/services/mcp}"
SERVICE_DIR="${SERVICE_DIR:-/etc/systemd/system}"
SERVICE_NAME="mcp-knowledge"

SUDO=""
if [[ "$(id -u)" -ne 0 ]]; then
  SUDO="sudo"
fi

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo "==> Building (tests skipped here; CI runs them)"
  ./mvnw -B -DskipTests package
fi

JAR=$(ls target/mimo-knowledge-mcp-*.jar | head -1)
if [[ -z "${JAR}" ]]; then
  echo "ERROR: no built jar found in target/" >&2
  exit 1
fi

echo "==> Deploying ${JAR} to ${APP_DIR}"
${SUDO} mkdir -p "${APP_DIR}"

if [[ ! -f "${APP_DIR}/.env" ]]; then
  echo "ERROR: ${APP_DIR}/.env not found. Provision it once from .env.example before deploying." >&2
  exit 1
fi

# Keep the previous jar for instant rollback.
if [[ -f "${APP_DIR}/app.jar" ]]; then
  ${SUDO} cp "${APP_DIR}/app.jar" "${APP_DIR}/app.jar.prev"
fi
${SUDO} cp "${JAR}" "${APP_DIR}/app.jar"

echo "==> Installing systemd unit"
${SUDO} cp "scripts/${SERVICE_NAME}.service" "${SERVICE_DIR}/${SERVICE_NAME}.service"
${SUDO} systemctl daemon-reload
${SUDO} systemctl enable "${SERVICE_NAME}"
${SUDO} systemctl restart "${SERVICE_NAME}"

echo "==> Waiting for readiness"
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT:-3100}/health/ready" > /dev/null 2>&1; then
    echo "==> ${SERVICE_NAME} is ready"
    exit 0
  fi
  sleep 2
done

echo "WARNING: service did not become ready in 60s. Check: journalctl -u ${SERVICE_NAME} -n 100" >&2
echo "Rollback: ${SUDO} mv ${APP_DIR}/app.jar.prev ${APP_DIR}/app.jar && ${SUDO} systemctl restart ${SERVICE_NAME}" >&2
exit 1
