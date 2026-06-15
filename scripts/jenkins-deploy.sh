#!/usr/bin/env bash
#
# Jenkins deploy script for the mimobike MCP server + sync worker.
#
# Run this from the root of the Jenkins workspace (the checked-out repo).
# It copies the app into /opt/services/mcp, installs the systemd units,
# applies the DB schema and restarts the services.
#
# The .env file in $APP_DIR is preserved (never overwritten) — provision it
# once on the host (or push it from Jenkins credentials separately).
#
# Override defaults with env vars if needed:
#   APP_DIR     - deploy target        (default: /opt/services/mcp)
#   SERVICE_DIR - systemd unit dir     (default: /etc/systemd/system)
#   SRC_DIR     - source to copy from  (default: current directory)

set -euo pipefail

APP_DIR="${APP_DIR:-/opt/services/mcp}"
SERVICE_DIR="${SERVICE_DIR:-/etc/systemd/system}"
SRC_DIR="${SRC_DIR:-$(pwd)}"
SERVICES=("mcp-server" "mcp-sync")

# Use sudo only when not already running as root.
SUDO=""
if [[ "$(id -u)" -ne 0 ]]; then
  SUDO="sudo"
fi

echo "==> Deploying from ${SRC_DIR} to ${APP_DIR}"
${SUDO} mkdir -p "${APP_DIR}"

# Copy application files into the target, excluding things that must not be
# shipped or overwritten (workspace junk, dev deps, and the host's secrets).
echo "==> Syncing application files"
${SUDO} rsync -a --delete \
  --exclude='.git/' \
  --exclude='.idea/' \
  --exclude='node_modules/' \
  --exclude='.env' \
  "${SRC_DIR}/" "${APP_DIR}/"

cd "${APP_DIR}"

if [[ ! -f "${APP_DIR}/.env" ]]; then
  echo "ERROR: ${APP_DIR}/.env not found. Provision it before deploying." >&2
  exit 1
fi

echo "==> Installing production dependencies"
${SUDO} npm ci --omit=dev

echo "==> Applying database schema"
${SUDO} npm run migrate

echo "==> Installing systemd units"
for svc in "${SERVICES[@]}"; do
  ${SUDO} cp "${APP_DIR}/scripts/${svc}.service" "${SERVICE_DIR}/${svc}.service"
done

echo "==> Reloading systemd and restarting services"
${SUDO} systemctl daemon-reload
${SUDO} systemctl enable "${SERVICES[@]}"
${SUDO} systemctl restart "${SERVICES[@]}"

echo "==> Done. Service status:"
${SUDO} systemctl --no-pager --lines=0 status "${SERVICES[@]}" || true
