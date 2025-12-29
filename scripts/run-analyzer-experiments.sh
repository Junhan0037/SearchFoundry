#!/usr/bin/env bash
set -euo pipefail

# nori 분석기 조합 실험을 실행하는 편의 스크립트.
# - BASE_URL: Admin API 호스트 (기본 http://localhost:8080)
# - DATASET_ID: 평가 데이터셋 ID (기본 baseline)
# - CASE_NAMES: 실행할 케이스 이름을 콤마로 구분해 지정(비우면 기본 4종 모두)

BASE_URL="${BASE_URL:-http://localhost:8080}"
DATASET_ID="${DATASET_ID:-baseline}"
TOP_K="${TOP_K:-10}"
WORST_QUERIES="${WORST_QUERIES:-20}"
SAMPLE_DATA="${SAMPLE_DATA:-docs/data/sample_documents.json}"
BASE_TEMPLATE_VERSION="${BASE_TEMPLATE_VERSION:-1}"
CLEANUP="${CLEANUP:-true}"
GENERATE_REPORT="${GENERATE_REPORT:-true}"
CASE_NAMES="${CASE_NAMES:-}"

if [[ ! -f "${SAMPLE_DATA}" ]]; then
  echo "[ERROR] 샘플 데이터 파일을 찾을 수 없습니다: ${SAMPLE_DATA}" >&2
  exit 1
fi

case_json="[]"
if [[ -n "${CASE_NAMES}" ]]; then
  IFS=',' read -ra names <<< "${CASE_NAMES}"
  quoted=()
  for name in "${names[@]}"; do
    trimmed="$(echo "${name}" | xargs)"
    if [[ -n "${trimmed}" ]]; then
      quoted+=("\"${trimmed}\"")
    fi
  done

  if [[ ${#quoted[@]} -gt 0 ]]; then
    case_json="[$(IFS=','; echo "${quoted[*]}")]"
  fi
fi

payload=$(cat <<EOF
{
  "datasetId": "${DATASET_ID}",
  "topK": ${TOP_K},
  "worstQueries": ${WORST_QUERIES},
  "baseTemplateVersion": ${BASE_TEMPLATE_VERSION},
  "sampleDataPath": "${SAMPLE_DATA}",
  "caseNames": ${case_json},
  "cleanupAfterRun": ${CLEANUP},
  "generateReport": ${GENERATE_REPORT}
}
EOF
)

echo "[INFO] 분석기 실험 요청 전송(dataset=${DATASET_ID}, cases=${CASE_NAMES:-all}, topK=${TOP_K})"
curl -fsS -X POST "${BASE_URL}/admin/eval/experiments/analyzer" \
  -H "Content-Type: application/json" \
  -d "${payload}"
echo
echo "[INFO] 완료. Admin API 로그와 reports/ 디렉토리에서 결과를 확인하세요."
