package com.searchfoundry.core.observability

import com.fasterxml.jackson.databind.ObjectMapper
import com.searchfoundry.support.config.PerformanceBenchmarkProperties
import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.Locale

/**
 * 성능 벤치마크 실행 결과를 JSON/Markdown 리포트로 저장하는 컴포넌트.
 * - metrics.json은 비교기를 위해 안정된 스키마로 기록한다.
 * - summary.md는 빠르게 수치/P50/P95/QPS를 확인하기 위한 요약본이다.
 */
@Component
class PerformanceReportWriter(
    private val objectMapper: ObjectMapper,
    performanceBenchmarkProperties: PerformanceBenchmarkProperties
) {
    private val logger = LoggerFactory.getLogger(PerformanceReportWriter::class.java)
    private val baseDir: Path = Paths.get(performanceBenchmarkProperties.reportBasePath)

    /**
     * 벤치마크 결과를 디스크에 기록하고 경로 정보를 반환한다.
     */
    fun write(result: PerformanceBenchmarkResult): PerformanceReport {
        try {
            val runDir = baseDir.resolve(result.runId)
            Files.createDirectories(runDir)

            val jsonPayload = PerformanceReportJson.from(result)
            val metricsPath = runDir.resolve("metrics.json")
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metricsPath.toFile(), jsonPayload)

            val summaryPath = runDir.resolve("summary.md")
            Files.writeString(summaryPath, renderSummary(jsonPayload), StandardCharsets.UTF_8)

            logger.info("성능 리포트 생성 완료(runId={}, dir={})", result.runId, runDir.toAbsolutePath())
            return PerformanceReport(
                reportId = result.runId,
                datasetId = result.datasetId,
                targetIndex = result.targetIndex,
                metricsPath = metricsPath,
                summaryPath = summaryPath,
                startedAt = result.startedAt,
                completedAt = result.completedAt,
                totalQueries = result.totalQueries,
                totalSamples = result.totalSamples
            )
        } catch (ex: Exception) {
            logger.error("성능 리포트 생성 실패(runId={}): {}", result.runId, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "성능 리포트 생성 중 오류가 발생했습니다.", ex.message)
        }
    }

    /**
     * Markdown 요약본 렌더링.
     */
    private fun renderSummary(json: PerformanceReportJson): String {
        val builder = StringBuilder()
        val topSlow = json.queries.sortedByDescending { it.latency.p95Ms }.take(5)

        builder.appendLine("# 검색 성능 벤치마크")
        builder.appendLine("- Report ID: ${json.reportId}")
        builder.appendLine("- Dataset: ${json.datasetId}")
        builder.appendLine("- Target Index/Alias: ${json.targetIndex}")
        builder.appendLine("- TopK: ${json.topK}")
        builder.appendLine("- Iterations: ${json.iterations} (warmup=${json.warmups})")
        builder.appendLine("- Total Queries: ${json.totalQueries}")
        builder.appendLine("- Total Samples: ${json.totalSamples}")
        builder.appendLine("- Started At (UTC): ${json.startedAt}")
        builder.appendLine("- Completed At (UTC): ${json.completedAt}")
        builder.appendLine("- Elapsed(ms): ${json.elapsedMs}")
        builder.appendLine("- QPS (approx): ${formatDouble(json.qps)}")

        builder.appendLine()
        builder.appendLine("## Latency Summary")
        builder.appendLine("|Stat|Value(ms)|")
        builder.appendLine("|---|---|")
        builder.appendLine("|P50|${json.latency.p50Ms}|")
        builder.appendLine("|P95|${json.latency.p95Ms}|")
        builder.appendLine("|Avg|${formatDouble(json.latency.avgMs)}|")
        builder.appendLine("|Min|${json.latency.minMs}|")
        builder.appendLine("|Max|${json.latency.maxMs}|")

        builder.appendLine()
        builder.appendLine("## Top Slow Queries (by P95)")
        if (topSlow.isEmpty()) {
            builder.appendLine("- 측정된 쿼리가 없습니다.")
        } else {
            builder.appendLine("|QueryId|P50|P95|Avg|Max|Query|")
            builder.appendLine("|---|---|---|---|---|---|")
            topSlow.forEach { entry ->
                builder.appendLine(
                    "|${entry.queryId}|${entry.latency.p50Ms}|${entry.latency.p95Ms}|${formatDouble(entry.latency.avgMs)}|" +
                        "${entry.latency.maxMs}|${entry.queryText}|"
                )
            }
        }

        return builder.toString()
    }

    private fun formatDouble(value: Double): String = String.format(Locale.US, "%.3f", value)
}

/**
 * 성능 리포트 파일 경로 및 메타데이터.
 */
data class PerformanceReport(
    val reportId: String,
    val datasetId: String,
    val targetIndex: String,
    val metricsPath: Path,
    val summaryPath: Path,
    val startedAt: Instant,
    val completedAt: Instant,
    val totalQueries: Int,
    val totalSamples: Int
)

/**
 * metrics.json 스키마. 비교/재활용을 위해 파생 클래스를 분리했다.
 */
data class PerformanceReportJson(
    val reportId: String,
    val datasetId: String,
    val targetIndex: String,
    val topK: Int,
    val iterations: Int,
    val warmups: Int,
    val totalQueries: Int,
    val totalSamples: Int,
    val startedAt: String,
    val completedAt: String,
    val elapsedMs: Long,
    val qps: Double,
    val latency: LatencyStatsJson,
    val queries: List<QueryLatencyJson>
) {
    companion object {
        fun from(result: PerformanceBenchmarkResult): PerformanceReportJson = PerformanceReportJson(
            reportId = result.runId,
            datasetId = result.datasetId,
            targetIndex = result.targetIndex,
            topK = result.topK,
            iterations = result.iterations,
            warmups = result.warmups,
            totalQueries = result.totalQueries,
            totalSamples = result.totalSamples,
            startedAt = result.startedAt.toString(),
            completedAt = result.completedAt.toString(),
            elapsedMs = result.elapsedMs,
            qps = result.qps,
            latency = LatencyStatsJson.from(result.latency),
            queries = result.perQuery.map { QueryLatencyJson.from(it) }
        )
    }
}

/**
 * 분포 요약 JSON 스키마.
 */
data class LatencyStatsJson(
    val minMs: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long,
    val avgMs: Double
) {
    companion object {
        fun from(latency: LatencyStats): LatencyStatsJson = LatencyStatsJson(
            minMs = latency.minMs,
            p50Ms = latency.p50Ms,
            p95Ms = latency.p95Ms,
            maxMs = latency.maxMs,
            avgMs = latency.avgMs
        )
    }
}

/**
 * 쿼리별 성능 요약 JSON 스키마.
 */
data class QueryLatencyJson(
    val queryId: String,
    val queryText: String,
    val samples: List<Long>,
    val latency: LatencyStatsJson
) {
    companion object {
        fun from(stats: QueryLatencyStats): QueryLatencyJson = QueryLatencyJson(
            queryId = stats.queryId,
            queryText = stats.queryText,
            samples = stats.samples.map { it.tookMs },
            latency = LatencyStatsJson.from(stats.latency)
        )
    }
}
