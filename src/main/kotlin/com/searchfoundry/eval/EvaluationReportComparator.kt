package com.searchfoundry.eval

import com.fasterxml.jackson.databind.ObjectMapper
import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * 평가 리포트(전/후) metrics.json을 비교해 메트릭 변화와 worst query 개선 여부를 자동으로 산출하는 컴포넌트.
 */
@Component
class EvaluationReportComparator(
    private val objectMapper: ObjectMapper,
    @Value("\${eval.report.base-path:reports}") basePath: String = "reports"
) {
    private val logger = LoggerFactory.getLogger(EvaluationReportComparator::class.java)
    private val reportBasePath: Path = Paths.get(basePath)
    private val comparisonDir: Path = reportBasePath.resolve("comparisons")

    /**
     * 전/후 리포트를 비교해 결과 객체와 마크다운 요약 파일을 생성한다.
     */
    fun compareAndWrite(
        beforeReportId: String,
        afterReportId: String,
        topQueries: Int = 20
    ): EvaluationComparisonReport {
        require(topQueries > 0) { "topQueries는 1 이상이어야 합니다." }

        val comparison = compare(beforeReportId, afterReportId, topQueries)
        val markdown = renderMarkdown(comparison, topQueries)
        val outputPath = writeMarkdown(afterReportId, beforeReportId, markdown)

        return EvaluationComparisonReport(
            comparison = comparison,
            markdownPath = outputPath
        )
    }

    /**
     * 전/후 리포트 metrics.json을 로드해 메트릭 델타와 worst query 이동을 계산한다.
     */
    fun compare(beforeReportId: String, afterReportId: String, topQueries: Int = 20): EvaluationComparison {
        require(topQueries > 0) { "topQueries는 1 이상이어야 합니다." }

        val before = loadMetrics(beforeReportId)
        val after = loadMetrics(afterReportId)

        val metricsDelta = listOf(
            MetricDelta("Precision@K", before.summary.meanPrecisionAtK, after.summary.meanPrecisionAtK),
            MetricDelta("Recall@K", before.summary.meanRecallAtK, after.summary.meanRecallAtK),
            MetricDelta("MRR", before.summary.meanMrr, after.summary.meanMrr),
            MetricDelta("nDCG@K", before.summary.meanNdcgAtK, after.summary.meanNdcgAtK)
        )

        val worstQueryChanges = buildWorstQueryChanges(before, after)
        val improved = worstQueryChanges.filter { it.status == WorstQueryChangeStatus.IMPROVED || it.status == WorstQueryChangeStatus.REMOVED_FROM_WORST }
            .sortedByDescending { it.ndcgDelta }
            .take(topQueries)
        val regressed = worstQueryChanges.filter { it.status == WorstQueryChangeStatus.REGRESSED || it.status == WorstQueryChangeStatus.NEW_IN_WORST }
            .sortedBy { it.ndcgDelta }
            .take(topQueries)

        return EvaluationComparison(
            beforeReportId = beforeReportId,
            afterReportId = afterReportId,
            metricsDelta = metricsDelta,
            improvedQueries = improved,
            regressedQueries = regressed
        )
    }

    /**
     * worstQueries 리스트를 기반으로 개선/퇴행/신규/제외된 쿼리를 식별한다.
     */
    private fun buildWorstQueryChanges(
        before: EvaluationReportJson,
        after: EvaluationReportJson
    ): List<WorstQueryChange> {
        val beforeMap = before.worstQueries.associateBy { it.queryId }
        val afterMap = after.worstQueries.associateBy { it.queryId }
        val allQueryIds = beforeMap.keys + afterMap.keys

        return allQueryIds.map { queryId ->
            val beforeEntry = beforeMap[queryId]
            val afterEntry = afterMap[queryId]
            val delta = when {
                beforeEntry != null && afterEntry != null -> afterEntry.ndcgAtK - beforeEntry.ndcgAtK
                beforeEntry != null -> 1.0 - beforeEntry.ndcgAtK // worst 목록에서 빠졌으므로 개선으로 간주.
                else -> -(afterEntry?.ndcgAtK ?: 0.0) // 새로 worst에 진입했으므로 퇴행으로 간주.
            }
            val status = when {
                beforeEntry != null && afterEntry != null -> when {
                    delta > 0 -> WorstQueryChangeStatus.IMPROVED
                    delta < 0 -> WorstQueryChangeStatus.REGRESSED
                    else -> WorstQueryChangeStatus.UNCHANGED
                }
                beforeEntry != null -> WorstQueryChangeStatus.REMOVED_FROM_WORST
                else -> WorstQueryChangeStatus.NEW_IN_WORST
            }

            WorstQueryChange(
                queryId = queryId,
                intent = afterEntry?.intent ?: beforeEntry?.intent.orEmpty(),
                ndcgDelta = delta,
                precisionDelta = afterEntry?.let { afterVal ->
                    beforeEntry?.let { afterVal.precisionAtK - it.precisionAtK }
                },
                recallDelta = afterEntry?.let { afterVal ->
                    beforeEntry?.let { afterVal.recallAtK - it.recallAtK }
                },
                mrrDelta = afterEntry?.let { afterVal ->
                    beforeEntry?.let { afterVal.mrr - it.mrr }
                },
                before = beforeEntry,
                after = afterEntry,
                status = status
            )
        }
    }

    /**
     * 비교 결과를 마크다운으로 렌더링한다.
     */
    private fun renderMarkdown(comparison: EvaluationComparison, topQueries: Int): String {
        val builder = StringBuilder()
        builder.appendLine("# Report Comparison")
        builder.appendLine()
        builder.appendLine("- Before: ${comparison.beforeReportId}")
        builder.appendLine("- After: ${comparison.afterReportId}")
        builder.appendLine()
        builder.appendLine("## Metrics Δ")
        builder.appendLine("|Metric|Before|After|Delta|")
        builder.appendLine("|---|---|---|---|")
        comparison.metricsDelta.forEach { delta ->
            builder.appendLine("|${delta.name}|${formatDouble(delta.before)}|${formatDouble(delta.after)}|${formatDouble(delta.delta)}|")
        }

        builder.appendLine()
        builder.appendLine("## Worst Queries 개선 Top $topQueries")
        if (comparison.improvedQueries.isEmpty()) {
            builder.appendLine("- 개선된 worst query가 없습니다.")
        } else {
            builder.appendLine("|QueryId|Intent|Before nDCG|After nDCG|Δ nDCG|Status|")
            builder.appendLine("|---|---|---|---|---|---|")
            comparison.improvedQueries.forEach { change ->
                builder.appendLine(
                    "|${change.queryId}|${change.intent}|${formatDouble(change.before?.ndcgAtK)}|" +
                        "${formatDouble(change.after?.ndcgAtK)}|${formatDouble(change.ndcgDelta)}|${change.status}|"
                )
            }
        }

        builder.appendLine()
        builder.appendLine("## Worst Queries 퇴행/신규 Top $topQueries")
        if (comparison.regressedQueries.isEmpty()) {
            builder.appendLine("- 퇴행하거나 신규로 유입된 worst query가 없습니다.")
        } else {
            builder.appendLine("|QueryId|Intent|Before nDCG|After nDCG|Δ nDCG|Status|")
            builder.appendLine("|---|---|---|---|---|---|")
            comparison.regressedQueries.forEach { change ->
                builder.appendLine(
                    "|${change.queryId}|${change.intent}|${formatDouble(change.before?.ndcgAtK)}|" +
                        "${formatDouble(change.after?.ndcgAtK)}|${formatDouble(change.ndcgDelta)}|${change.status}|"
                )
            }
        }

        return builder.toString()
    }

    /**
     * 비교 결과 마크다운을 디스크에 저장한다.
     */
    private fun writeMarkdown(afterReportId: String, beforeReportId: String, content: String): Path {
        try {
            Files.createDirectories(comparisonDir)
            val fileName = "${afterReportId}_vs_${beforeReportId}.md"
            val path = comparisonDir.resolve(fileName)
            Files.writeString(path, content, StandardCharsets.UTF_8)
            logger.info("평가 리포트 비교 마크다운을 생성했습니다(path={})", path.toAbsolutePath())
            return path
        } catch (ex: Exception) {
            logger.error("비교 리포트 생성 실패(after={}, before={}): {}", afterReportId, beforeReportId, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "비교 리포트 생성 중 오류가 발생했습니다.", ex.message)
        }
    }

    /**
     * 리포트 디렉터리에서 metrics.json을 로드한다.
     */
    private fun loadMetrics(reportId: String): EvaluationReportJson {
        val metricsPath = reportBasePath.resolve(reportId).resolve("metrics.json")
        if (!Files.exists(metricsPath)) {
            throw AppException(ErrorCode.NOT_FOUND, "metrics.json을 찾을 수 없습니다. reportId=$reportId", null)
        }

        return try {
            objectMapper.readValue(metricsPath.toFile(), EvaluationReportJson::class.java)
        } catch (ex: Exception) {
            logger.error("metrics.json 파싱 실패(reportId={}): {}", reportId, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "metrics.json 파싱에 실패했습니다.", ex.message)
        }
    }

    private fun formatDouble(value: Double?): String =
        value?.let { String.format(Locale.US, "%.4f", it) } ?: "-"
}

