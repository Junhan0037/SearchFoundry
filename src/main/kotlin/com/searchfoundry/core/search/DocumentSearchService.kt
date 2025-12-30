package com.searchfoundry.core.search

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.Time
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreQuery
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery
import co.elastic.clients.elasticsearch._types.query_dsl.RankFeatureQuery
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.Highlight
import co.elastic.clients.elasticsearch.core.search.HighlightField
import co.elastic.clients.elasticsearch.core.search.Hit
import com.searchfoundry.core.document.Document
import com.searchfoundry.core.search.PopularityMode.FIELD_VALUE_FACTOR
import com.searchfoundry.core.search.PopularityMode.RANK_FEATURE
import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Elasticsearch docs_read alias를 대상으로 검색/자동완성 쿼리를 실행하는 서비스.
 * - 기본 검색은 multi_match(best_fields) + 필터 + 하이라이트를 적용한다.
 * - 정렬 모드에 따라 recency/popularity를 function_score로 가중한다.
 */
@Service
class DocumentSearchService(
    private val elasticsearchClient: ElasticsearchClient
) {
    private val logger = LoggerFactory.getLogger(DocumentSearchService::class.java)

    private val defaultReadAlias = "docs_read"
    private val defaultRankingTuning = RankingTuning.default()

    /**
     * 필드 가중치/필터/정렬 조건을 조합한 검색을 수행한다.
     */
    fun search(request: SearchQuery): SearchResult {
        val targetIndex = request.targetIndex ?: defaultReadAlias
        val baseQuery = buildBaseQuery(request)
        val scoredQuery = applyFunctionScore(baseQuery, request.sort, request.rankingTuning)
        val highlight = defaultHighlight()

        try {
            val response = elasticsearchClient.search({ builder ->
                builder
                    .index(targetIndex)
                    .query(scoredQuery)
                    .from(request.page * request.size)
                    .size(request.size)
                    .trackTotalHits { it.enabled(true) } // 실측 total hits 확보
                    .highlight(highlight)

                // 최신순 정렬 시 publishedAt을 내림차순 정렬한다.
                if (request.sort == SearchSort.RECENCY) {
                    builder.sort { sort ->
                        sort.field { field -> field.field("publishedAt").order(SortOrder.Desc) }
                    }
                }
                builder
            }, Document::class.java)

            return toSearchResult(response, request)
        } catch (ex: Exception) {
            logger.error("검색 실행 실패(query={}, sort={}): {}", request.query, request.sort, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "검색 실행 중 오류가 발생했습니다.", ex.message)
        }
    }

    /**
     * edge_ngram 기반 titleAutocomplete 필드를 사용해 간단한 자동완성 제안을 반환한다.
     */
    fun suggest(request: SuggestQuery): SuggestResult {
        val targetIndex = request.targetIndex ?: defaultReadAlias
        val suggestQuery = buildSuggestQuery(request)

        try {
            val response = elasticsearchClient.search({ builder ->
                builder
                    .index(targetIndex)
                    .query(suggestQuery)
                    .size(request.size)
                    .trackTotalHits { it.enabled(false) } // 자동완성은 total이 중요하지 않으므로 비활성화
                    .source { source ->
                        // 제안에 필요한 최소 필드만 로드해 전송 비용을 줄인다.
                        source.filter { filter ->
                            filter.includes("id", "title", "category", "author", "publishedAt", "popularityScore")
                        }
                    }

                // 점수 우선 + 최신순으로 제안 정렬. popularityScore는 function_score에 맡긴다.
                builder.sort { sort -> sort.score { s -> s.order(SortOrder.Desc) } }
                builder.sort { sort -> sort.field { field -> field.field("publishedAt").order(SortOrder.Desc) } }
                builder
            }, Document::class.java)

            return toSuggestResult(response, request)
        } catch (ex: Exception) {
            logger.error("자동완성 실행 실패(query={}): {}", request.query, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "자동완성 실행 중 오류가 발생했습니다.", ex.message)
        }
    }

    /**
     * multi_match + 필터로 기본 bool 쿼리를 구성한다.
     */
    private fun buildBaseQuery(request: SearchQuery): Query {
        val boolBuilder = BoolQuery.Builder()

        boolBuilder.must { must ->
            // multi_match: 여러 필드에 동시에 적용해서 매칭 점수를 계산한다.
            val multiMatchBuilder = MultiMatchQuery.Builder()
                .query(request.query)
                .type(request.multiMatchType.toTextQueryType()) // 실험/튜닝 시 best_fields/most_fields/cross_fields 전환 지원.
                .fields("title^4", "summary^2", "body") // “제목 > 요약 > 본문” 순으로 중요도

            // // most_fields에서 필드별 점수 쏠림을 완화하기 위해 tie_breaker를 낮게 설정.
            if (request.multiMatchType == MultiMatchType.MOST_FIELDS) {
                multiMatchBuilder.tieBreaker(0.2)
            }

            must.multiMatch(multiMatchBuilder.build())
        }

        request.category?.let { category ->
            boolBuilder.filter { filter ->
                // term: 정확히 일치(exact match) 시키는 조건.
                filter.term { term -> term.field("category").value(category) }
            }
        }

        if (request.tags.isNotEmpty()) {
            boolBuilder.filter { filter ->
                // tags가 여러 개라서 terms 사용
                filter.terms { terms ->
                    terms.field("tags")
                        // "목록 중 하나(any)"에 매칭되면 통과. FieldValue.of: Kotlin client는 타입 안정성을 위해 사용.
                        .terms { values -> values.value(request.tags.map { tag -> FieldValue.of(tag) }) }
                }
            }
        }

        request.author?.let { author ->
            boolBuilder.filter { filter ->
                filter.term { term -> term.field("author").value(author) }
            }
        }

        if (request.publishedFrom != null || request.publishedTo != null) {
            val rangeQuery = Query.of { query ->
                query.range { range ->
                    range.date { date ->
                        date.field("publishedAt")
                        request.publishedFrom?.let { date.gte(it.toString()) }
                        request.publishedTo?.let { date.lte(it.toString()) }
                        date
                    }
                }
            }
            boolBuilder.filter(rangeQuery)
        }

        // popularityScore를 rank_feature saturation으로 사용할 때 should 스코어에 가중한다.
        val popularity = request.rankingTuning.popularity
        if (popularity.enabled && popularity.mode == RANK_FEATURE) {
            boolBuilder.should { should ->
                should.rankFeature(buildRankFeature(popularity))
            }
        }

        return Query.of { query -> query.bool(boolBuilder.build()) }
    }

    // rank_feature saturation으로 popularityScore를 점수에 녹인다.
    private fun buildRankFeature(popularity: PopularityTuning): RankFeatureQuery =
        RankFeatureQuery.Builder()
            .field("popularityScore")
            .boost(popularity.rankFeatureBoost.toFloat())
            .saturation { saturation -> saturation.pivot(popularity.saturationPivot.toFloat()) }
            .build()

    /**
     * 정렬 모드에 따라 recency/popularity function_score를 조합한다.
     */
    private fun applyFunctionScore(baseQuery: Query, sort: SearchSort, rankingTuning: RankingTuning): Query {
        val functions = when (sort) {
            SearchSort.RELEVANCE -> listOfNotNull(recencyDecayFunction(rankingTuning), popularityBoostFunction(rankingTuning))
            SearchSort.RECENCY -> listOfNotNull(recencyDecayFunction(rankingTuning))
            SearchSort.POPULARITY -> listOfNotNull(popularityBoostFunction(rankingTuning))
        }

        if (functions.isEmpty()) {
            return baseQuery
        }

        val functionScoreQuery = FunctionScoreQuery.Builder()
            .query(baseQuery)
            .functions(functions)
            .scoreMode(rankingTuning.scoreMode)
            .boostMode(rankingTuning.boostMode)
            .build()

        return Query.of { query -> query.functionScore(functionScoreQuery) }
    }

    // 최신성 가중치: origin=now 기준 gauss decay를 적용한다.
    private fun recencyDecayFunction(rankingTuning: RankingTuning): FunctionScore? {
        val recency = rankingTuning.recency
        if (!recency.enabled) {
            return null
        }

        return FunctionScore.of { function ->
            // gauss decay: 기준점(origin)에서 멀어질수록 점수를 점진적으로 감쇠.
            function.gauss { decay ->
                decay.date { date ->
                    date.field("publishedAt")
                        .placement { placement ->
                            placement
                                .origin("now")
                                .scale(Time.of { time -> time.time(recency.scale) })
                                .decay(recency.decay)
                        }
                    date
                }
            }.weight(recency.weight)
        }
    }

    // popularityScore를 field_value_factor 또는 비활성화 옵션으로 반영한다.
    private fun popularityBoostFunction(rankingTuning: RankingTuning): FunctionScore? {
        val popularity = rankingTuning.popularity
        if (!popularity.enabled || popularity.mode != FIELD_VALUE_FACTOR) {
            return null
        }

        return FunctionScore.of { function ->
            // fieldValueFactor: rank_feature 값을 log1p 등으로 압축하거나 계수를 곱해 가중한다.
            function.fieldValueFactor { factor ->
                factor.field("popularityScore")
                    .factor(popularity.factor)
                    .missing(popularity.missing)
                popularity.modifier?.let { modifier -> factor.modifier(modifier) }
            }.weight(popularity.weight)
        }
    }

    /**
     * 검색 결과를 도메인 모델로 변환한다.
     */
    private fun toSearchResult(response: SearchResponse<Document>, request: SearchQuery): SearchResult {
        val hits = response.hits().hits().mapNotNull { hit ->
            val source = hit.source() ?: return@mapNotNull null
            SearchHit(
                document = source,
                score = hit.score(),
                highlights = extractHighlights(hit)
            )
        }

        val totalHits = response.hits().total()?.value() ?: hits.size.toLong()

        return SearchResult(
            total = totalHits,
            page = request.page,
            size = request.size,
            tookMs = response.took(),
            hits = hits
        )
    }

    /**
     * 자동완성 결과를 도메인 모델로 변환한다.
     */
    private fun toSuggestResult(response: SearchResponse<Document>, request: SuggestQuery): SuggestResult {
        val suggestions = response.hits().hits().mapNotNull { hit ->
            val source = hit.source() ?: return@mapNotNull null
            Suggestion(
                title = source.title,
                documentId = source.id.toString(),
                category = source.category,
                author = source.author,
                score = hit.score()
            )
        }

        return SuggestResult(
            query = request.query,
            size = request.size,
            tookMs = response.took(),
            suggestions = suggestions
        )
    }

    // 검색 결과 하이라이트 설정. 기본 태그는 Elasticsearch 기본값 사용.
    private fun defaultHighlight(): Highlight =
        Highlight.Builder()
            .fields("title", HighlightField.Builder().build())
            .fields("summary", HighlightField.Builder().build())
            .fields("body", HighlightField.Builder().build())
            .build()

    // 하이라이트 맵을 안전하게 추출한다.
    private fun extractHighlights(hit: Hit<Document>): Map<String, List<String>> {
        val highlight = hit.highlight() ?: return emptyMap()
        return highlight.mapValues { it.value }
    }

    /**
     * titleAutocomplete 필드에 match_phrase_prefix를 적용한 자동완성 쿼리를 생성한다.
     * - 카테고리 필터가 있으면 bool.filter로 추가한다.
     * - recency/popularity는 function_score로 가중한다.
     */
    private fun buildSuggestQuery(request: SuggestQuery): Query {
        val baseQuery = Query.of { query ->
            query.matchPhrasePrefix { prefix ->
                prefix.field("titleAutocomplete")
                    .query(request.query)
                    .maxExpansions(50)
            }
        }

        val boolBuilder = BoolQuery.Builder()
        boolBuilder.must(baseQuery)

        request.category?.let { category ->
            boolBuilder.filter { filter ->
                filter.term { term -> term.field("category").value(category) }
            }
        }

        val boolQuery = Query.of { query -> query.bool(boolBuilder.build()) }
        return applyFunctionScore(boolQuery, SearchSort.POPULARITY, defaultRankingTuning)
    }

    private fun MultiMatchType.toTextQueryType(): TextQueryType = when (this) {
        MultiMatchType.BEST_FIELDS -> TextQueryType.BestFields
        MultiMatchType.MOST_FIELDS -> TextQueryType.MostFields
        MultiMatchType.CROSS_FIELDS -> TextQueryType.CrossFields
    }
}

