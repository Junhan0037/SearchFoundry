package com.searchfoundry.eval

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * 회귀 평가 파이프라인.
 * - baseline 리포트와 비교해 퇴행 여부를 신속히 판단하고 비교 리포트를 생성한다.
 */
@Service
class RegressionEvaluationService(
    private val evaluationRunner: EvaluationRunner,
    private val evaluationReportGenerator: EvaluationReportGenerator,
    private val evaluationReportComparator: EvaluationReportComparator,
    private val properties: RegressionEvaluationProperties
) {
    private val logger = LoggerFactory.getLogger(RegressionEvaluationService::class.java)

    /**
     * 회귀 평가를 실행하고 baseline 리포트와 비교 결과를 반환한다.
     * - 실패 시 예외로 전달해 호출 측에서 중단/롤백을 판단할 수 있게 한다.
     */
    fun run(request: RegressionEvaluationRequest = RegressionEvaluationRequest()): RegressionEvaluationResult {
        val resolved = request.resolve(properties)

        val runResult = evaluationRunner.run(
            datasetId = resolved.datasetId,
            topK = resolved.topK,
            targetIndex = resolved.targetIndex
        )

        val report = evaluationReportGenerator.generate(
            runResult = runResult,
            worstQueriesCount = resolved.worstQueries,
            reportIdPrefix = resolved.reportIdPrefix
        )

        val comparisonReport = evaluationReportComparator.compareAndWrite(
            beforeReportId = resolved.baselineReportId,
            afterReportId = report.reportId,
            topQueries = resolved.worstQueries
        )

        logger.info(
            "회귀 평가 완료(dataset={}, baselineReportId={}, reportId={}, comparison={})",
            resolved.datasetId,
            resolved.baselineReportId,
            report.reportId,
            comparisonReport.markdownPath.toAbsolutePath()
        )

        return RegressionEvaluationResult(
            request = resolved,
            runResult = runResult,
            report = report,
            comparison = comparisonReport.comparison,
            comparisonMarkdownPath = comparisonReport.markdownPath
        )
    }
}

/**
 * 회귀 평가 실행 요청(옵션 미지정 시 프로퍼티 기본값 사용).
 */
data class RegressionEvaluationRequest(
    val datasetId: String? = null,
    val baselineReportId: String? = null,
    val topK: Int? = null,
    val worstQueries: Int? = null,
    val targetIndex: String? = null,
    val reportIdPrefix: String? = null
) {
    fun resolve(properties: RegressionEvaluationProperties): ResolvedRegressionEvaluationRequest {
        val resolvedDataset = datasetId?.takeIf { it.isNotBlank() } ?: properties.datasetId
        val resolvedBaseline = baselineReportId?.takeIf { it.isNotBlank() } ?: properties.baselineReportId
        val resolvedTopK = (topK ?: properties.topK).coerceAtLeast(1)
        val resolvedWorst = (worstQueries ?: properties.worstQueries).coerceAtLeast(1)
        return ResolvedRegressionEvaluationRequest(
            datasetId = resolvedDataset,
            baselineReportId = resolvedBaseline,
            topK = resolvedTopK,
            worstQueries = resolvedWorst,
            targetIndex = targetIndex ?: properties.targetIndex,
            reportIdPrefix = reportIdPrefix ?: properties.reportIdPrefix
        )
    }
}

/**
 * 실제 실행에 사용되는 회귀 평가 요청.
 */
data class ResolvedRegressionEvaluationRequest(
    val datasetId: String,
    val baselineReportId: String,
    val topK: Int,
    val worstQueries: Int,
    val targetIndex: String?,
    val reportIdPrefix: String
)

/**
 * 회귀 평가 실행 및 비교 결과.
 */
data class RegressionEvaluationResult(
    val request: ResolvedRegressionEvaluationRequest,
    val runResult: EvaluationRunResult,
    val report: EvaluationReport,
    val comparison: EvaluationComparison,
    val comparisonMarkdownPath: Path
)