/**
 * 메트릭 단일 항목 델타.
 */
data class MetricDelta(
    val name: String,
    val before: Double,
    val after: Double
) {
    val delta: Double = after - before
}

/**
 * worst query 이동 정보와 ndcg/precision/mrr 변화량.
 */
data class WorstQueryChange(
    val queryId: String,
    val intent: String,
    val ndcgDelta: Double,
    val precisionDelta: Double?,
    val recallDelta: Double?,
    val mrrDelta: Double?,
    val before: WorstQueryEntry?,
    val after: WorstQueryEntry?,
    val status: WorstQueryChangeStatus
)

enum class WorstQueryChangeStatus {
    IMPROVED,
    REGRESSED,
    UNCHANGED,
    REMOVED_FROM_WORST,
    NEW_IN_WORST
}

/**
 * 리포트 비교 결과와 생성된 마크다운 경로.
 */
data class EvaluationComparisonReport(
    val comparison: EvaluationComparison,
    val markdownPath: Path
)

/**
 * 전/후 리포트 비교 요약.
 */
data class EvaluationComparison(
    val beforeReportId: String,
    val afterReportId: String,
    val metricsDelta: List<MetricDelta>,
    val improvedQueries: List<WorstQueryChange>,
    val regressedQueries: List<WorstQueryChange>
)