/**
 * 검색 요청 파라미터 도메인 모델.
 */
data class SearchQuery(
    val query: String,
    val category: String?,
    val tags: List<String>,
    val author: String?,
    val publishedFrom: Instant?,
    val publishedTo: Instant?,
    val sort: SearchSort,
    val multiMatchType: MultiMatchType = MultiMatchType.BEST_FIELDS,
    val page: Int,
    val size: Int,
    val targetIndex: String? = null,
    val rankingTuning: RankingTuning = RankingTuning.default()
)

/**
 * 검색 정렬 모드 정의.
 */
enum class SearchSort {
    RELEVANCE,
    RECENCY,
    POPULARITY
}

enum class MultiMatchType {
    BEST_FIELDS,
    MOST_FIELDS,
    CROSS_FIELDS
}

/**
 * 검색 결과 단일 히트 모델.
 */
data class SearchHit(
    val document: Document,
    val score: Double?,
    val highlights: Map<String, List<String>>
)

/**
 * 검색 결과 전체 모델.
 */
data class SearchResult(
    val total: Long,
    val page: Int,
    val size: Int,
    val tookMs: Long,
    val hits: List<SearchHit>
)

/**
 * 자동완성 요청 모델.
 */
data class SuggestQuery(
    val query: String,
    val category: String?,
    val size: Int,
    val targetIndex: String? = null
)

/**
 * 자동완성 단일 제안 결과.
 */
data class Suggestion(
    val title: String,
    val documentId: String,
    val category: String,
    val author: String,
    val score: Double?
)

/**
 * 자동완성 응답 모델.
 */
data class SuggestResult(
    val query: String,
    val size: Int,
    val tookMs: Long,
    val suggestions: List<Suggestion>
)
