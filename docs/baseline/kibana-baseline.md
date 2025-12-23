# Kibana Baseline 품질 확인 가이드

## 목적
- 로컬 Elasticsearch + Kibana에서 기본 검색 품질을 눈으로 확인하고, 이후 튜닝 실험 시 비교할 **대표 쿼리 10개**를 고정한다.
- API 레이어와 동일한 multi_match(best_fields) + function_score 구성으로 실행해 애플리케이션과 Kibana 사이 검색 행동 일관성을 유지한다.

## 사전 준비
1. Elasticsearch/Kibana 구동
   ```bash
   cd docker
   docker compose up -d
   ```
2. 애플리케이션 기동(인덱스/alias 자동 생성)
   ```bash
   # app.index.bootstrap.enabled=true 로 docs_v1 + docs_read/docs_write alias를 생성
   APP_INDEX_BOOTSTRAP_ENABLED=true APP_INDEX_BOOTSTRAP_VERSION=1 ./gradlew bootRun
   ```
3. 샘플 데이터 색인
   ```bash
   # 루트 경로에서 실행. sample_documents.json 배열을 BulkIndexRequest payload로 감싸 전송한다.
   curl -X POST "http://localhost:8080/admin/index/bulk" \
     -H "Content-Type: application/json" \
     -d '{"documents": '$(cat docs/data/sample_documents.json)'}'
   ```
4. Kibana Dev Tools 접속: http://localhost:5601 → "Dev Tools" → 아래 요청 블록을 붙여 넣어 실행.
5. 데이터 적재 확인(선택): Dev Tools에서 `GET docs_read/_count` 실행 후 `count: 5` 확인.

## 대표 쿼리 10개 (요약)
| 번호 | 입력/조건 | 검증 목표 | 기대 상위 문서 |
| --- | --- | --- | --- |
| 1 | `쿠버네티스 인그레스` | 사용자 사전(쿠버네티스) + 기본 랭킹 | 쿠버네티스 인그레스 설정 가이드 |
| 2 | `k8s ingress controller` | 동의어(k8s ↔ 쿠버네티스) 매칭 | 쿠버네티스 인그레스 설정 가이드 |
| 3 | `Nginx Ingress Controller` | 영문/요약 필드 매칭 | 쿠버네티스 인그레스 설정 가이드 |
| 4 | `엘라스틱서치 로그 수집` | 동의어(엘라스틱서치 ↔ elasticsearch) | 실시간 로그 수집 파이프라인 |
| 5 | `kafka 로그 파이프라인` + `tags=["kafka"]` | 태그 필터 동작 | 실시간 로그 수집 파이프라인 |
| 6 | `nori 동의어 튜닝` | 검색 품질 문서 매칭 | Elasticsearch nori 분석기 튜닝 체크리스트 |
| 7 | `검색 품질` + `category=search` | 카테고리 필터 + 가중치 | Elasticsearch nori 분석기 튜닝 체크리스트 |
| 8 | `redis 캐시 스탬피드` | Redis 캐시 시나리오 매칭 | Spring Boot에서 Redis 캐시 설계 패턴 |
| 9 | `LLM 검색 요약` | LLM/Retrieval 키워드 매칭 | LLM 기반 검색 결과 요약 아키텍처 |
| 10 | `elasticsearch` + 최신순 정렬 | recency 정렬 시 최신 문서 우선 | Elasticsearch nori 분석기 튜닝 체크리스트 |

## Kibana Dev Tools 실행 블록
> 아래 요청은 모두 docs_read alias를 대상으로 하며, multi_match(best_fields) + recency/popularity function_score를 그대로 적용했다. `size`는 빠른 확인을 위해 3으로 제한했다.

