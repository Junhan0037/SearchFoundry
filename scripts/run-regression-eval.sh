#!/usr/bin/env bash
# baseline 리포트와 비교하는 회귀 평가를 실행하고 비교 리포트를 생성한다.
# - 운영/튜닝 변경 후 품질 퇴행을 빠르게 감지하는 용도로 사용한다.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DATASET_ID="${DATASET_ID:-baseline}"
BASELINE_REPORT_ID="${BASELINE_REPORT_ID:-20251226_055824}"
TOP_K="${TOP_K:-10}"
WORST_QUERIES="${WORST_QUERIES:-20}"
TARGET_INDEX="${TARGET_INDEX:-}"
REPORT_ID_PREFIX="${REPORT_ID_PREFIX:-regression}"

TARGET_FIELD=""
if [[ -n "${TARGET_INDEX}" ]]; then
  TARGET_FIELD=$(printf ',\n  "targetIndex": "%s"' "${TARGET_INDEX}")
fi

body=$(cat <<EOF
{
  "datasetId": "${DATASET_ID}",
  "baselineReportId": "${BASELINE_REPORT_ID}",
  "topK": ${TOP_K},
  "worstQueries": ${WORST_QUERIES},
  "reportIdPrefix": "${REPORT_ID_PREFIX}"${TARGET_FIELD}
}
EOF
)

echo "[INFO] 회귀 평가 실행(base=${BASELINE_REPORT_ID}, dataset=${DATASET_ID}, target=${TARGET_INDEX:-docs_read})"
response=$(curl -sS --fail -X POST "${BASE_URL}/admin/eval/regression" \
  -H "Content-Type: application/json" \
  -d "${body}")

echo "[INFO] 요청 완료. 응답:"
if command -v jq >/dev/null 2>&1; then
  echo "${response}" | jq .
else
  echo "${response}"
fi
