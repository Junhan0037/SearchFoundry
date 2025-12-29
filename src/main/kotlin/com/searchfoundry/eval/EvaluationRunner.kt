package com.searchfoundry.eval

import com.searchfoundry.core.document.Document
import com.searchfoundry.core.search.DocumentSearchService
import com.searchfoundry.core.search.SearchQuery
import com.searchfoundry.core.search.SearchSort
import com.searchfoundry.eval.dataset.EvalQuery
import com.searchfoundry.eval.dataset.EvaluationDatasetLoader
import com.searchfoundry.eval.dataset.Judgement
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * QuerySet을 읽어 topK 검색을 실행하고 JudgementSet과 매칭하는 러너.
 */
@Service
class EvaluationRunner(
    private val evaluationDatasetLoader: EvaluationDatasetLoader,
    private val documentSearchService: DocumentSearchService,
    private val evaluationMetricCalculator: EvaluationMetricCalculator
) {
    private val logger = LoggerFactory.getLogger(EvaluationRunner::class.java)

    /**
     * datasetId를 기준으로 QuerySet/JudgementSet을 로드한 뒤 topK 검색 결과와 매칭한다.
     */
    fun run(datasetId: String, topK: Int, targetIndex: String? = null): EvaluationRunResult {
        require(topK > 0) { "topK는 1 이상이어야 합니다." }

        val dataset = evaluationDatasetLoader.load(datasetId)
        val startedAt = Instant.now()
        val startNanos = System.nanoTime()

        val evaluatedResults = dataset.queries.map { query ->
            val judgements = dataset.judgementsByQuery[query.queryId].orEmpty()
            evaluateSingleQuery(query, judgements, topK, targetIndex)
        }

        val elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis()
        val metricsSummary = evaluationMetricCalculator.summarize(evaluatedResults)
        logger.info(
            "평가 러너 실행 완료(datasetId={}, queries={}, topK={}, elapsedMs={})",
            datasetId,
            evaluatedResults.size,
            topK,
            elapsedMs
        )

        return EvaluationRunResult(
            datasetId = datasetId,
            topK = topK,
            totalQueries = evaluatedResults.size,
            startedAt = startedAt,
            completedAt = Instant.now(),
            elapsedMs = elapsedMs,
            targetIndex = targetIndex,
            metricsSummary = metricsSummary,
            results = evaluatedResults
        )
    }

    /**
     * 단일 쿼리에 대해 검색을 실행하고 JudgementSet과 매칭한다.
     */
    private fun evaluateSingleQuery(
        query: EvalQuery,
        judgements: List<Judgement>,
        topK: Int,
        targetIndex: String?
    ): EvaluatedQueryResult {
        val judgementByDocId: Map<UUID, Judgement> = judgements.associateBy { it.docId }
        val searchResult = documentSearchService.search(query.toSearchQuery(topK, targetIndex))

        val hits = searchResult.hits.take(topK).mapIndexed { index, hit ->
            val judgement = judgementByDocId[hit.document.id]
            EvaluatedHit(
                rank = index + 1,
                document = hit.document,
                score = hit.score,
                grade = judgement?.grade,
                judged = judgement != null
            )
        }

        val judgedCount = hits.count { it.judged }
        val relevantCount = hits.count { (it.grade ?: 0) > 0 }
        val metrics = evaluationMetricCalculator.calculateQueryMetrics(hits, judgements, topK)

        return EvaluatedQueryResult(
            queryId = query.queryId,
            intent = query.intent,
            topK = topK,
            tookMs = searchResult.tookMs,
            totalHits = searchResult.total,
            judgedHits = judgedCount,
            relevantHits = relevantCount,
            metrics = metrics,
            hits = hits
        )
    }

    /**
     * QuerySet 엔트리를 검색 요청 모델로 변환한다.
     */
    private fun EvalQuery.toSearchQuery(topK: Int, targetIndex: String?): SearchQuery {
        val filter = this.filters
        return SearchQuery(
            query = this.queryText,
            category = filter?.category,
            tags = filter?.tags ?: emptyList(),
            author = filter?.author,
            publishedFrom = filter?.publishedAtFrom,
            publishedTo = filter?.publishedAtTo,
            sort = SearchSort.RELEVANCE,
            page = 0,
            size = topK,
            targetIndex = targetIndex
        )
    }
}

/**
 * 평가 실행 결과 요약.
 */
data class EvaluationRunResult(
    val datasetId: String,
    val topK: Int,
    val totalQueries: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val elapsedMs: Long,
    val targetIndex: String?,
    val metricsSummary: EvaluationMetricsSummary,
    val results: List<EvaluatedQueryResult>
)

/**
 * 단일 쿼리의 평가 결과.
 */
data class EvaluatedQueryResult(
    val queryId: String,
    val intent: String,
    val topK: Int,
    val tookMs: Long,
    val totalHits: Long,
    val judgedHits: Int,
    val relevantHits: Int,
    val metrics: QueryMetrics,
    val hits: List<EvaluatedHit>
)

/**
 * topK 결과 + judgement 매핑 정보.
 */
data class EvaluatedHit(
    val rank: Int,
    val document: Document,
    val score: Double?,
    val grade: Int?,
    val judged: Boolean
)