```http
# Q1. 쿠버네티스 인그레스 (userdict + 기본 랭킹)
POST docs_read/_search
{
  "size": 3,
  "query": {
    "function_score": {
      "query": {
        "multi_match": {
          "query": "쿠버네티스 인그레스",
          "type": "best_fields",
          "fields": ["title^4", "summary^2", "body"]
        }
      },
      "functions": [
        { "gauss": { "publishedAt": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "popularityScore", "factor": 1.0, "missing": 0.0 } }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "highlight": { "fields": { "title": {}, "summary": {}, "body": {} } }
}

# Q2. k8s ingress controller (동의어 매칭)
POST docs_read/_search
{
  "size": 3,
  "query": {
    "function_score": {
      "query": {
        "multi_match": {
          "query": "k8s ingress controller",
          "type": "best_fields",
          "fields": ["title^4", "summary^2", "body"]
        }
      },
      "functions": [
        { "gauss": { "publishedAt": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "popularityScore", "factor": 1.0, "missing": 0.0 } }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "highlight": { "fields": { "title": {}, "summary": {}, "body": {} } }
}

# Q3. Nginx Ingress Controller (영문/요약 필드 매칭)
POST docs_read/_search
{
  "size": 3,
  "query": {
    "function_score": {
      "query": {
        "multi_match": {
          "query": "Nginx Ingress Controller",
          "type": "best_fields",
          "fields": ["title^4", "summary^2", "body"]
        }
      },
      "functions": [
        { "gauss": { "publishedAt": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "popularityScore", "factor": 1.0, "missing": 0.0 } }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "highlight": { "fields": { "title": {}, "summary": {}, "body": {} } }
}

# Q4. 엘라스틱서치 로그 수집 (동의어 확인)
POST docs_read/_search
{
  "size": 3,
  "query": {
    "function_score": {
      "query": {
        "multi_match": {
          "query": "엘라스틱서치 로그 수집",
          "type": "best_fields",
          "fields": ["title^4", "summary^2", "body"]
        }
      },
      "functions": [
        { "gauss": { "publishedAt": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "popularityScore", "factor": 1.0, "missing": 0.0 } }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "highlight": { "fields": { "title": {}, "summary": {}, "body": {} } }
}

# Q5. kafka 로그 파이프라인 + tag 필터(kafka)
POST docs_read/_search
{
  "size": 3,
  "query": {
    "function_score": {
      "query": {
        "bool": {
          "must": {
            "multi_match": {
              "query": "kafka 로그 파이프라인",
              "type": "best_fields",
              "fields": ["title^4", "summary^2", "body"]
            }
          },
          "filter": [
            { "terms": { "tags": ["kafka"] } }
          ]
        }
      },
      "functions": [
        { "gauss": { "publishedAt": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "popularityScore", "factor": 1.0, "missing": 0.0 } }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "highlight": { "fields": { "title": {}, "summary": {}, "body": {} } }
}

# Q6. nori 동의어 튜닝 (검색 품질 문서)
POST docs_read/_search
{
  "size": 3,
  "query": {
    "function_score": {
      "query": {
        "multi_match": {
          "query": "nori 동의어 튜닝",
          "type": "best_fields",
          "fields": ["title^4", "summary^2", "body"]
        }
      },
      "functions": [
        { "gauss": { "publishedAt": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "popularityScore", "factor": 1.0, "missing": 0.0 } }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "highlight": { "fields": { "title": {}, "summary": {}, "body": {} } }
}

# Q7. 검색 품질 + category=search (카테고리 필터)
POST docs_read/_search
{
  "size": 3,
  "query": {
    "function_score": {
      "query": {
        "bool": {
          "must": {
            "multi_match": {
              "query": "검색 품질",
              "type": "best_fields",
              "fields": ["title^4", "summary^2", "body"]
            }
          },
          "filter": [
            { "term": { "category": "search" } }
          ]
        }
      },
      "functions": [
        { "gauss": { "publishedAt": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "popularityScore", "factor": 1.0, "missing": 0.0 } }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "highlight": { "fields": { "title": {}, "summary": {}, "body": {} } }
}

# Q8. redis 캐시 스탬피드
POST docs_read/_search
{
  "size": 3,
  "query": {
    "function_score": {
      "query": {
        "multi_match": {
          "query": "redis 캐시 스탬피드",
          "type": "best_fields",
          "fields": ["title^4", "summary^2", "body"]
        }
      },
      "functions": [
        { "gauss": { "publishedAt": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "popularityScore", "factor": 1.0, "missing": 0.0 } }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "highlight": { "fields": { "title": {}, "summary": {}, "body": {} } }
}

# Q9. LLM 검색 요약
POST docs_read/_search
{
  "size": 3,
  "query": {
    "function_score": {
      "query": {
        "multi_match": {
          "query": "LLM 검색 요약",
          "type": "best_fields",
          "fields": ["title^4", "summary^2", "body"]
        }
      },
      "functions": [
        { "gauss": { "publishedAt": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "popularityScore", "factor": 1.0, "missing": 0.0 } }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "highlight": { "fields": { "title": {}, "summary": {}, "body": {} } }
}

# Q10. elasticsearch + 최신순 정렬 (recency 우선)
POST docs_read/_search
{
  "size": 3,
  "query": {
    "function_score": {
      "query": {
        "multi_match": {
          "query": "elasticsearch",
          "type": "best_fields",
          "fields": ["title^4", "summary^2", "body"]
        }
      },
      "functions": [
        { "gauss": { "publishedAt": { "origin": "now", "scale": "30d", "decay": 0.5 } } }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "sort": [
    { "publishedAt": { "order": "desc" } }
  ],
  "highlight": { "fields": { "title": {}, "summary": {}, "body": {} } }
}
```

## 확인 포인트
- 상위 결과가 요약 표의 기대 문서와 일치하는지 확인한다. 동일 문서가 여러 쿼리에서 반복되는 것은 baseline 고정 목적상 허용한다.
- 하이라이트가 title/summary/body에서 모두 응답되는지, recency 정렬(Q10)이 최신 문서(`publishedAt` 2024-03-21, popularityScore 0.91)를 우선 반환하는지 확인한다.
- 이후 튜닝 시 동일 쿼리 세트를 재사용해 품질 변화(랭킹/하이라이트/응답 시간)를 비교한다.
