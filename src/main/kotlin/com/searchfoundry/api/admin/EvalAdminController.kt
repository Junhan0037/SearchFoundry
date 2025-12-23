package com.searchfoundry.api.admin

import com.searchfoundry.eval.EvaluatedHit
import com.searchfoundry.eval.EvaluatedQueryResult
import com.searchfoundry.eval.EvaluationRunResult
import com.searchfoundry.eval.EvaluationRunner
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
    private val evaluationRunner: EvaluationRunner
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
        ) topK: Int
    ): ApiResponse<EvaluationRunResponse> {
        val result = evaluationRunner.run(datasetId.trim(), topK)
        return ApiResponse.success(EvaluationRunResponse.from(result))
    }
}

data class EvaluationRunResponse(
    val datasetId: String,
    val topK: Int,
    val totalQueries: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val elapsedMs: Long,
    val results: List<EvaluatedQueryResponse>
) {
    companion object {
        fun from(result: EvaluationRunResult): EvaluationRunResponse = EvaluationRunResponse(
            datasetId = result.datasetId,
            topK = result.topK,
            totalQueries = result.totalQueries,
            startedAt = result.startedAt,
            completedAt = result.completedAt,
            elapsedMs = result.elapsedMs,
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
