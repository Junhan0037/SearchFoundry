package com.searchfoundry.core.observability

import com.searchfoundry.core.search.DocumentSearchService
import com.searchfoundry.core.search.MultiMatchType
import com.searchfoundry.core.search.RankingTuning
import com.searchfoundry.core.search.SearchQuery
import com.searchfoundry.core.search.SearchSort
import com.searchfoundry.eval.dataset.EvalQuery
import com.searchfoundry.eval.dataset.EvaluationDatasetLoader
import com.searchfoundry.support.config.PerformanceBenchmarkProperties
import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * 고정 QuerySet 기반으로 검색 성능(P50/P95/QPS)을 계측하고 리포트를 생성하는 서비스.
 * - Warmup 실행으로 캐시 편차를 줄이고, 반복 측정으로 분포 지표를 계산한다.
 * - baseline 리포트 ID가 주어지면 비교 리포트까지 함께 작성한다.
 */
@Service
class PerformanceBenchmarkService(
    private val evaluationDatasetLoader: EvaluationDatasetLoader,
    private val documentSearchService: DocumentSearchService,
    private val performanceReportWriter: PerformanceReportWriter,
    private val performanceReportComparator: PerformanceReportComparator,
    private val properties: PerformanceBenchmarkProperties
) {
    private val logger = LoggerFactory.getLogger(PerformanceBenchmarkService::class.java)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    /**
     * 요청 파라미터를 해석해 검색 벤치마크를 실행하고 리포트/비교 리포트를 생성한다.
     */
    fun run(request: PerformanceBenchmarkRequest = PerformanceBenchmarkRequest()): PerformanceBenchmarkOutcome {
        val resolved = request.resolve(properties)
        val dataset = evaluationDatasetLoader.load(resolved.datasetId)
        if (dataset.queries.isEmpty()) {
            throw AppException(
                ErrorCode.BAD_REQUEST,
                "성능 측정을 위한 쿼리셋이 비어 있습니다.",
                "datasetId=${resolved.datasetId}"
            )
        }

        val startedAt = Instant.now()
        val runId = buildRunId(resolved.reportIdPrefix, resolved.datasetId, startedAt)
        val startNanos = System.nanoTime()
        val samples = mutableListOf<QueryLatencySample>()

        dataset.queries.forEach { query ->
            runWarmups(query, resolved)
            samples += runMeasuredIterations(query, resolved)
        }

        val elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis()
        val perQuery = summarizePerQuery(samples)
        val latency = summarizeLatency(samples.map { it.tookMs })
        val totalSamples = samples.size
        val qps = if (elapsedMs > 0) totalSamples / (elapsedMs / 1000.0) else totalSamples.toDouble()

        val result = PerformanceBenchmarkResult(
            runId = runId,
            datasetId = resolved.datasetId,
            targetIndex = resolved.targetIndex,
            topK = resolved.topK,
            iterations = resolved.iterations,
            warmups = resolved.warmups,
            totalQueries = dataset.queries.size,
            totalSamples = totalSamples,
            startedAt = startedAt,
            completedAt = Instant.now(),
            elapsedMs = elapsedMs,
            qps = qps,
            latency = latency,
            perQuery = perQuery
        )

        val report = performanceReportWriter.write(result)
        val comparison = resolved.baselineReportId?.let { baselineId ->
            performanceReportComparator.compareAndWrite(baselineId, report.reportId)
        }

        logger.info(
            "검색 성능 벤치마크 완료(runId={}, dataset={}, queries={}, samples={}, elapsedMs={}, qps={})",
            runId,
            resolved.datasetId,
            dataset.queries.size,
            totalSamples,
            elapsedMs,
            "%.3f".format(qps)
        )

        return PerformanceBenchmarkOutcome(
            result = result,
            report = report,
            comparison = comparison
        )
    }

    /**
     * warmup 수행으로 파일시스템/캐시 상태를 정렬해 측정 편차를 줄인다.
     */
    private fun runWarmups(
        query: EvalQuery,
        resolved: ResolvedPerformanceBenchmarkRequest
    ) {
        repeat(resolved.warmups) {
            documentSearchService.search(query.toSearchQuery(resolved.topK, resolved.targetIndex))
        }
    }

    /**
     * 실제 측정 반복을 수행하고 took(ms) 샘플을 반환한다.
     */
    private fun runMeasuredIterations(
        query: EvalQuery,
        resolved: ResolvedPerformanceBenchmarkRequest
    ): List<QueryLatencySample> =
        (1..resolved.iterations).map { iteration ->
            val searchResult = documentSearchService.search(query.toSearchQuery(resolved.topK, resolved.targetIndex))
            QueryLatencySample(
                queryId = query.queryId,
                queryText = query.queryText,
                iteration = iteration,
                tookMs = searchResult.tookMs,
                totalHits = searchResult.total
            )
        }

    /**
     * 쿼리별 took 분포를 계산해 정렬된 리스트를 반환한다.
     */
    private fun summarizePerQuery(samples: List<QueryLatencySample>): List<QueryLatencyStats> =
        samples
            .groupBy { it.queryId }
            .map { (queryId, querySamples) ->
                val queryText = querySamples.first().queryText
                QueryLatencyStats(
                    queryId = queryId,
                    queryText = queryText,
                    samples = querySamples,
                    latency = summarizeLatency(querySamples.map { it.tookMs })
                )
            }
            .sortedByDescending { it.latency.p95Ms }

    /**
     * 전체 분포에 대한 P50/P95/평균/최소/최대 값을 계산한다.
     */
    private fun summarizeLatency(values: List<Long>): LatencyStats {
        if (values.isEmpty()) {
            return LatencyStats(minMs = 0, p50Ms = 0, p95Ms = 0, maxMs = 0, avgMs = 0.0)
        }
        val sorted = values.sorted()
        return LatencyStats(
            minMs = sorted.first(),
            p50Ms = percentile(sorted, 0.5),
            p95Ms = percentile(sorted, 0.95),
            maxMs = sorted.last(),
            avgMs = sorted.average()
        )
    }

    /**
     * 단순한 percentile 계산(ceil 기반)으로 분포 지표를 구한다.
     */
    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        val rank = ceil(percentile * sorted.size).toInt() - 1
        val index = rank.coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun buildRunId(prefix: String, datasetId: String, startedAt: Instant): String =
        "${prefix}_${datasetId}_${timestampFormatter.format(startedAt)}"
}

