#!/usr/bin/env bash
set -euo pipefail

# 고정 QuerySet으로 검색 성능(P50/P95/QPS)을 측정하고 리포트를 생성하는 스크립트.
# - BASE_URL: Spring Boot Admin API URL
# - DATASET_ID: docs/eval/querysets/{datasetId}_queries.json
# - TARGET_INDEX: 검색 대상 인덱스/alias (기본 docs_read)
# - BASELINE_REPORT_ID: 비교 기준 리포트 ID(없으면 비교 생략)

BASE_URL="${BASE_URL:-http://localhost:8080}"
DATASET_ID="${DATASET_ID:-baseline}"
TOP_K="${TOP_K:-10}"
ITERATIONS="${ITERATIONS:-3}"
WARMUPS="${WARMUPS:-1}"
TARGET_INDEX="${TARGET_INDEX:-docs_read}"
REPORT_PREFIX="${REPORT_PREFIX:-perf}"
BASELINE_REPORT_ID="${BASELINE_REPORT_ID:-}"

log() { echo "[INFO] $*"; }
warn() { echo "[WARN] $*" >&2; }

# 헬스 체크(실패해도 계속 진행).
if curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
  log "애플리케이션 헬스 체크 성공(BASE_URL=${BASE_URL})"
else
  warn "헬스 체크에 실패했습니다. 기동 상태를 확인해주세요."
fi

# payload 구성(기본값은 서버 프로퍼티 사용).
payload=$(cat <<EOF
{
  "datasetId": "${DATASET_ID}",
  "topK": ${TOP_K},
  "iterations": ${ITERATIONS},
  "warmups": ${WARMUPS},
  "targetIndex": "${TARGET_INDEX}",
  "reportIdPrefix": "${REPORT_PREFIX}"$(if [[ -n "${BASELINE_REPORT_ID}" ]]; then printf ',\n  "baselineReportId": "%s"' "${BASELINE_REPORT_ID}"; fi)
}
EOF
)

log "검색 성능 벤치마크 실행(dataset=${DATASET_ID}, topK=${TOP_K}, iter=${ITERATIONS}, warmup=${WARMUPS})"
response=$(curl -fsS -X POST "${BASE_URL}/admin/performance/benchmark" \
  -H "Content-Type: application/json" \
  -d "${payload}") || {
    warn "성능 벤치마크 실행에 실패했습니다."
    exit 1
  }

log "성능 벤치마크 완료. 응답:"
echo "${response}" | sed 's/\\\\n/\
/g'
log "metrics/summary 파일은 서버 경로 reports/performance/{reportId}/에 생성됩니다."
