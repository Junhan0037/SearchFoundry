package com.searchfoundry.api.admin

import com.searchfoundry.core.observability.LatencyStats
import com.searchfoundry.core.observability.PerformanceBenchmarkOutcome
import com.searchfoundry.core.observability.PerformanceBenchmarkRequest
import com.searchfoundry.core.observability.PerformanceBenchmarkService
import com.searchfoundry.core.observability.PerformanceComparison
import com.searchfoundry.core.observability.PerformanceReport
import com.searchfoundry.core.observability.QueryLatencyChange
import com.searchfoundry.core.observability.QueryLatencyStats
import com.searchfoundry.core.observability.NumericDelta
import com.searchfoundry.support.api.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 고정 QuerySet 기반 검색 성능 벤치마크를 실행하는 Admin API.
 */
@RestController
@RequestMapping("/admin/performance")
@Validated
class PerformanceAdminController(
    private val performanceBenchmarkService: PerformanceBenchmarkService
) {

    /**
     * P50/P95/QPS를 포함한 검색 성능 벤치마크를 실행한다.
     * - 요청이 비어 있으면 performance.benchmark.* 기본값을 사용한다.
     * - baselineReportId가 지정되면 비교 리포트까지 함께 생성한다.
     */
    @PostMapping("/benchmark")
    fun runBenchmark(
        @RequestBody(required = false) @Valid request: PerformanceBenchmarkRequestDto?
    ): ApiResponse<PerformanceBenchmarkResponse> {
        val outcome = performanceBenchmarkService.run(request?.toDomain() ?: PerformanceBenchmarkRequest())
        return ApiResponse.success(PerformanceBenchmarkResponse.from(outcome))
    }
}

data class PerformanceBenchmarkRequestDto(
    val datasetId: String? = null,
    @field:Min(value = 1, message = "topK는 1 이상이어야 합니다.")
    val topK: Int? = null,
    @field:Min(value = 1, message = "iterations는 1 이상이어야 합니다.")
    val iterations: Int? = null,
    @field:Min(value = 0, message = "warmups는 0 이상이어야 합니다.")
    val warmups: Int? = null,
    val targetIndex: String? = null,
    val reportIdPrefix: String? = null,
    val baselineReportId: String? = null
) {
    fun toDomain(): PerformanceBenchmarkRequest = PerformanceBenchmarkRequest(
        datasetId = datasetId?.trim().takeUnless { it.isNullOrBlank() },
        topK = topK,
        iterations = iterations,
        warmups = warmups,
        targetIndex = targetIndex?.trim().takeUnless { it.isNullOrBlank() },
        reportIdPrefix = reportIdPrefix?.trim().takeUnless { it.isNullOrBlank() },
        baselineReportId = baselineReportId?.trim().takeUnless { it.isNullOrBlank() }
    )
}

data class PerformanceBenchmarkResponse(
    val reportId: String,
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
    val latency: LatencyStatsResponse,
    val queries: List<QueryLatencyResponse>,
    val report: PerformanceReportResponse,
    val comparison: PerformanceComparisonResponse?
) {
    companion object {
        fun from(outcome: PerformanceBenchmarkOutcome): PerformanceBenchmarkResponse = PerformanceBenchmarkResponse(
            reportId = outcome.result.runId,
            datasetId = outcome.result.datasetId,
            targetIndex = outcome.result.targetIndex,
            topK = outcome.result.topK,
            iterations = outcome.result.iterations,
            warmups = outcome.result.warmups,
            totalQueries = outcome.result.totalQueries,
            totalSamples = outcome.result.totalSamples,
            startedAt = outcome.result.startedAt,
            completedAt = outcome.result.completedAt,
            elapsedMs = outcome.result.elapsedMs,
            qps = outcome.result.qps,
            latency = LatencyStatsResponse.from(outcome.result.latency),
            queries = outcome.result.perQuery.map { QueryLatencyResponse.from(it) },
            report = PerformanceReportResponse.from(outcome.report),
            comparison = outcome.comparison?.let { PerformanceComparisonResponse.from(it.comparison, it.markdownPath.toAbsolutePath().toString()) }
        )
    }
}