/**
 * 벤치마크 실행 요청. 비어 있는 값은 프로퍼티 기본값으로 대체한다.
 */
data class PerformanceBenchmarkRequest(
    val datasetId: String? = null,
    val topK: Int? = null,
    val iterations: Int? = null,
    val warmups: Int? = null,
    val targetIndex: String? = null,
    val reportIdPrefix: String? = null,
    val baselineReportId: String? = null
) {
    fun resolve(properties: PerformanceBenchmarkProperties): ResolvedPerformanceBenchmarkRequest {
        val resolvedDataset = datasetId?.takeIf { it.isNotBlank() } ?: properties.datasetId
        val resolvedTopK = (topK ?: properties.topK).coerceAtLeast(1)
        val resolvedIterations = (iterations ?: properties.iterations).coerceAtLeast(1)
        val resolvedWarmups = (warmups ?: properties.warmups).coerceAtLeast(0)
        val resolvedTargetIndex = targetIndex?.takeIf { it.isNotBlank() } ?: properties.targetIndex
        val resolvedPrefix = reportIdPrefix?.takeIf { it.isNotBlank() } ?: properties.reportIdPrefix
        val resolvedBaseline = baselineReportId?.takeIf { it.isNotBlank() } ?: properties.baselineReportId

        return ResolvedPerformanceBenchmarkRequest(
            datasetId = resolvedDataset,
            topK = resolvedTopK,
            iterations = resolvedIterations,
            warmups = resolvedWarmups,
            targetIndex = resolvedTargetIndex,
            reportIdPrefix = resolvedPrefix,
            baselineReportId = resolvedBaseline
        )
    }
}

/**
 * 프로퍼티 적용이 끝난 실 실행 요청.
 */
data class ResolvedPerformanceBenchmarkRequest(
    val datasetId: String,
    val topK: Int,
    val iterations: Int,
    val warmups: Int,
    val targetIndex: String,
    val reportIdPrefix: String,
    val baselineReportId: String?
)

/**
 * 벤치마크 실행 결과/리포트 메타데이터.
 */
data class PerformanceBenchmarkOutcome(
    val result: PerformanceBenchmarkResult,
    val report: PerformanceReport,
    val comparison: PerformanceComparisonReport?
)

/**
 * took 샘플 및 분포 요약.
 */
data class PerformanceBenchmarkResult(
    val runId: String,
    val datasetId: String,
    val targetIndex: String,
    val topK: Int,
    val iterations: Int,
    val warmups: Int,
    val totalQueries: Int,
    val totalSamples: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val elapsedMs: Long,
    val qps: Double,
    val latency: LatencyStats,
    val perQuery: List<QueryLatencyStats>
)

/**
 * 단일 샘플을 구성하는 took/iteration/totalHits 정보.
 */
data class QueryLatencySample(
    val queryId: String,
    val queryText: String,
    val iteration: Int,
    val tookMs: Long,
    val totalHits: Long
)

/**
 * 쿼리별 분포 요약과 원본 샘플.
 */
data class QueryLatencyStats(
    val queryId: String,
    val queryText: String,
    val samples: List<QueryLatencySample>,
    val latency: LatencyStats
)

/**
 * P50/P95/평균/최소/최대 값으로 구성된 분포 요약.
 */
data class LatencyStats(
    val minMs: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long,
    val avgMs: Double
)

/**
 * EvalQuery를 SearchQuery로 변환해 DocumentSearchService에서 재사용한다.
 */
private fun EvalQuery.toSearchQuery(topK: Int, targetIndex: String): SearchQuery {
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
        targetIndex = targetIndex,
        rankingTuning = RankingTuning.default()
    )
}
