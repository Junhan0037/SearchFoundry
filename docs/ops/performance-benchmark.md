# 검색 성능 벤치마크(P50/P95) 가이드

고정 데이터셋과 고정 QuerySet을 기반으로 검색 성능(P50/P95/QPS)을 계측하고 리포트를 생성하는 방법을 정리한다.

## 기본값
- 프로퍼티: `performance.benchmark.*` (application.yml 참고)
  - dataset-id: baseline QuerySet(`docs/eval/querysets/baseline_queries.json`)
  - top-k: 10
  - iterations: 3 (측정 반복), warmups: 1
  - target-index: `docs_read`
  - report-base-path: `reports/performance`
- 리포트 파일: `reports/performance/{reportId}/metrics.json`, `summary.md`
- 비교 리포트: `reports/performance/comparisons/{after}_vs_{before}.md`

## 실행 방법
### 1) Admin API 직접 호출
```bash
curl -X POST http://localhost:8080/admin/performance/benchmark \
  -H "Content-Type: application/json" \
  -d '{
    "datasetId": "baseline",
    "topK": 10,
    "iterations": 5,
    "warmups": 1,
    "targetIndex": "docs_read",
    "baselineReportId": "perf_baseline_20250101_000000"
  }'
```
- `baselineReportId`를 지정하면 전/후 Δ(P50/P95/QPS) 비교 리포트도 생성된다.

### 2) 스크립트 사용
```bash
BASE_URL=http://localhost:8080 \
DATASET_ID=baseline \
TOP_K=10 \
ITERATIONS=5 \
WARMUPS=1 \
TARGET_INDEX=docs_read \
BASELINE_REPORT_ID=perf_baseline_20250101_000000 \
./scripts/run-performance-benchmark.sh
```
- 서버 측에 `reports/performance/{reportId}` 폴더가 생성되며 metrics/summary가 저장된다.

## 리포트 읽는 법
- `summary.md`: P50/P95/Avg/Min/Max, QPS, P95 기준 상위 느린 쿼리 목록을 표 형태로 제공.
- `metrics.json`:
  - `latency`: 분포 요약(P50/P95/Avg/Min/Max)
  - `queries[]`: 쿼리별 샘플 took(ms) 목록과 분포 요약
  - `qps`: 총 샘플 수 대비 wall-clock 실행 시간 기반 추정 QPS
- 비교 리포트(`comparisons/*.md`): 전/후 P50/P95/Avg/Max 및 QPS 델타, P95 기준 회귀/개선된 쿼리 Top N 정리.

## 운영 팁
- JVM/Elasticsearch 워밍업을 위해 최소 1회 warmup 유지 권장.
- 측정 반복(iterations)은 3~10 사이에서 조정하되, 측정 시간(elapsedMs)이 너무 길어지지 않는지 함께 확인한다.
- QuerySet은 실제 트래픽을 대표하는 쿼리로 유지하고, 변경 시 reportIdPrefix를 달리해 비교 이력을 남긴다.
