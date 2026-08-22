#!/usr/bin/env bash
#
# Deploy script for the Mimo knowledge MCP server — same conventions as the
# other Mimo Java services on the CentOS host: jar in /opt/services/<name>,
# <name>.sh launcher in /usr/local/bin, <name>.service systemd unit,
# external config in /opt/services/<name>/application.properties.
#
# Run from the checked-out repo root (Jenkins workspace or manually).
# Requires: JDK 21 on the build machine (mvnw), systemd on the target host.
#
# Overridable env vars:
#   APP_DIR     - deploy target      (default: /opt/services/mcp)
#   SERVICE_DIR - systemd unit dir   (default: /etc/systemd/system)
#   BIN_DIR     - launcher dir       (default: /usr/local/bin)
#   SKIP_BUILD  - set to 1 to deploy an already-built target/*.jar

set -euo pipefail

APP_DIR="${APP_DIR:-/opt/services/mcp}"
SERVICE_DIR="${SERVICE_DIR:-/etc/systemd/system}"
BIN_DIR="${BIN_DIR:-/usr/local/bin}"
SERVICE_NAME="mcp"

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

if [[ ! -f "${APP_DIR}/application.properties" ]]; then
  echo "ERROR: ${APP_DIR}/application.properties not found." >&2
  echo "Provision it once from application.properties.example before deploying." >&2
  exit 1
fi

# Keep the previous jar for instant rollback.
if [[ -f "${APP_DIR}/${SERVICE_NAME}.jar" ]]; then
  ${SUDO} cp "${APP_DIR}/${SERVICE_NAME}.jar" "${APP_DIR}/${SERVICE_NAME}.jar.prev"
fi
${SUDO} cp "${JAR}" "${APP_DIR}/${SERVICE_NAME}.jar"

echo "==> Installing launcher and systemd unit"
${SUDO} cp "${SERVICE_NAME}.sh" "${BIN_DIR}/${SERVICE_NAME}.sh"
${SUDO} chmod +x "${BIN_DIR}/${SERVICE_NAME}.sh"
${SUDO} cp "${SERVICE_NAME}.service" "${SERVICE_DIR}/${SERVICE_NAME}.service"
${SUDO} systemctl daemon-reload
${SUDO} systemctl enable "${SERVICE_NAME}"
${SUDO} systemctl restart "${SERVICE_NAME}"

echo "==> Waiting for readiness"
PORT=$(grep -E '^server\.port=' "${APP_DIR}/application.properties" | cut -d= -f2 || true)
PORT="${PORT:-3100}"
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/health/ready" > /dev/null 2>&1; then
    echo "==> ${SERVICE_NAME} is ready"
    exit 0
  fi
  sleep 2
done

echo "WARNING: service did not become ready in 60s. Check /opt/log/mcp.log" >&2
echo "Rollback: ${SUDO} mv ${APP_DIR}/${SERVICE_NAME}.jar.prev ${APP_DIR}/${SERVICE_NAME}.jar && ${SUDO} systemctl restart ${SERVICE_NAME}" >&2
exit 1
