package com.searchfoundry.api.admin

import com.searchfoundry.eval.EvaluatedHit
import com.searchfoundry.eval.EvaluatedQueryResult
import com.searchfoundry.eval.EvaluationReport
import com.searchfoundry.eval.EvaluationReportGenerator
import com.searchfoundry.eval.EvaluationRunResult
import com.searchfoundry.eval.EvaluationRunner
import com.searchfoundry.eval.EvaluationMetricsSummary
import com.searchfoundry.eval.QueryMetrics
import com.searchfoundry.eval.WorstQueryEntry
import com.searchfoundry.eval.WorstQueryChange
import com.searchfoundry.eval.EvaluationComparison
import com.searchfoundry.eval.MetricDelta
import com.searchfoundry.eval.RegressionEvaluationRequest
import com.searchfoundry.eval.RegressionEvaluationResult
import com.searchfoundry.eval.RegressionEvaluationService
import com.searchfoundry.eval.experiment.AnalyzerExperimentRequest
import com.searchfoundry.eval.experiment.AnalyzerExperimentResult
import com.searchfoundry.eval.experiment.AnalyzerExperimentRunner
import com.searchfoundry.eval.experiment.AnalyzerExperimentStatus
import com.searchfoundry.eval.experiment.AnalyzerExperimentSuiteResult
import com.searchfoundry.index.BulkIndexResult
import com.searchfoundry.support.api.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID
import java.nio.file.Path

/**
 * 평가 러너를 트리거하는 Admin API.
 */
@RestController
@RequestMapping("/admin/eval")
@Validated
class EvalAdminController(
    private val evaluationRunner: EvaluationRunner,
    private val evaluationReportGenerator: EvaluationReportGenerator,
    private val analyzerExperimentRunner: AnalyzerExperimentRunner,
    private val regressionEvaluationService: RegressionEvaluationService
) {

    /**
     * QuerySet/JudgementSet을 읽어 topK 검색 결과와 매칭한다.
     * - 예: POST /admin/eval/run?datasetId=baseline&topK=10
     */
    @PostMapping("/run")
    fun runEvaluation(
        @RequestParam @NotBlank(message = "datasetId는 필수입니다.") datasetId: String,
        @RequestParam(defaultValue = "10") @Min(value = 1, message = "topK는 1 이상이어야 합니다.") @Max(
            value = 100,
            message = "topK는 100 이하로 요청해주세요."
        ) topK: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(
            value = 200,
            message = "worstQueries는 1~200 사이로 요청해주세요."
        ) worstQueries: Int,
        @RequestParam(defaultValue = "true") generateReport: Boolean
    ): ApiResponse<EvaluationRunResponse> {
        val result = evaluationRunner.run(datasetId.trim(), topK)
        val report = if (generateReport) evaluationReportGenerator.generate(result, worstQueries) else null
        return ApiResponse.success(EvaluationRunResponse.from(result, report))
    }

    /**
     * baseline 리포트와 비교하는 회귀 평가를 실행한다.
     * - 요청을 비우면 기본 프로퍼티(eval.regression.*)를 사용한다.
     */
    @PostMapping("/regression")
    fun runRegression(
        @RequestBody(required = false) @Valid request: RegressionEvaluationRequestDto?
    ): ApiResponse<RegressionEvaluationResponse> {
        val result = regressionEvaluationService.run(request?.toDomain() ?: RegressionEvaluationRequest())
        return ApiResponse.success(RegressionEvaluationResponse.from(result))
    }

    /**
     * nori 분석기 설정 조합(분해 모드/사용자 사전/동의어)을 실험하고 요약 지표를 반환한다.
     */
    @PostMapping("/experiments/analyzer")
    fun runAnalyzerExperiments(
        @RequestBody @Valid request: AnalyzerExperimentRequestDto
    ): ApiResponse<AnalyzerExperimentSuiteResponse> {
        val suiteResult = analyzerExperimentRunner.run(request.toDomain())
        return ApiResponse.success(AnalyzerExperimentSuiteResponse.from(suiteResult))
    }
}

