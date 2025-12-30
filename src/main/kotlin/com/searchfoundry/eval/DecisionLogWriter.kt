package com.searchfoundry.eval

import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 평가 리포트 비교 결과를 기반으로 Decision Log를 자동으로 남기는 유틸리티.
 * - before/after 리포트 ID와 가설/선택 이유를 함께 남겨 재현성을 보장한다.
 */
@Component
class DecisionLogWriter(
    private val evaluationReportComparator: EvaluationReportComparator,
    @Value("\${eval.decision-log.path:docs/decision/DecisionLog.md}") decisionLogPath: String = "docs/decision/DecisionLog.md"
) {
    private val logger = LoggerFactory.getLogger(DecisionLogWriter::class.java)
    private val logPath: Path = Paths.get(decisionLogPath)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /**
     * 전/후 리포트 비교 + 실험 메타데이터를 Decision Log에 추가한다.
     */
    fun appendEntry(request: DecisionLogRequest): DecisionLogEntryResult {
        val comparison = evaluationReportComparator.compare(request.beforeReportId, request.afterReportId, request.topQueries)
        val entry = renderEntry(request, comparison)

        try {
            val parent = logPath.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            if (!Files.exists(logPath)) {
                // 최초 생성 시 헤더를 추가해 문서 일관성을 유지한다.
                Files.writeString(logPath, "# Decision Log\n\n", StandardCharsets.UTF_8)
            }
            Files.writeString(logPath, entry, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)
            logger.info("Decision Log를 업데이트했습니다(path={}, experiment={})", logPath.toAbsolutePath(), request.experimentName)
        } catch (ex: Exception) {
            logger.error("Decision Log 작성 실패(experiment={}, path={}): {}", request.experimentName, logPath, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "Decision Log 작성 중 오류가 발생했습니다.", ex.message)
        }

        return DecisionLogEntryResult(
            path = logPath,
            comparison = comparison
        )
    }

    /**
     * Decision Log 항목 마크다운을 생성한다.
     */
    private fun renderEntry(request: DecisionLogRequest, comparison: EvaluationComparison): String {
        val timestamp = timestampFormatter.format(Instant.now())
        val builder = StringBuilder()
        builder.appendLine("## ${request.experimentName} (${timestamp})")
        builder.appendLine("- 유형: ${request.experimentType}")
        builder.appendLine("- 데이터셋: ${request.datasetId}")
        builder.appendLine("- 보고서: before=${comparison.beforeReportId}, after=${comparison.afterReportId}")
        builder.appendLine("- 가설: ${request.hypothesis}")
        builder.appendLine("- 선택 근거/노트: ${request.notes.ifBlank { "N/A" }}")
        builder.appendLine()
        builder.appendLine("### 메트릭 변화")
        builder.appendLine("|Metric|Before|After|Δ|")
        builder.appendLine("|---|---|---|---|")
        comparison.metricsDelta.forEach { delta ->
            builder.appendLine("|${delta.name}|${formatDouble(delta.before)}|${formatDouble(delta.after)}|${formatDouble(delta.delta)}|")
        }

        builder.appendLine()
        builder.appendLine("### Worst Query 개선 Top ${request.topQueries}")
        if (comparison.improvedQueries.isEmpty()) {
            builder.appendLine("- 개선된 worst query 없음")
        } else {
            builder.appendLine("|QueryId|Intent|Δ nDCG|Status|")
            builder.appendLine("|---|---|---|---|")
            comparison.improvedQueries.forEach { change ->
                builder.appendLine("|${change.queryId}|${change.intent}|${formatDouble(change.ndcgDelta)}|${change.status}|")
            }
        }

        builder.appendLine()
        builder.appendLine("### Worst Query 퇴행/신규 Top ${request.topQueries}")
        if (comparison.regressedQueries.isEmpty()) {
            builder.appendLine("- 퇴행/신규 worst query 없음")
        } else {
            builder.appendLine("|QueryId|Intent|Δ nDCG|Status|")
            builder.appendLine("|---|---|---|---|")
            comparison.regressedQueries.forEach { change ->
                builder.appendLine("|${change.queryId}|${change.intent}|${formatDouble(change.ndcgDelta)}|${change.status}|")
            }
        }

        builder.appendLine()
        return builder.toString()
    }

    private fun formatDouble(value: Double): String = String.format(Locale.US, "%.4f", value)
}

/**
 * Decision Log에 남길 요청 파라미터.
 */
data class DecisionLogRequest(
    val experimentName: String,
    val experimentType: String,
    val datasetId: String,
    val beforeReportId: String,
    val afterReportId: String,
    val hypothesis: String,
    val notes: String = "",
    val topQueries: Int = 5
) {
    init {
        require(experimentName.isNotBlank()) { "experimentName은 비어 있을 수 없습니다." }
        require(experimentType.isNotBlank()) { "experimentType은 비어 있을 수 없습니다." }
        require(datasetId.isNotBlank()) { "datasetId는 비어 있을 수 없습니다." }
        require(beforeReportId.isNotBlank()) { "beforeReportId는 비어 있을 수 없습니다." }
        require(afterReportId.isNotBlank()) { "afterReportId는 비어 있을 수 없습니다." }
        require(hypothesis.isNotBlank()) { "hypothesis는 비어 있을 수 없습니다." }
        require(topQueries > 0) { "topQueries는 1 이상이어야 합니다." }
    }
}

/**
 * Decision Log 업데이트 결과.
 */
data class DecisionLogEntryResult(
    val path: Path,
    val comparison: EvaluationComparison
)
