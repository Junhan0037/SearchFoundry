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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 평가 실행 결과를 실행 시점별 폴더에 Markdown/JSON 리포트로 떨어뜨리는 생성기.
 */
@Component
class EvaluationReportGenerator(
    private val objectMapper: ObjectMapper,
    @Value("\${eval.report.base-path:reports}") basePath: String = "reports"
) {
    private val logger = LoggerFactory.getLogger(EvaluationReportGenerator::class.java)
    private val reportBasePath: Path = Paths.get(basePath)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    /**
     * EvaluationRunResult를 받아 실행 시점별 폴더를 만들고 summary.md/metrics.json을 생성한다.
     * - worstQueriesCount: nDCG가 낮은 순으로 노출할 하위 쿼리 수
     */
    fun generate(runResult: EvaluationRunResult, worstQueriesCount: Int = 20): EvaluationReport {
        require(worstQueriesCount > 0) { "worstQueriesCount는 1 이상이어야 합니다." }
        val reportId = timestampFormatter.format(runResult.startedAt)
        val reportDir = reportBasePath.resolve(reportId)

        try {
            Files.createDirectories(reportDir)
            val worstQueries = pickWorstQueries(runResult, worstQueriesCount)

            val metricsJson = EvaluationReportJson(
                reportId = reportId,
                datasetId = runResult.datasetId,
                topK = runResult.topK,
                totalQueries = runResult.totalQueries,
                startedAt = runResult.startedAt.toString(),
                completedAt = runResult.completedAt.toString(),
                elapsedMs = runResult.elapsedMs,
                summary = runResult.metricsSummary,
                worstQueries = worstQueries
            )

            val metricsJsonPath = reportDir.resolve("metrics.json")
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metricsJsonPath.toFile(), metricsJson)

            val summaryPath = reportDir.resolve("summary.md")
            Files.writeString(summaryPath, renderSummaryMarkdown(metricsJson), StandardCharsets.UTF_8)

            logger.info(
                "평가 리포트 생성 완료(reportId={}, dir={})",
                reportId,
                reportDir.toAbsolutePath()
            )

            return EvaluationReport(
                reportId = reportId,
                directory = reportDir,
                metricsPath = metricsJsonPath,
                summaryPath = summaryPath,
                worstQueries = worstQueries
            )
        } catch (ex: Exception) {
            logger.error("평가 리포트 생성 실패(reportId={}): {}", reportId, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "평가 리포트 생성 중 오류가 발생했습니다.", ex.message)
        }
    }

    /**
     * nDCG가 낮은 순으로 Worst Queries를 추출한다.
     */
    private fun pickWorstQueries(runResult: EvaluationRunResult, worstQueriesCount: Int): List<WorstQueryEntry> =
        runResult.results
            .sortedWith(
                compareBy<EvaluatedQueryResult> { it.metrics.ndcgAtK }
                    .thenBy { it.metrics.recallAtK }
            )
            .take(worstQueriesCount)
            .map {
                WorstQueryEntry(
                    queryId = it.queryId,
                    intent = it.intent,
                    precisionAtK = it.metrics.precisionAtK,
                    recallAtK = it.metrics.recallAtK,
                    mrr = it.metrics.mrr,
                    ndcgAtK = it.metrics.ndcgAtK,
                    judgedHits = it.judgedHits,
                    relevantHits = it.relevantHits,
                    totalHits = it.totalHits
                )
            }

    /**
     * summary.md를 렌더링한다.
     */
    private fun renderSummaryMarkdown(json: EvaluationReportJson): String {
        val builder = StringBuilder()
        builder.appendLine("# 평가 리포트")
        builder.appendLine()
        builder.appendLine("- Report ID: ${json.reportId}")
        builder.appendLine("- Dataset: ${json.datasetId}")
        builder.appendLine("- TopK: ${json.topK}")
        builder.appendLine("- Total Queries: ${json.totalQueries}")
        builder.appendLine("- Started At (UTC): ${json.startedAt}")
        builder.appendLine("- Completed At (UTC): ${json.completedAt}")
        builder.appendLine("- Elapsed(ms): ${json.elapsedMs}")
        builder.appendLine()
        builder.appendLine("## 평균 지표(Mean)")
        builder.appendLine("|Metric|Value|")
        builder.appendLine("|---|---|")
        builder.appendLine("|Precision@K|${formatDouble(json.summary.meanPrecisionAtK)}|")
        builder.appendLine("|Recall@K|${formatDouble(json.summary.meanRecallAtK)}|")
        builder.appendLine("|MRR|${formatDouble(json.summary.meanMrr)}|")
        builder.appendLine("|nDCG@K|${formatDouble(json.summary.meanNdcgAtK)}|")

        builder.appendLine()
        builder.appendLine("## Worst Queries (Top ${json.worstQueries.size}, nDCG 오름차순)")
        if (json.worstQueries.isEmpty()) {
            builder.appendLine("- 집계할 쿼리가 없습니다.")
        } else {
            builder.appendLine("|QueryId|Intent|P@K|R@K|MRR|nDCG@K|Judged|Relevant|TotalHits|")
            builder.appendLine("|---|---|---|---|---|---|---|---|---|")
            json.worstQueries.forEach {
                builder.appendLine(
                    "|${it.queryId}|${it.intent}|${formatDouble(it.precisionAtK)}|" +
                        "${formatDouble(it.recallAtK)}|${formatDouble(it.mrr)}|" +
                        "${formatDouble(it.ndcgAtK)}|${it.judgedHits}|${it.relevantHits}|${it.totalHits}|"
                )
            }
        }

        return builder.toString()
    }

    /**
     * 소수점 4자리로 포맷해 Markdown/JSON 가독성을 높인다.
     */
    private fun formatDouble(value: Double): String =
        String.format(Locale.US, "%.4f", value)
}

/**
 * 디스크에 기록된 리포트 경로 및 Worst Queries 결과.
 */
data class EvaluationReport(
    val reportId: String,
    val directory: Path,
    val metricsPath: Path,
    val summaryPath: Path,
    val worstQueries: List<WorstQueryEntry>
)

/**
 * JSON 리포트 스키마.
 */
data class EvaluationReportJson(
    val reportId: String,
    val datasetId: String,
    val topK: Int,
    val totalQueries: Int,
    val startedAt: String,
    val completedAt: String,
    val elapsedMs: Long,
    val summary: EvaluationMetricsSummary,
    val worstQueries: List<WorstQueryEntry>
)

/**
 * nDCG 기준으로 하위 순위인 쿼리 엔트리.
 */
data class WorstQueryEntry(
    val queryId: String,
    val intent: String,
    val precisionAtK: Double,
    val recallAtK: Double,
    val mrr: Double,
    val ndcgAtK: Double,
    val judgedHits: Int,
    val relevantHits: Int,
    val totalHits: Long
)
