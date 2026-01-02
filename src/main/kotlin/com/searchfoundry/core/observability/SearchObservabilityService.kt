package com.searchfoundry.core.observability

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.search.Profile
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.searchfoundry.core.document.Document
import com.searchfoundry.core.search.MultiMatchType
import com.searchfoundry.core.search.RankingTuning
import com.searchfoundry.core.search.SearchQuery
import com.searchfoundry.core.search.SearchQueryBuilder
import com.searchfoundry.core.search.SearchSort
import com.searchfoundry.eval.dataset.EvalQuery
import com.searchfoundry.eval.dataset.EvaluationDatasetLoader
import com.searchfoundry.support.config.ObservabilityProperties
import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 검색 프로파일 및 슬로우로그를 묶어 관측 리포트를 생성하는 유스케이스 서비스.
 * - QuerySet을 기반으로 profile=true 검색을 실행해 샤드별 브레이크다운을 저장한다.
 * - 슬로우로그 파일 tail을 함께 읽어 성능 이벤트를 재현 가능한 형태로 남긴다.
 */
@Service
class SearchObservabilityService(
    private val evaluationDatasetLoader: EvaluationDatasetLoader,
    private val searchQueryBuilder: SearchQueryBuilder,
    private val elasticsearchClient: ElasticsearchClient,
    private val observabilityReportWriter: ObservabilityReportWriter,
    private val slowlogReader: SlowlogReader,
    private val observabilityProperties: ObservabilityProperties,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(SearchObservabilityService::class.java)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    /**
     * 요청 파라미터를 해석해 검색 프로파일/슬로우로그를 수집하고 리포트를 기록한다.
     */
    fun capture(request: SearchObservationRequest): SearchObservationReport {
        val dataset = evaluationDatasetLoader.load(request.datasetId)
        val resolvedTopK = request.topK ?: observabilityProperties.defaultTopK
        val targetIndex = request.targetIndex ?: observabilityProperties.defaultTargetIndex
        val slowlogPath = request.slowlogPath ?: observabilityProperties.slowlogPath
        val slowlogTail = request.slowlogTail ?: observabilityProperties.slowlogTail

        val startedAt = Instant.now()
        val runId = buildRunId(dataset.datasetId, startedAt)
        val startNanos = System.nanoTime()

        val profiledQueries = if (request.includeProfile) {
            dataset.queries.map { query -> profileSingleQuery(query, resolvedTopK, targetIndex) }
        } else {
            emptyList()
        }

        val slowlogSnapshot = if (request.includeSlowlog) {
            slowlogReader.read(slowlogPath, slowlogTail)
        } else {
            null
        }

        val elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis()

        val result = SearchObservationResult(
            runId = runId,
            datasetId = dataset.datasetId,
            targetIndex = targetIndex,
            topK = resolvedTopK,
            totalQueries = dataset.queries.size,
            profiledQueries = profiledQueries,
            slowlogSnapshot = slowlogSnapshot,
            startedAt = startedAt,
            completedAt = Instant.now(),
            elapsedMs = elapsedMs
        )

        logger.info(
            "검색 관측 수집 완료(runId={}, datasetId={}, queries={}, includeProfile={}, includeSlowlog={}, elapsedMs={})",
            runId,
            dataset.datasetId,
            dataset.queries.size,
            request.includeProfile,
            request.includeSlowlog,
            elapsedMs
        )

        return observabilityReportWriter.write(result)
    }

    /**
     * 단일 쿼리에 대해 profile=true 검색을 실행하고 raw profile 객체를 JSON Map으로 변환한다.
     */
    private fun profileSingleQuery(query: EvalQuery, topK: Int, targetIndex: String): ProfiledQueryObservation {
        val searchQuery = query.toSearchQuery(topK)
        try {
            val response = elasticsearchClient.search({ builder ->
                builder
                    .index(targetIndex)
                    .query(searchQueryBuilder.buildSearchQuery(searchQuery))
                    .size(topK)
                    .trackTotalHits { it.enabled(true) }
                    .profile(true) // profile 결과 확보
            }, Document::class.java)

            val rawProfile = response.profile()?.let { profile -> convertProfileToMap(profile) }

            return ProfiledQueryObservation(
                queryId = query.queryId,
                queryText = query.queryText,
                tookMs = response.took(),
                totalHits = response.hits().total()?.value() ?: response.hits().hits().size.toLong(),
                rawProfile = rawProfile
            )
        } catch (ex: Exception) {
            logger.error(
                "검색 프로파일 수집 실패(queryId={}, queryText={}): {}",
                query.queryId,
                query.queryText,
                ex.message,
                ex
            )
            throw AppException(ErrorCode.INTERNAL_ERROR, "검색 프로파일 수집 중 오류가 발생했습니다.", ex.message)
        }
    }

    /**
     * Elastic profile 객체를 Map 형태로 직렬화해 JSON 디스크 기록 시 재사용한다.
     */
    private fun convertProfileToMap(profile: Profile): Map<String, Any?> =
        objectMapper.convertValue(profile, object : TypeReference<Map<String, Any?>>() {})

    private fun buildRunId(datasetId: String, startedAt: Instant): String =
        "obs_${datasetId}_${timestampFormatter.format(startedAt)}"

    /**
     * EvalQuery를 실행 가능한 SearchQuery로 변환한다.
     */
    private fun EvalQuery.toSearchQuery(topK: Int): SearchQuery {
        val filters = this.filters
        return SearchQuery(
            query = this.queryText,
            category = filters?.category,
            tags = filters?.tags ?: emptyList(),
            author = filters?.author,
            publishedFrom = filters?.publishedAtFrom,
            publishedTo = filters?.publishedAtTo,
            sort = SearchSort.RELEVANCE,
            page = 0,
            size = topK,
            multiMatchType = MultiMatchType.BEST_FIELDS,
            rankingTuning = RankingTuning.default()
        )
    }
}

/**
 * 관측 실행 입력 모델. topK/targetIndex/slowlog 설정은 프로퍼티 기본값과 병합한다.
 */
data class SearchObservationRequest(
    val datasetId: String,
    val topK: Int? = null,
    val targetIndex: String? = null,
    val slowlogPath: String? = null,
    val slowlogTail: Int? = null,
    val includeSlowlog: Boolean = true,
    val includeProfile: Boolean = true
)

/**
 * profile=true 검색 결과 요약 모델.
 */
data class ProfiledQueryObservation(
    val queryId: String,
    val queryText: String,
    val tookMs: Long,
    val totalHits: Long,
    val rawProfile: Map<String, Any?>?
)

/**
 * 관측 실행 결과(파일 기록 전에 사용).
 */
data class SearchObservationResult(
    val runId: String,
    val datasetId: String,
    val targetIndex: String,
    val topK: Int,
    val totalQueries: Int,
    val profiledQueries: List<ProfiledQueryObservation>,
    val slowlogSnapshot: SlowlogSnapshot?,
    val startedAt: Instant,
    val completedAt: Instant,
    val elapsedMs: Long
)
