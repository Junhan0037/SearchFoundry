package com.searchfoundry.api.search

import com.searchfoundry.core.search.DocumentSearchService
import com.searchfoundry.core.search.SearchHit
import com.searchfoundry.core.search.SearchQuery
import com.searchfoundry.core.search.SearchResult
import com.searchfoundry.core.search.SearchSort
import com.searchfoundry.core.search.SuggestQuery
import com.searchfoundry.core.search.SuggestResult
import com.searchfoundry.core.search.Suggestion
import com.searchfoundry.support.api.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 검색/자동완성 Public API 컨트롤러.
 */
@RestController
@RequestMapping("/api")
@Validated
class SearchController(
    private val documentSearchService: DocumentSearchService
) {

    /**
     * 기본 검색.
     */
    @GetMapping("/search")
    fun search(@Valid @ModelAttribute request: SearchRequestDto): ApiResponse<SearchResponse> {
        val result = documentSearchService.search(request.toQuery())
        return ApiResponse.success(SearchResponse.from(result))
    }

    /**
     * edge_ngram 기반 자동완성.
     */
    @GetMapping("/suggest")
    fun suggest(@Valid @ModelAttribute request: SuggestRequestDto): ApiResponse<SuggestResponse> {
        val result = documentSearchService.suggest(request.toQuery())
        return ApiResponse.success(SuggestResponse.from(result))
    }
}

/**
 * 검색 요청 DTO. GET 파라미터를 객체로 바인딩한다.
 */
data class SearchRequestDto(
    @field:NotBlank(message = "q는 필수입니다.")
    val q: String,
    val category: String? = null,
    val author: String? = null,
    val tags: List<String> = emptyList(),
    @field:Min(value = 0, message = "page는 0 이상이어야 합니다.")
    val page: Int = 0,
    @field:Min(value = 1, message = "size는 1 이상이어야 합니다.")
    @field:Max(value = 100, message = "size는 100 이하로 요청해주세요.")
    val size: Int = 10,
    val sort: SearchSort = SearchSort.RELEVANCE,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    val publishedFrom: Instant? = null,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    val publishedTo: Instant? = null
) {
    fun toQuery(): SearchQuery = SearchQuery(
        query = q.trim(),
        category = category?.takeIf { it.isNotBlank() },
        tags = tags.filter { it.isNotBlank() },
        author = author?.takeIf { it.isNotBlank() },
        publishedFrom = publishedFrom,
        publishedTo = publishedTo,
        sort = sort,
        page = page,
        size = size
    )
}

/**
 * 자동완성 요청 DTO.
 */
data class SuggestRequestDto(
    @field:NotBlank(message = "q는 필수입니다.")
    val q: String,
    val category: String? = null,
    @field:Min(value = 1, message = "size는 1 이상이어야 합니다.")
    @field:Max(value = 50, message = "size는 50 이하로 요청해주세요.")
    val size: Int = 5
) {
    fun toQuery(): SuggestQuery = SuggestQuery(
        query = q.trim(),
        category = category?.takeIf { it.isNotBlank() },
        size = size
    )
}

/**
 * 검색 응답 DTO.
 */
data class SearchResponse(
    val total: Long,
    val page: Int,
    val size: Int,
    val tookMs: Long,
    val hits: List<SearchHitResponse>
) {
    companion object {
        fun from(result: SearchResult): SearchResponse = SearchResponse(
            total = result.total,
            page = result.page,
            size = result.size,
            tookMs = result.tookMs,
            hits = result.hits.map { SearchHitResponse.from(it) }
        )
    }
}

/**
 * 검색 결과 단건 DTO.
 */
data class SearchHitResponse(
    val id: String,
    val title: String,
    val summary: String?,
    val body: String,
    val tags: List<String>,
    val category: String,
    val author: String,
    val publishedAt: Instant,
    val popularityScore: Double,
    val score: Double?,
    val highlights: Map<String, List<String>>
) {
    companion object {
        fun from(hit: SearchHit): SearchHitResponse {
            val doc = hit.document
            return SearchHitResponse(
                id = doc.id.toString(),
                title = doc.title,
                summary = doc.summary,
                body = doc.body,
                tags = doc.tags,
                category = doc.category,
                author = doc.author,
                publishedAt = doc.publishedAt,
                popularityScore = doc.popularityScore,
                score = hit.score,
                highlights = hit.highlights
            )
        }
    }
}

/**
 * 자동완성 응답 DTO.
 */
data class SuggestResponse(
    val query: String,
    val size: Int,
    val tookMs: Long,
    val suggestions: List<SuggestItemResponse>
) {
    companion object {
        fun from(result: SuggestResult): SuggestResponse = SuggestResponse(
            query = result.query,
            size = result.size,
            tookMs = result.tookMs,
            suggestions = result.suggestions.map { SuggestItemResponse.from(it) }
        )
    }
}

/**
 * 자동완성 단건 DTO.
 */
data class SuggestItemResponse(
    val title: String,
    val documentId: String,
    val category: String,
    val author: String,
    val score: Double?
) {
    companion object {
        fun from(item: Suggestion): SuggestItemResponse = SuggestItemResponse(
            title = item.title,
            documentId = item.documentId,
            category = item.category,
            author = item.author,
            score = item.score
        )
    }
}
