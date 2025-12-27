#!/usr/bin/env bash
set -euo pipefail

# baseline 데이터셋(baseline_queries/judgements)으로 평가를 한 번 실행해 리포트를 생성하는 스크립트.
# - 사전 조건: docs_read/docs_write alias가 이미 대상 인덱스에 연결되어 있어야 한다(bootstrap runner 사용 권장).
# - BASE_URL은 Spring Boot Admin API 주소, SAMPLE_DATA는 bulk 색인에 사용할 문서 JSON 배열 경로.

BASE_URL="${BASE_URL:-http://localhost:8080}"
DATASET_ID="${DATASET_ID:-baseline}"
TOP_K="${TOP_K:-10}"
WORST_QUERIES="${WORST_QUERIES:-5}"
SAMPLE_DATA="${SAMPLE_DATA:-docs/data/sample_documents.json}"
TARGET_ALIAS="${TARGET_ALIAS:-docs_write}"

log() { echo "[INFO] $*"; }
warn() { echo "[WARN] $*" >&2; }

if [[ ! -f "${SAMPLE_DATA}" ]]; then
  warn "샘플 데이터 파일을 찾을 수 없습니다: ${SAMPLE_DATA}"
  exit 1
fi

# 헬스 체크는 강제하지 않지만, 실패 시 바로 알려줌.
if curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
  log "애플리케이션 헬스 체크 성공(BASE_URL=${BASE_URL})"
else
  warn "헬스 체크에 실패했습니다. 애플리케이션 기동 상태를 확인해주세요."
fi

# 1) 샘플 문서를 docs_write alias로 색인한다.
payload="$(printf '{"documents": %s}\n' "$(cat "${SAMPLE_DATA}")")"
log "Bulk 색인 시작(target=${TARGET_ALIAS}, file=${SAMPLE_DATA})"
if ! curl -fsS -X POST "${BASE_URL}/admin/index/bulk" \
  -H "Content-Type: application/json" \
  -d "${payload}"; then
  warn "Bulk 색인에 실패했습니다. alias 연결 상태와 Elasticsearch 로그를 확인하세요."
  exit 1
fi

# 2) baseline 평가 실행(topK/worstQueries는 환경 변수로 조정 가능).
log "Baseline 평가 실행(datasetId=${DATASET_ID}, topK=${TOP_K}, worst=${WORST_QUERIES})"
if ! curl -fsS -X POST "${BASE_URL}/admin/eval/run" \
  -H "Content-Type: application/json" \
  -G \
  --data-urlencode "datasetId=${DATASET_ID}" \
  --data-urlencode "topK=${TOP_K}" \
  --data-urlencode "worstQueries=${WORST_QUERIES}" \
  --data-urlencode "generateReport=true"; then
  warn "평가 실행에 실패했습니다. Elasticsearch 연결 및 데이터 적재 상태를 확인하세요."
  exit 1
fi

log "baseline 평가 완료. 리포트는 서버 기준 경로 reports/{timestamp}/summary.md, metrics.json에 생성됩니다."
