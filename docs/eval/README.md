# 평가 데이터 스키마(JSON)

검색 품질 평가를 위한 QuerySet/JudgementSet JSON 스키마를 정의합니다. 파일 인코딩은 UTF-8, 들여쓰기는 2 spaces를 사용합니다.

## 파일 위치
- QuerySet: `docs/eval/querysets/*.json`
- JudgementSet: `docs/eval/judgements/*.json`

## QuerySet 스키마
- `queryId` (string): 평가용 쿼리의 식별자, JudgementSet과 1:N으로 매핑됩니다.
- `queryText` (string): 사용자가 입력한 검색어 원문입니다.
- `intent` (string): 검색 의도 구분. 예) `informational` | `navigational` | `transactional`.
- `filters` (object, optional): 검색 시 함께 적용할 필터. 키 예시)
  - `category` (string)
  - `tags` (string array)
  - 필요한 경우 `author`, `publishedAtFrom`, `publishedAtTo` 등 확장 가능합니다.

예시:
```json
{
  "queryId": "q001",
  "queryText": "쿠버네티스 인그레스",
  "intent": "informational",
  "filters": {
    "category": "infra",
    "tags": ["kubernetes", "ingress"]
  }
}
```

## JudgementSet 스키마
- `queryId` (string): QuerySet의 `queryId`와 연결됩니다.
- `docId` (string): 정답 문서의 ID(`Document.id` UUID).
- `grade` (number): 0~3 가중치. 0은 불일치, 3은 매우 적합.
- `notes` (string, optional): 채점 근거/메모.

예시:
```json
{
  "queryId": "q001",
  "docId": "b1c0e627-2c89-4d27-9eb1-5a95f9b5c81c",
  "grade": 3,
  "notes": "Ingress 설정 가이드(완전 일치)"
}
```

## 운영 규칙
- `queryId`는 QuerySet/JudgementSet 전체에서 유일하게 유지합니다.
- grade는 0/1/2/3만 사용해 nDCG 등 가중치 지표 계산 시 일관성을 보장합니다.
- QuerySet과 JudgementSet은 동일한 파일명 프리픽스를 사용해 짝을 맞춥니다(예: `baseline_queries.json`, `baseline_judgements.json`).
- 파일 추가/수정 시 PR이나 변경 기록에 개선 대상 쿼리/문서/의도 변경 이유를 함께 남깁니다.
