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
import java.util.Locale

/**
 * 성능 벤치마크 리포트(전/후) metrics.json을 비교해 Δ(P50/P95/QPS)와 쿼리별 변화를 도출한다.
 */
@Component
class PerformanceReportComparator(
    private val objectMapper: ObjectMapper,
    performanceBenchmarkProperties: PerformanceBenchmarkProperties
) {
    private val logger = LoggerFactory.getLogger(PerformanceReportComparator::class.java)
    private val basePath: Path = Paths.get(performanceBenchmarkProperties.reportBasePath)
    private val comparisonDir: Path = basePath.resolve("comparisons")

    /**
     * 비교 결과를 계산하고 마크다운 리포트를 생성한다.
     */
    fun compareAndWrite(
        beforeReportId: String,
        afterReportId: String,
        topQueries: Int = 5
    ): PerformanceComparisonReport {
        val comparison = compare(beforeReportId, afterReportId, topQueries)
        val markdown = renderMarkdown(comparison, topQueries)
        val path = writeMarkdown(afterReportId, beforeReportId, markdown)
        return PerformanceComparisonReport(comparison = comparison, markdownPath = path)
    }

    /**
     * metrics.json을 로드해 전/후 분포/쿼리 변화를 계산한다.
     */
    fun compare(
        beforeReportId: String,
        afterReportId: String,
        topQueries: Int = 5
    ): PerformanceComparison {
        require(topQueries > 0) { "topQueries는 1 이상이어야 합니다." }

        val before = loadReport(beforeReportId)
        val after = loadReport(afterReportId)

        val latencyDelta = listOf(
            NumericDelta("P50", before.latency.p50Ms.toDouble(), after.latency.p50Ms.toDouble()),
            NumericDelta("P95", before.latency.p95Ms.toDouble(), after.latency.p95Ms.toDouble()),
            NumericDelta("Avg", before.latency.avgMs, after.latency.avgMs),
            NumericDelta("Max", before.latency.maxMs.toDouble(), after.latency.maxMs.toDouble())
        )
        val qpsDelta = NumericDelta("QPS", before.qps, after.qps)

        val changes = buildQueryChanges(before, after)
        val regressions = changes.filter { it.p95Delta > 0 }
            .sortedByDescending { it.p95Delta }
            .take(topQueries)
        val improvements = changes.filter { it.p95Delta < 0 }
            .sortedBy { it.p95Delta }
            .take(topQueries)

        return PerformanceComparison(
            beforeReportId = beforeReportId,
            afterReportId = afterReportId,
            latencyDelta = latencyDelta,
            qpsDelta = qpsDelta,
            regressions = regressions,
            improvements = improvements
        )
    }

    /**
     * 쿼리별 P50/P95 변화를 계산한다.
     */
    private fun buildQueryChanges(
        before: PerformanceReportJson,
        after: PerformanceReportJson
    ): List<QueryLatencyChange> {
        val beforeMap = before.queries.associateBy { it.queryId }
        val afterMap = after.queries.associateBy { it.queryId }
        val allQueryIds = beforeMap.keys + afterMap.keys

        return allQueryIds.map { queryId ->
            val beforeEntry = beforeMap[queryId]
            val afterEntry = afterMap[queryId]
            val queryText = afterEntry?.queryText ?: beforeEntry?.queryText.orEmpty()
            val beforeP95 = beforeEntry?.latency?.p95Ms?.toDouble() ?: 0.0
            val afterP95 = afterEntry?.latency?.p95Ms?.toDouble() ?: 0.0
            val beforeP50 = beforeEntry?.latency?.p50Ms?.toDouble() ?: 0.0
            val afterP50 = afterEntry?.latency?.p50Ms?.toDouble() ?: 0.0

            QueryLatencyChange(
                queryId = queryId,
                queryText = queryText,
                p50Delta = afterP50 - beforeP50,
                p95Delta = afterP95 - beforeP95,
                before = beforeEntry,
                after = afterEntry
            )
        }
    }

    /**
     * 비교 결과를 Markdown으로 렌더링한다.
     */
    private fun renderMarkdown(comparison: PerformanceComparison, topQueries: Int): String {
        val builder = StringBuilder()
        builder.appendLine("# Performance Comparison")
        builder.appendLine()
        builder.appendLine("- Before: ${comparison.beforeReportId}")
        builder.appendLine("- After: ${comparison.afterReportId}")
        builder.appendLine()
        builder.appendLine("## Latency Δ")
        builder.appendLine("|Metric|Before|After|Δ|")
        builder.appendLine("|---|---|---|---|")
        comparison.latencyDelta.forEach { delta ->
            builder.appendLine("|${delta.name}|${formatDouble(delta.before)}|${formatDouble(delta.after)}|${formatDouble(delta.delta)}|")
        }

        builder.appendLine()
        builder.appendLine("## QPS Δ")
        builder.appendLine("|Before|After|Δ|")
        builder.appendLine("|---|---|---|")
        builder.appendLine("|${formatDouble(comparison.qpsDelta.before)}|${formatDouble(comparison.qpsDelta.after)}|${formatDouble(comparison.qpsDelta.delta)}|")

        builder.appendLine()
        builder.appendLine("## Regressions (Top $topQueries, by P95 Δ)")
        if (comparison.regressions.isEmpty()) {
            builder.appendLine("- P95 악화 쿼리가 없습니다.")
        } else {
            builder.appendLine("|QueryId|Before P95|After P95|Δ P95|Query|")
            builder.appendLine("|---|---|---|---|---|")
            comparison.regressions.forEach { change ->
                builder.appendLine(
                    "|${change.queryId}|${formatLong(change.before?.latency?.p95Ms)}|" +
                        "${formatLong(change.after?.latency?.p95Ms)}|${formatDouble(change.p95Delta)}|" +
                        "${change.queryText}|"
                )
            }
        }

        builder.appendLine()
        builder.appendLine("## Improvements (Top $topQueries, by P95 Δ)")
        if (comparison.improvements.isEmpty()) {
            builder.appendLine("- 개선된 쿼리가 없습니다.")
        } else {
            builder.appendLine("|QueryId|Before P95|After P95|Δ P95|Query|")
            builder.appendLine("|---|---|---|---|---|")
            comparison.improvements.forEach { change ->
                builder.appendLine(
                    "|${change.queryId}|${formatLong(change.before?.latency?.p95Ms)}|" +
                        "${formatLong(change.after?.latency?.p95Ms)}|${formatDouble(change.p95Delta)}|" +
                        "${change.queryText}|"
                )
            }
        }

        return builder.toString()
    }

    /**
     * 비교 결과 마크다운을 파일로 기록한다.
     */
    private fun writeMarkdown(afterReportId: String, beforeReportId: String, content: String): Path {
        try {
            Files.createDirectories(comparisonDir)
            val path = comparisonDir.resolve("${afterReportId}_vs_${beforeReportId}.md")
            Files.writeString(path, content, StandardCharsets.UTF_8)
            logger.info("성능 비교 리포트를 생성했습니다(path={})", path.toAbsolutePath())
            return path
        } catch (ex: Exception) {
            logger.error("성능 비교 리포트 생성 실패(after={}, before={}): {}", afterReportId, beforeReportId, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "성능 비교 리포트 생성 중 오류가 발생했습니다.", ex.message)
        }
    }

    /**
     * reportId를 기반으로 metrics.json을 로드한다.
     */
    private fun loadReport(reportId: String): PerformanceReportJson {
        val metricsPath = basePath.resolve(reportId).resolve("metrics.json")
        if (!Files.exists(metricsPath)) {
            throw AppException(
                ErrorCode.NOT_FOUND,
                "성능 리포트 metrics.json을 찾을 수 없습니다.",
                metricsPath.toAbsolutePath().toString()
            )
        }

        return try {
            objectMapper.readValue(metricsPath.toFile(), PerformanceReportJson::class.java)
        } catch (ex: Exception) {
            logger.error("성능 리포트 metrics.json 파싱 실패(reportId={}): {}", reportId, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "성능 리포트 로드 중 오류가 발생했습니다.", ex.message)
        }
    }

    private fun formatDouble(value: Double): String = String.format(Locale.US, "%.3f", value)
    private fun formatLong(value: Long?): String = value?.toString() ?: "-"
}

/**
 * 비교 결과 및 생성된 마크다운 경로.
 */
data class PerformanceComparisonReport(
    val comparison: PerformanceComparison,
    val markdownPath: Path
)

/**
 * 전/후 리포트 비교 요약.
 */
data class PerformanceComparison(
    val beforeReportId: String,
    val afterReportId: String,
    val latencyDelta: List<NumericDelta>,
    val qpsDelta: NumericDelta,
    val regressions: List<QueryLatencyChange>,
    val improvements: List<QueryLatencyChange>
)

/**
 * 숫자 지표 델타.
 */
data class NumericDelta(
    val name: String,
    val before: Double,
    val after: Double
) {
    val delta: Double = after - before
}

/**
 * 쿼리별 P50/P95 변화량.
 */
data class QueryLatencyChange(
    val queryId: String,
    val queryText: String,
    val p50Delta: Double,
    val p95Delta: Double,
    val before: QueryLatencyJson?,
    val after: QueryLatencyJson?
)
