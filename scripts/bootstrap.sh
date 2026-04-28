#!/usr/bin/env bash
#
# bootstrap.sh — one-shot dev environment bootstrap for kukuvaia.
#
# Idempotent. Safe to re-run. Does the following:
#   1. Creates kukuvaia-engine/.env from .env.example (only if .env does not exist).
#   2. Reports whether the Postgres container is running.
#
# Usage:
#   ./scripts/bootstrap.sh
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_DIR="${REPO_ROOT}/kukuvaia-engine"
ENV_FILE="${ENGINE_DIR}/.env"
ENV_EXAMPLE="${ENGINE_DIR}/.env.example"

green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
red() { printf '\033[31m%s\033[0m\n' "$*" >&2; }

# 1. .env -----------------------------------------------------------------
if [[ -f "${ENV_FILE}" ]]; then
  yellow "[1/2] .env already exists — leaving it alone."
else
  if [[ ! -f "${ENV_EXAMPLE}" ]]; then
    red "[1/2] .env.example not found at ${ENV_EXAMPLE} — cannot bootstrap."
    exit 1
  fi
  cp "${ENV_EXAMPLE}" "${ENV_FILE}"
  green "[1/2] Created ${ENV_FILE} from .env.example."
fi

# 2. docker-compose check -------------------------------------------------
if ! docker info >/dev/null 2>&1; then
  yellow "[2/2] Docker is not running. Start it before launching Postgres."
elif docker ps --format '{{.Names}}' | grep -qx 'kukuvaia-postgres'; then
  green "[2/2] kukuvaia-postgres container already running."
else
  yellow "[2/2] Postgres not running. Start it with:"
  echo "       docker compose -f kukuvaia-engine/docker-compose.yml up -d"
fi

cat <<EOF

$(green "Bootstrap complete.") Next steps:

  1. Review ${ENV_FILE} and set LLM_BASE_URL / LLM_API_KEY / LLM_MODEL.
  2. Start Postgres if it is not running:
       docker compose -f kukuvaia-engine/docker-compose.yml up -d
  3. Start the engine — .env is loaded automatically:
       cd kukuvaia-engine && ./gradlew :kukuvaia-app:bootRun

EOF