data class EvaluationRunResponse(
    val datasetId: String,
    val topK: Int,
    val totalQueries: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val elapsedMs: Long,
    val targetIndex: String?,
    val metrics: EvaluationMetricsSummaryResponse,
    val report: EvaluationReportResponse?,
    val results: List<EvaluatedQueryResponse>
) {
    companion object {
        fun from(result: EvaluationRunResult, report: EvaluationReport?): EvaluationRunResponse = EvaluationRunResponse(
            datasetId = result.datasetId,
            topK = result.topK,
            totalQueries = result.totalQueries,
            startedAt = result.startedAt,
            completedAt = result.completedAt,
            elapsedMs = result.elapsedMs,
            targetIndex = result.targetIndex,
            metrics = EvaluationMetricsSummaryResponse.from(result.metricsSummary),
            report = EvaluationReportResponse.from(report),
            results = result.results.map { EvaluatedQueryResponse.from(it) }
        )
    }
}

data class RegressionEvaluationRequestDto(
    val datasetId: String? = null,
    val baselineReportId: String? = null,
    @field:Min(1)
    @field:Max(100)
    val topK: Int? = null,
    @field:Min(1)
    @field:Max(200)
    val worstQueries: Int? = null,
    val targetIndex: String? = null,
    val reportIdPrefix: String? = "regression"
) {
    fun toDomain(): RegressionEvaluationRequest = RegressionEvaluationRequest(
        datasetId = datasetId?.trim().takeUnless { it.isNullOrBlank() },
        baselineReportId = baselineReportId?.trim().takeUnless { it.isNullOrBlank() },
        topK = topK,
        worstQueries = worstQueries,
        targetIndex = targetIndex?.trim().takeUnless { it.isNullOrBlank() },
        reportIdPrefix = reportIdPrefix?.trim().takeUnless { it.isNullOrBlank() }
    )
}

data class RegressionEvaluationResponse(
    val datasetId: String,
    val baselineReportId: String,
    val evaluation: EvaluationRunResponse,
    val report: EvaluationReportResponse,
    val comparison: EvaluationComparisonResponse
) {
    companion object {
        fun from(result: RegressionEvaluationResult): RegressionEvaluationResponse = RegressionEvaluationResponse(
            datasetId = result.request.datasetId,
            baselineReportId = result.request.baselineReportId,
            evaluation = EvaluationRunResponse.from(result.runResult, result.report),
            report = EvaluationReportResponse.from(result.report)!!,
            comparison = EvaluationComparisonResponse.from(result.comparison, result.comparisonMarkdownPath)
        )
    }
}

data class EvaluatedQueryResponse(
    val queryId: String,
    val intent: String,
    val topK: Int,
    val tookMs: Long,
    val totalHits: Long,
    val judgedHits: Int,
    val relevantHits: Int,
    val metrics: QueryMetricsResponse,
    val hits: List<EvaluatedHitResponse>
) {
    companion object {
        fun from(result: EvaluatedQueryResult): EvaluatedQueryResponse = EvaluatedQueryResponse(
            queryId = result.queryId,
            intent = result.intent,
            topK = result.topK,
            tookMs = result.tookMs,
            totalHits = result.totalHits,
            judgedHits = result.judgedHits,
            relevantHits = result.relevantHits,
            metrics = QueryMetricsResponse.from(result.metrics),
            hits = result.hits.map { EvaluatedHitResponse.from(it) }
        )
    }
}

data class EvaluatedHitResponse(
    val rank: Int,
    val documentId: UUID,
    val title: String,
    val category: String,
    val author: String,
    val publishedAt: Instant,
    val popularityScore: Double,
    val score: Double?,
    val grade: Int?,
    val judged: Boolean
) {
    companion object {
        fun from(hit: EvaluatedHit): EvaluatedHitResponse = EvaluatedHitResponse(
            rank = hit.rank,
            documentId = hit.document.id,
            title = hit.document.title,
            category = hit.document.category,
            author = hit.document.author,
            publishedAt = hit.document.publishedAt,
            popularityScore = hit.document.popularityScore,
            score = hit.score,
            grade = hit.grade,
            judged = hit.judged
        )
    }
}

/**
 * 단일 쿼리의 지표 응답 모델.
 */
data class QueryMetricsResponse(
    val precisionAtK: Double,
    val recallAtK: Double,
    val mrr: Double,
    val ndcgAtK: Double,
    val relevantJudgements: Int,
    val relevantRetrieved: Int
) {
    companion object {
        fun from(metrics: QueryMetrics): QueryMetricsResponse = QueryMetricsResponse(
            precisionAtK = metrics.precisionAtK,
            recallAtK = metrics.recallAtK,
            mrr = metrics.mrr,
            ndcgAtK = metrics.ndcgAtK,
            relevantJudgements = metrics.relevantJudgements,
            relevantRetrieved = metrics.relevantRetrieved
        )
    }
}

