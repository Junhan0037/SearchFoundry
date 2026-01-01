# 회귀 평가 파이프라인

## 목적
- 튜닝/운영(리인덱스 포함) 변경 후 baseline 대비 품질 퇴행 여부를 자동으로 확인한다.
- 기준선은 `eval.regression.baseline-report-id`로 관리하며, 기본값은 `reports/20251226_055824`이다.

## 실행 방법
### 1) 스크립트
```bash
# 기본값: dataset=baseline, baselineReportId=20251226_055824, topK=10, worst=20
./scripts/run-regression-eval.sh

# 대상 인덱스 지정 + 리포트 prefix 변경
TARGET_INDEX=docs_v4 REPORT_ID_PREFIX="regression_post_reindex" \
BASELINE_REPORT_ID=20251226_055824 \
./scripts/run-regression-eval.sh
```
- 결과: `reportId`와 함께 비교 리포트 마크다운 경로(`reports/comparisons/{after}_vs_{before}.md`)가 응답에 포함된다.

### 2) Admin API 직접 호출
```bash
curl -X POST http://localhost:8080/admin/eval/regression \
  -H "Content-Type: application/json" \
  -d '{
    "datasetId": "baseline",
    "baselineReportId": "20251226_055824",
    "topK": 10,
    "worstQueries": 20,
    "targetIndex": "docs_read",
    "reportIdPrefix": "regression"
  }'
```

## 응답 주요 필드
- `report.reportId`: 새 평가 리포트 ID (`reports/{reportId}/`)
- `comparison.markdownPath`: baseline 대비 비교 리포트 경로 (`reports/comparisons/{after}_vs_{before}.md`)
- `comparison.metricsDelta`: Precision/Recall/MRR/nDCG 변화량
- `comparison.regressedQueries`: 퇴행하거나 새로 worst에 진입한 쿼리 목록

## 운영 통합 팁
1. 블루그린 전환 후 `scripts/run-regression-eval.sh`를 실행해 품질 퇴행 여부를 확인한다.
2. 비교 리포트를 검토해 퇴행이 확인되면 `/admin/index/rollback`으로 즉시 복구한다.
3. 개선이 확인되면 Decision Log에 baseline/after 리포트 ID와 메트릭 변화를 기록한다.
