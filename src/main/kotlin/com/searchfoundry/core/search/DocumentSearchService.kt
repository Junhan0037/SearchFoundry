package com.searchfoundry.core.search

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.Highlight
import co.elastic.clients.elasticsearch.core.search.HighlightField
import co.elastic.clients.elasticsearch.core.search.Hit
import com.searchfoundry.core.document.Document
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
    private val elasticsearchClient: ElasticsearchClient,
    private val searchQueryBuilder: SearchQueryBuilder
) {
    private val logger = LoggerFactory.getLogger(DocumentSearchService::class.java)

    private val defaultReadAlias = "docs_read"

    /**
     * 필드 가중치/필터/정렬 조건을 조합한 검색을 수행한다.
     */
    fun search(request: SearchQuery): SearchResult {
        val targetIndex = request.targetIndex ?: defaultReadAlias
        val scoredQuery = searchQueryBuilder.buildSearchQuery(request)
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
        val suggestQuery = searchQueryBuilder.buildSuggestQuery(request)

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

}

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

data class SearchHit(
    val document: Document,
    val score: Double?,
    val highlights: Map<String, List<String>>
)

data class SearchResult(
    val total: Long,
    val page: Int,
    val size: Int,
    val tookMs: Long,
    val hits: List<SearchHit>
)

data class SuggestQuery(
    val query: String,
    val category: String?,
    val size: Int,
    val targetIndex: String? = null
)

data class Suggestion(
    val title: String,
    val documentId: String,
    val category: String,
    val author: String,
    val score: Double?
)

data class SuggestResult(
    val query: String,
    val size: Int,
    val tookMs: Long,
    val suggestions: List<Suggestion>
)
