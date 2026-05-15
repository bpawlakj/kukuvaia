#!/usr/bin/env bash
# TG2.D — Phase 2 acceptance smoke. Exercises the architecture §2 diagram:
#
#   ETSL (WireMock stub) → validation-engine → kukuvaia → validation-engine MCP
#
# Pre-requisites: docker-compose.phase2.yml is up and healthy.
#
# Acceptance gates (mirrors plan TG2.D.1's 4 tests):
#   1. POST /api/v2/validate { outlineId } returns 202 + runId
#   2. Polled GET /runs/{id} eventually reports COMPLETED
#   3. The completed run records at least one finding with evaluatedBy=LLM
#   4. The run document carries a non-empty outlineHash
#
# Exit code: 0 on full pass, non-zero on first gate failure.

set -euo pipefail

VAL_ENGINE_URL="${VAL_ENGINE_URL:-http://localhost:8081}"
KUKUVAIA_URL="${KUKUVAIA_URL:-http://localhost:8080}"
OUTLINE_ID="${OUTLINE_ID:-phase2-outline}"
SERVICE_TOKEN="${VALIDATION_ENGINE_TOKEN:-phase2-test-token}"
POLL_TIMEOUT_SECONDS="${POLL_TIMEOUT_SECONDS:-90}"

log()   { printf '\033[1;34m[smoke]\033[0m %s\n' "$*"; }
ok()    { printf '\033[1;32m  ok\033[0m %s\n' "$*"; }
fail()  { printf '\033[1;31m  fail\033[0m %s\n' "$*" >&2; exit 1; }

require_jq() {
  command -v jq >/dev/null 2>&1 || fail "jq is required (apt install jq)"
}

wait_until_healthy() {
  local name="$1" url="$2" deadline=$((SECONDS + 60))
  log "waiting for ${name} at ${url}..."
  while (( SECONDS < deadline )); do
    if curl -sf -o /dev/null "${url}"; then ok "${name} reachable"; return; fi
    sleep 2
  done
  fail "${name} did not become healthy within 60s"
}

poll_run_status() {
  local run_id="$1" deadline=$((SECONDS + POLL_TIMEOUT_SECONDS))
  local body status
  while (( SECONDS < deadline )); do
    body=$(curl -sf -H "Authorization: Bearer ${SERVICE_TOKEN}" \
            "${VAL_ENGINE_URL}/api/v2/validate/runs/${run_id}") || true
    status=$(printf '%s' "$body" | jq -r '.status // ""')
    case "$status" in
      COMPLETED|FAILED) printf '%s' "$body"; return;;
      "") sleep 2; continue;;
      *) sleep 2;;
    esac
  done
  fail "run ${run_id} did not finish within ${POLL_TIMEOUT_SECONDS}s"
}

main() {
  require_jq

  wait_until_healthy "validation-engine" "${VAL_ENGINE_URL}/actuator/health"
  wait_until_healthy "kukuvaia-engine"   "${KUKUVAIA_URL}/api/sessions"

  log "gate 1 — POST /api/v2/validate { outlineId='${OUTLINE_ID}' }"
  start_response=$(curl -sf -X POST \
      -H "Authorization: Bearer ${SERVICE_TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"outlineId\":\"${OUTLINE_ID}\"}" \
      "${VAL_ENGINE_URL}/api/v2/validate")
  run_id=$(printf '%s' "$start_response" | jq -r '.runId // ""')
  [[ -n "$run_id" && "$run_id" != "null" ]] || fail "no runId in start response: ${start_response}"
  ok "runId=${run_id}"

  log "gate 2 — poll GET /runs/${run_id} until COMPLETED"
  run_body=$(poll_run_status "$run_id")
  status=$(printf '%s' "$run_body" | jq -r '.status')
  [[ "$status" == "COMPLETED" ]] || fail "expected COMPLETED, got ${status}: ${run_body}"
  ok "run completed"

  log "gate 3 — at least one finding with evaluatedBy=LLM"
  llm_findings=$(curl -sf -H "Authorization: Bearer ${SERVICE_TOKEN}" \
      "${VAL_ENGINE_URL}/api/v2/validate/runs/${run_id}/findings" \
      | jq '[.[] | select(.evaluatedBy == "LLM")] | length')
  (( llm_findings > 0 )) || fail "expected at least one LLM finding, got ${llm_findings}"
  ok "${llm_findings} LLM finding(s) recorded"

  log "gate 4 — outlineHash present and non-empty"
  outline_hash=$(printf '%s' "$run_body" | jq -r '.outlineHash // ""')
  [[ -n "$outline_hash" && "$outline_hash" != "null" ]] || fail "outlineHash missing"
  ok "outlineHash=${outline_hash:0:32}..."

  printf '\n\033[1;32mPhase 2 acceptance: PASS\033[0m\n'
}

main "$@"
