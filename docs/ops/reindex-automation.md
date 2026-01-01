# 블루그린 Reindex 운영 자동화

## 개요
- 새 인덱스 생성 → reindex → 검증(count/샘플 쿼리/해시) → alias 스위치 → 보관 manifest 기록까지 API 한 번으로 실행한다.
- 롤백은 `/admin/index/rollback`을 사용하며, 스위치 직후 문제 발생 시 이전 인덱스로 즉시 되돌린다.

## 전제 조건
- Spring Boot API가 실행 중(`BASE_URL` 기본값: `http://localhost:8080`).
- Elasticsearch 클러스터에 `docs_v{n}` 인덱스가 존재하고, `docs_read/docs_write` alias가 정상 연결되어 있어야 한다.
- 샘플 쿼리는 운영 대표 쿼리로 교체 권장(미지정 시 기본값을 사용한다).

## 스크립트 사용법
`scripts/blue-green-reindex.sh`가 블루그린 reindex 전체를 자동화한다.

```bash
# docs_v3 -> docs_v4 전환 (기본 검증 옵션 사용)
./scripts/blue-green-reindex.sh 3 4

# 검증 강도/쿼리 조정
SAMPLE_QUERIES="쿠버네티스 인그레스 설정,로그 수집 파이프라인,검색 품질 개선" \
SAMPLE_TOP_K=10 MIN_JACCARD=0.7 HASH_MAX_DOCS=8000 HASH_PAGE_SIZE=800 \
./scripts/blue-green-reindex.sh 3 4

# 원격 환경 호출
BASE_URL=http://api.example.com ./scripts/blue-green-reindex.sh 5 6
```

- 주요 환경 변수  
  - `BASE_URL`: API 엔드포인트 기본 주소(기본: `http://localhost:8080`)  
  - `WAIT_FOR_COMPLETION`/`REFRESH_AFTER`: ES Reindex API 옵션(`true|false`)  
  - `SAMPLE_QUERIES`: 검증용 대표 쿼리(`,` 구분 문자열)  
  - `SAMPLE_TOP_K`, `MIN_JACCARD`, `HASH_MAX_DOCS`, `HASH_PAGE_SIZE`: 검증 강도/비용 조정
- 실행 결과에 `retentionManifestPath`가 포함되어 보관 manifest 위치를 바로 확인할 수 있다(`reports/reindex/{timestamp}_{target}/manifest.md`).

## API 명세
### `POST /admin/index/reindex`
- 역할: 블루그린 reindex 전체 절차를 원자적으로 수행한다. 검증 실패 시 alias 스위치는 실행하지 않는다.

요청 예시:
```json
{
  "sourceVersion": 3,
  "targetVersion": 4,
  "waitForCompletion": true,
  "refreshAfter": true,
  "validation": {
    "sampleQueries": ["쿠버네티스 인그레스 설정", "로그 수집 파이프라인", "nori 분석기 튜닝"],
    "sampleTopK": 5,
    "minJaccard": 0.6,
    "hashMaxDocs": 5000,
    "hashPageSize": 500
  }
}
```

응답 주요 필드:
- `sourceIndex` / `targetIndex`: 전환 전/후 인덱스명
- `sourceCount` / `targetCount`: 문서 수 비교 결과
- `aliasBefore` / `aliasAfter`: read/write alias 전환 상태
- `validation`: count/샘플 쿼리/해시 검증 상세 및 `passed` 여부
- `retentionManifestPath`: 보관 manifest 경로(롤백/청소 시 참고)

### `POST /admin/index/rollback`
- 파라미터: `currentIndex`, `rollbackToIndex`
- 용도: 스위치 직후 장애 발생 시 이전 인덱스로 즉시 복구

## 운영 체크리스트 제안
1. 스크립트로 reindex 실행 후 `validation.passed` 확인
2. `retentionManifestPath` 확인해 이전 인덱스 보관 기록 검토
3. 필요 시 `/admin/eval/run`으로 회귀 평가(topK=10, worstQueries=20 등) 병행
4. 이상 징후 발견 시 `/admin/index/rollback`으로 즉시 복구

## 회귀 평가 연동
- 블루그린 전환 후 회귀 체크:  
  ```bash
  # baseline 리포트(기본: 20251226_055824)와 비교
  ./scripts/run-regression-eval.sh
  ```
- 응답의 `comparison.markdownPath`를 검토해 퇴행 여부를 확인하고, 필요 시 롤백/튜닝 조정을 진행한다.
