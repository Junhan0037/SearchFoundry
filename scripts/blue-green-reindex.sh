#!/usr/bin/env bash
# 블루그린 reindex 자동화 스크립트: 인덱스 생성 → reindex → 검증 → alias 스위치까지 API로 실행한다.

set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <sourceVersion> <targetVersion>"
  echo "예) $0 3 4  # docs_v3 -> docs_v4 전환"
  exit 1
fi

SOURCE_VERSION="$1"
TARGET_VERSION="$2"
BASE_URL="${BASE_URL:-http://localhost:8080}"
WAIT_FOR_COMPLETION="${WAIT_FOR_COMPLETION:-true}"
REFRESH_AFTER="${REFRESH_AFTER:-true}"
SAMPLE_QUERIES="${SAMPLE_QUERIES:-쿠버네티스 인그레스 설정,로그 수집 파이프라인,nori 분석기 튜닝}"
SAMPLE_TOP_K="${SAMPLE_TOP_K:-5}"
MIN_JACCARD="${MIN_JACCARD:-0.6}"
HASH_MAX_DOCS="${HASH_MAX_DOCS:-5000}"
HASH_PAGE_SIZE="${HASH_PAGE_SIZE:-500}"

# 샘플 쿼리 문자열을 JSON 배열로 직렬화한다. ("," 구분자 기반)
render_queries() {
  local raw="$1"
  local IFS=',' read -ra parts <<<"$raw"
  local result="["
  for idx in "${!parts[@]}"; do
    local trimmed="${parts[$idx]#"${parts[$idx]%%[![:space:]]*}"}"
    trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"
    local escaped="${trimmed//\"/\\\"}"
    result+="\"${escaped}\""
    if [ "$idx" -lt $((${#parts[@]} - 1)) ]; then
      result+=","
    fi
  done
  result+="]"
  echo "$result"
}

SAMPLE_QUERIES_JSON="$(render_queries "$SAMPLE_QUERIES")"

PAYLOAD=$(cat <<EOF
{
  "sourceVersion": ${SOURCE_VERSION},
  "targetVersion": ${TARGET_VERSION},
  "waitForCompletion": ${WAIT_FOR_COMPLETION},
  "refreshAfter": ${REFRESH_AFTER},
  "validation": {
    "sampleQueries": ${SAMPLE_QUERIES_JSON},
    "sampleTopK": ${SAMPLE_TOP_K},
    "minJaccard": ${MIN_JACCARD},
    "hashMaxDocs": ${HASH_MAX_DOCS},
    "hashPageSize": ${HASH_PAGE_SIZE}
  }
}
EOF
)

echo ">>> 블루그린 reindex 실행: docs_v${SOURCE_VERSION} -> docs_v${TARGET_VERSION} (${BASE_URL})"

RESPONSE=$(curl -sS --fail -X POST "${BASE_URL}/admin/index/reindex" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD")

echo ">>> 요청 완료. 응답 본문:"
if command -v jq >/dev/null 2>&1; then
  echo "$RESPONSE" | jq .
else
  echo "$RESPONSE"
fi
