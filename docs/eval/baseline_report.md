# Baseline 평가 리포트(회귀 기준)

- 목적: Phase 2 기준선을 고정해 이후 튜닝/운영 변경 시 품질 퇴행을 감지한다.
- 데이터셋: `docs/eval/querysets/baseline_queries.json` + `docs/eval/judgements/baseline_judgements.json`
- 파라미터: `topK=10`, `worstQueries=5`
- 리포트: `reports/20251226_055824/summary.md`, `reports/20251226_055824/metrics.json`
  - 평균 지표: Precision@K=0.2000, Recall@K=1.0000, MRR=1.0000, nDCG@K=1.0000
  - Worst Queries: baseline 5개 쿼리 모두 동점(nDCG 1.0)으로 표기

## 재실행 방법
1. Elasticsearch + Spring Boot를 기동하고 docs_read/docs_write alias가 대상 인덱스에 연결되어 있는지 확인한다. (`APP_INDEX_BOOTSTRAP_ENABLED=true` 권장)
2. 샘플 데이터 적재 및 평가 실행:
   ```bash
   BASE_URL=http://localhost:8080 \\
   DATASET_ID=baseline TOP_K=10 WORST_QUERIES=5 \\
   ./scripts/run-baseline-eval.sh
   ```
3. 새 리포트는 `reports/{timestamp}/`에 생성된다. 회귀 확인 시 `reports/20251226_055824`와 비교한다.

## 참고
- Precision@K가 0.2인 이유: topK(10) 대비 샘플 문서 수(5)가 적고, 각 쿼리에 정답 문서가 1개만 존재한다.
- baseline 리포트는 회귀 기준선이므로, 이후 튜닝 결과 리포트는 동일 파라미터(topK=10)로 산출해 비교한다.
