# 샘플 데이터 스펙

문서 검색 도메인에 맞춘 샘플 데이터는 `docs/data/sample_documents.json` 파일을 사용합니다. Elasticsearch 인덱스 매핑/검색 API/평가 파이프라인 모두 동일한 스키마를 기준으로 동작합니다.

## 스키마
- `id` (UUID String) : 문서 식별자
- `title` (String) : 검색 가중치가 가장 높은 제목
- `summary` (String, optional) : 본문 요약
- `body` (String) : 전체 본문
- `tags` (List<String>) : 필터/집계/추천에 활용할 태그 집합
- `category` (String) : 상위 카테고리(예: `infra`, `data`, `backend`, `ml`)
- `author` (String) : 작성자
- `publishedAt` (Instant, ISO-8601 UTC) : 게시 시점
- `popularityScore` (Double) : 조회/좋아요 기반 인기 지표, 0 이상 값으로 관리하며 랭킹 신호로 사용

## 사용 방법
- 개발/로컬 환경에서는 `sample_documents.json`을 NDJSON 형태로 변환해 bulk 색인 러너에 전달합니다.
- 필드 추가/변경이 필요하면 `Document` 도메인 모델(`com.searchfoundry.core.document.Document`)과 함께 본 스펙을 업데이트해 일관성을 유지합니다.
- `popularityScore`는 0~1 정규화된 값으로 시작하고, 실측 데이터로 교체할 때도 0 이상 제약을 유지합니다.