/**
 * 전체 평가 결과에 대한 평균 지표 응답 모델.
 */
data class EvaluationMetricsSummaryResponse(
    val topK: Int,
    val totalQueries: Int,
    val meanPrecisionAtK: Double,
    val meanRecallAtK: Double,
    val meanMrr: Double,
    val meanNdcgAtK: Double
) {
    companion object {
        fun from(summary: EvaluationMetricsSummary): EvaluationMetricsSummaryResponse =
            EvaluationMetricsSummaryResponse(
                topK = summary.topK,
                totalQueries = summary.totalQueries,
                meanPrecisionAtK = summary.meanPrecisionAtK,
                meanRecallAtK = summary.meanRecallAtK,
                meanMrr = summary.meanMrr,
                meanNdcgAtK = summary.meanNdcgAtK
            )
    }
}

/**
 * 생성된 리포트 파일 경로 및 Worst Queries 응답 모델.
 */
data class EvaluationReportResponse(
    val reportId: String,
    val metricsPath: String,
    val summaryPath: String,
    val targetIndex: String,
    val worstQueries: List<WorstQueryResponse>
) {
    companion object {
        fun from(report: EvaluationReport?): EvaluationReportResponse? = report?.let {
            EvaluationReportResponse(
                reportId = it.reportId,
                metricsPath = it.metricsPath.toString(),
                summaryPath = it.summaryPath.toString(),
                targetIndex = it.targetIndex,
                worstQueries = it.worstQueries.map { entry -> WorstQueryResponse.from(entry) }
            )
        }
    }
}

/**
 * Worst Query 단일 응답 모델.
 */
data class WorstQueryResponse(
    val queryId: String,
    val intent: String,
    val precisionAtK: Double,
    val recallAtK: Double,
    val mrr: Double,
    val ndcgAtK: Double,
    val judgedHits: Int,
    val relevantHits: Int,
    val totalHits: Long
) {
    companion object {
        fun from(entry: WorstQueryEntry): WorstQueryResponse = WorstQueryResponse(
            queryId = entry.queryId,
            intent = entry.intent,
            precisionAtK = entry.precisionAtK,
            recallAtK = entry.recallAtK,
            mrr = entry.mrr,
            ndcgAtK = entry.ndcgAtK,
            judgedHits = entry.judgedHits,
            relevantHits = entry.relevantHits,
            totalHits = entry.totalHits
        )
    }
}

data class EvaluationComparisonResponse(
    val beforeReportId: String,
    val afterReportId: String,
    val metricsDelta: List<MetricDeltaResponse>,
    val improvedQueries: List<WorstQueryChangeResponse>,
    val regressedQueries: List<WorstQueryChangeResponse>,
    val markdownPath: String
) {
    companion object {
        fun from(comparison: EvaluationComparison, markdownPath: Path): EvaluationComparisonResponse =
            EvaluationComparisonResponse(
                beforeReportId = comparison.beforeReportId,
                afterReportId = comparison.afterReportId,
                metricsDelta = comparison.metricsDelta.map { MetricDeltaResponse.from(it) },
                improvedQueries = comparison.improvedQueries.map { WorstQueryChangeResponse.from(it) },
                regressedQueries = comparison.regressedQueries.map { WorstQueryChangeResponse.from(it) },
                markdownPath = markdownPath.toAbsolutePath().toString()
            )
    }
}

data class MetricDeltaResponse(
    val metric: String,
    val before: Double,
    val after: Double,
    val delta: Double
) {
    companion object {
        fun from(delta: MetricDelta): MetricDeltaResponse = MetricDeltaResponse(
            metric = delta.name,
            before = delta.before,
            after = delta.after,
            delta = delta.delta
        )
    }
}