data class LatencyStatsResponse(
    val minMs: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long,
    val avgMs: Double
) {
    companion object {
        fun from(stats: LatencyStats): LatencyStatsResponse = LatencyStatsResponse(
            minMs = stats.minMs,
            p50Ms = stats.p50Ms,
            p95Ms = stats.p95Ms,
            maxMs = stats.maxMs,
            avgMs = stats.avgMs
        )
    }
}

data class QueryLatencyResponse(
    val queryId: String,
    val queryText: String,
    val samples: List<Long>,
    val latency: LatencyStatsResponse
) {
    companion object {
        fun from(stats: QueryLatencyStats): QueryLatencyResponse = QueryLatencyResponse(
            queryId = stats.queryId,
            queryText = stats.queryText,
            samples = stats.samples.map { it.tookMs },
            latency = LatencyStatsResponse.from(stats.latency)
        )
    }
}

data class PerformanceReportResponse(
    val reportId: String,
    val datasetId: String,
    val targetIndex: String,
    val metricsPath: String,
    val summaryPath: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val totalQueries: Int,
    val totalSamples: Int
) {
    companion object {
        fun from(report: PerformanceReport): PerformanceReportResponse = PerformanceReportResponse(
            reportId = report.reportId,
            datasetId = report.datasetId,
            targetIndex = report.targetIndex,
            metricsPath = report.metricsPath.toAbsolutePath().toString(),
            summaryPath = report.summaryPath.toAbsolutePath().toString(),
            startedAt = report.startedAt,
            completedAt = report.completedAt,
            totalQueries = report.totalQueries,
            totalSamples = report.totalSamples
        )
    }
}

data class PerformanceComparisonResponse(
    val beforeReportId: String,
    val afterReportId: String,
    val latencyDelta: List<NumericDeltaResponse>,
    val qpsDelta: NumericDeltaResponse,
    val regressions: List<QueryLatencyChangeResponse>,
    val improvements: List<QueryLatencyChangeResponse>,
    val markdownPath: String
) {
    companion object {
        fun from(comparison: PerformanceComparison, markdownPath: String): PerformanceComparisonResponse =
            PerformanceComparisonResponse(
                beforeReportId = comparison.beforeReportId,
                afterReportId = comparison.afterReportId,
                latencyDelta = comparison.latencyDelta.map { NumericDeltaResponse.from(it) },
                qpsDelta = NumericDeltaResponse.from(comparison.qpsDelta),
                regressions = comparison.regressions.map { QueryLatencyChangeResponse.from(it) },
                improvements = comparison.improvements.map { QueryLatencyChangeResponse.from(it) },
                markdownPath = markdownPath
            )
    }
}

data class NumericDeltaResponse(
    val metric: String,
    val before: Double,
    val after: Double,
    val delta: Double
) {
    companion object {
        fun from(delta: NumericDelta): NumericDeltaResponse = NumericDeltaResponse(
            metric = delta.name,
            before = delta.before,
            after = delta.after,
            delta = delta.delta
        )
    }
}

data class QueryLatencyChangeResponse(
    val queryId: String,
    val queryText: String,
    val beforeP50: Long?,
    val afterP50: Long?,
    val p50Delta: Double,
    val beforeP95: Long?,
    val afterP95: Long?,
    val p95Delta: Double
) {
    companion object {
        fun from(change: QueryLatencyChange): QueryLatencyChangeResponse = QueryLatencyChangeResponse(
            queryId = change.queryId,
            queryText = change.queryText,
            beforeP50 = change.before?.latency?.p50Ms,
            afterP50 = change.after?.latency?.p50Ms,
            p50Delta = change.p50Delta,
            beforeP95 = change.before?.latency?.p95Ms,
            afterP95 = change.after?.latency?.p95Ms,
            p95Delta = change.p95Delta
        )
    }
}
