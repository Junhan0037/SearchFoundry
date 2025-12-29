# nori 분석기 실험 가이드

## 목적
- decompound 모드(mixed/discard/none), 사용자 사전(userdict), 동의어(synonym_graph) 조합을 3~5회 비교해 한글 검색 품질 영향도를 수치로 검증한다.
- 실험 시에는 별도 인덱스(docs_exp_*)를 생성해 기본 `docs_read` alias를 건드리지 않는다.

## 실험 케이스(기본 4종)
1. **baseline_mixed_synonym**: decompound=mixed + userdict + synonym_graph (기본값)
2. **discard_userdict_synonym**: decompound=discard + userdict + synonym_graph
3. **mixed_no_userdict**: decompound=mixed, userdict 미사용, synonym_graph 사용
4. **mixed_userdict_no_synonym**: decompound=mixed + userdict, synonym_graph 미사용

## 실행 방법
### Admin API (직접 호출)
```bash
curl -X POST http://localhost:8080/admin/eval/experiments/analyzer \
  -H "Content-Type: application/json" \
  -d '{
    "datasetId": "baseline",
    "topK": 10,
    "worstQueries": 20,
    "baseTemplateVersion": 1,
    "sampleDataPath": "docs/data/sample_documents.json",
    "caseNames": [],             // 비우면 기본 4개 모두 실행
    "cleanupAfterRun": true,     // true면 실험 인덱스 삭제
    "generateReport": true       // summary.md/metrics.json 생성
  }'
```

### 스크립트 사용
```bash
./scripts/run-analyzer-experiments.sh \
  BASE_URL=http://localhost:8080 \
  DATASET_ID=baseline \
  TOP_K=10 WORST_QUERIES=20 \
  SAMPLE_DATA=docs/data/sample_documents.json \
  CASE_NAMES="baseline_mixed_synonym,discard_userdict_synonym"
```
- `CASE_NAMES`를 비우면 기본 케이스 4개를 모두 실행한다.
- `CLEANUP=false`로 주면 색인된 실험 인덱스를 Kibana 등에서 직접 분석할 수 있다.

## 결과 확인
- API 응답: 케이스별 Bulk 색인 요약, 평균 지표, 리포트 경로 반환.
- 리포트 파일: `reports/{reportId}/summary.md`, `reports/{reportId}/metrics.json`
  - reportId는 `analyzer-{caseName}_yyyymmdd_HHMMss` 형태.
  - summary.md 상단 메타데이터에 `Target Index/Alias`가 포함되어 어떤 인덱스를 쿼리했는지 확인 가능.