data class WorstQueryChangeResponse(
    val queryId: String,
    val intent: String,
    val ndcgDelta: Double,
    val precisionDelta: Double?,
    val recallDelta: Double?,
    val mrrDelta: Double?,
    val before: WorstQueryResponse?,
    val after: WorstQueryResponse?,
    val status: String
) {
    companion object {
        fun from(change: WorstQueryChange): WorstQueryChangeResponse = WorstQueryChangeResponse(
            queryId = change.queryId,
            intent = change.intent,
            ndcgDelta = change.ndcgDelta,
            precisionDelta = change.precisionDelta,
            recallDelta = change.recallDelta,
            mrrDelta = change.mrrDelta,
            before = change.before?.let { WorstQueryResponse.from(it) },
            after = change.after?.let { WorstQueryResponse.from(it) },
            status = change.status.name
        )
    }
}

data class AnalyzerExperimentRequestDto(
    @field:NotBlank(message = "datasetId는 필수입니다.")
    val datasetId: String,
    @field:Min(1)
    @field:Max(100)
    val topK: Int = 10,
    @field:Min(1)
    @field:Max(200)
    val worstQueries: Int = 20,
    @field:Min(1)
    val baseTemplateVersion: Int = 1,
    @field:NotBlank(message = "sampleDataPath는 비어 있을 수 없습니다.")
    val sampleDataPath: String = "docs/data/sample_documents.json",
    val caseNames: List<String> = emptyList(),
    val cleanupAfterRun: Boolean = true,
    val generateReport: Boolean = true
) {
    fun toDomain(): AnalyzerExperimentRequest = AnalyzerExperimentRequest(
        datasetId = datasetId.trim(),
        topK = topK,
        worstQueries = worstQueries,
        baseTemplateVersion = baseTemplateVersion,
        sampleDataPath = sampleDataPath.trim(),
        caseNames = caseNames.map { it.trim() }.filter { it.isNotBlank() },
        cleanupAfterRun = cleanupAfterRun,
        generateReport = generateReport
    )
}

data class AnalyzerExperimentSuiteResponse(
    val runId: String,
    val datasetId: String,
    val topK: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val successCount: Int,
    val results: List<AnalyzerExperimentResultResponse>
) {
    companion object {
        fun from(result: AnalyzerExperimentSuiteResult): AnalyzerExperimentSuiteResponse =
            AnalyzerExperimentSuiteResponse(
                runId = result.runId,
                datasetId = result.datasetId,
                topK = result.topK,
                startedAt = result.startedAt,
                completedAt = result.completedAt,
                successCount = result.successCount,
                results = result.results.map { AnalyzerExperimentResultResponse.from(it) }
            )
    }
}

data class AnalyzerExperimentResultResponse(
    val caseName: String,
    val description: String,
    val decompoundMode: String,
    val useUserDictionary: Boolean,
    val useSynonymGraph: Boolean,
    val indexName: String,
    val targetIndex: String?,
    val status: AnalyzerExperimentStatus,
    val cleanedUp: Boolean,
    val errorMessage: String?,
    val bulk: AnalyzerBulkIndexSummaryResponse?,
    val metrics: EvaluationMetricsSummaryResponse?,
    val report: EvaluationReportResponse?
) {
    companion object {
        fun from(result: AnalyzerExperimentResult): AnalyzerExperimentResultResponse =
            AnalyzerExperimentResultResponse(
                caseName = result.case.name,
                description = result.case.description,
                decompoundMode = result.case.decompoundMode,
                useUserDictionary = result.case.useUserDictionary,
                useSynonymGraph = result.case.useSynonymGraph,
                indexName = result.indexName,
                targetIndex = result.evaluationResult?.targetIndex ?: result.indexName,
                status = result.status,
                cleanedUp = result.cleanedUp,
                errorMessage = result.errorMessage,
                bulk = AnalyzerBulkIndexSummaryResponse.from(result.bulkIndexResult),
                metrics = result.evaluationResult?.metricsSummary?.let { EvaluationMetricsSummaryResponse.from(it) },
                report = EvaluationReportResponse.from(result.report)
            )
    }
}

/**
 * Bulk 색인 요약 응답 DTO(실험 전용, 실패 목록은 제외).
 */
data class AnalyzerBulkIndexSummaryResponse(
    val target: String,
    val total: Int,
    val success: Int,
    val failed: Int,
    val attempts: Int,
    val tookMs: Long
) {
    companion object {
        fun from(result: BulkIndexResult?): AnalyzerBulkIndexSummaryResponse? = result?.let {
            AnalyzerBulkIndexSummaryResponse(
                target = it.target,
                total = it.total,
                success = it.success,
                failed = it.failed,
                attempts = it.attempts,
                tookMs = it.tookMs
            )
        }
    }
}
