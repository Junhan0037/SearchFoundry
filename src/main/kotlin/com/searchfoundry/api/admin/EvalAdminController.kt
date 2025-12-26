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
import com.searchfoundry.support.api.ApiResponse
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * 평가 러너를 트리거하는 Admin API.
 */
@RestController
@RequestMapping("/admin/eval")
@Validated
class EvalAdminController(
    private val evaluationRunner: EvaluationRunner,
    private val evaluationReportGenerator: EvaluationReportGenerator
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
}

data class EvaluationRunResponse(
    val datasetId: String,
    val topK: Int,
    val totalQueries: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val elapsedMs: Long,
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
            metrics = EvaluationMetricsSummaryResponse.from(result.metricsSummary),
            report = EvaluationReportResponse.from(report),
            results = result.results.map { EvaluatedQueryResponse.from(it) }
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
    val worstQueries: List<WorstQueryResponse>
) {
    companion object {
        fun from(report: EvaluationReport?): EvaluationReportResponse? = report?.let {
            EvaluationReportResponse(
                reportId = it.reportId,
                metricsPath = it.metricsPath.toString(),
                summaryPath = it.summaryPath.toString(),
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
