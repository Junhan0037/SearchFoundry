package com.searchfoundry.eval

import com.searchfoundry.eval.dataset.Judgement
import org.springframework.stereotype.Component
import kotlin.math.log2
import kotlin.math.pow

/**
 * Precision@K, Recall@K, MRR, nDCG@K 등을 계산하는 유틸리티.
 * - grade > 0인 Judgement을 relevant로 간주한다.
 * - 검색 결과가 topK보다 적을 경우 실제 반환된 결과 수로 precision 분모를 잡아 편향을 줄인다.
 */
@Component
class EvaluationMetricCalculator {

    /**
     * 단일 쿼리의 topK 결과와 JudgementSet을 기반으로 핵심 지표를 산출한다.
     */
    fun calculateQueryMetrics(
        hits: List<EvaluatedHit>,
        judgements: List<Judgement>,
        topK: Int
    ): QueryMetrics {
        val relevantJudgements = judgements.count { it.grade > 0 }
        val normalizedHits = hits.take(topK)
        val retrievedCount = normalizedHits.size.coerceAtLeast(1) // 분모 0 방지
        val relevantRetrieved = normalizedHits.count { (it.grade ?: 0) > 0 }

        val precisionAtK = relevantRetrieved.toDouble() / retrievedCount
        val recallAtK = if (relevantJudgements == 0) 0.0 else relevantRetrieved.toDouble() / relevantJudgements
        val meanReciprocalRank = calculateMrr(normalizedHits)
        val ndcgAtK = calculateNdcg(normalizedHits, judgements, topK)

        return QueryMetrics(
            precisionAtK = precisionAtK,
            recallAtK = recallAtK,
            mrr = meanReciprocalRank,
            ndcgAtK = ndcgAtK,
            relevantJudgements = relevantJudgements,
            relevantRetrieved = relevantRetrieved
        )
    }

    /**
     * 실행된 모든 쿼리의 지표를 평균 내어 요약 값을 반환한다.
     */
    fun summarize(results: List<EvaluatedQueryResult>): EvaluationMetricsSummary {
        if (results.isEmpty()) {
            return EvaluationMetricsSummary(
                topK = 0,
                totalQueries = 0,
                meanPrecisionAtK = 0.0,
                meanRecallAtK = 0.0,
                meanMrr = 0.0,
                meanNdcgAtK = 0.0
            )
        }

        val size = results.size.toDouble()
        val topK = results.first().topK

        return EvaluationMetricsSummary(
            topK = topK,
            totalQueries = results.size,
            meanPrecisionAtK = results.sumOf { it.metrics.precisionAtK } / size,
            meanRecallAtK = results.sumOf { it.metrics.recallAtK } / size,
            meanMrr = results.sumOf { it.metrics.mrr } / size,
            meanNdcgAtK = results.sumOf { it.metrics.ndcgAtK } / size
        )
    }

    /**
     * 첫 번째 relevant 문서의 rank를 기반으로 MRR을 계산한다.
     */
    private fun calculateMrr(hits: List<EvaluatedHit>): Double {
        val firstRelevant = hits.firstOrNull { (it.grade ?: 0) > 0 } ?: return 0.0
        return 1.0 / firstRelevant.rank
    }

    /**
     * 실제 DCG와 IDCG를 비교해 nDCG@K를 계산한다.
     */
    private fun calculateNdcg(
        hits: List<EvaluatedHit>,
        judgements: List<Judgement>,
        topK: Int
    ): Double {
        val dcg = dcg(hits, topK)
        val idcg = idealDcg(judgements, topK)
        if (idcg == 0.0) {
            return 0.0
        }
        return dcg / idcg
    }

    /**
     * 얻은 결과의 DCG를 계산한다.
     */
    private fun dcg(hits: List<EvaluatedHit>, topK: Int): Double =
        hits.take(topK).mapIndexed { index, hit ->
            val grade = (hit.grade ?: 0).coerceAtLeast(0)
            if (grade == 0) {
                0.0
            } else {
                val gain = 2.0.pow(grade) - 1
                gain / log2((index + 2).toDouble()) // rank = index + 1, log2(rank + 1)
            }
        }.sum()

    /**
     * 정답 세트를 이상적인 정렬(grade 내림차순)로 두었을 때의 DCG(IDCG)를 계산한다.
     */
    private fun idealDcg(judgements: List<Judgement>, topK: Int): Double {
        val sortedGrades = judgements
            .map { it.grade }
            .filter { it > 0 }
            .sortedDescending()
            .take(topK)

        return sortedGrades.mapIndexed { index, grade ->
            val gain = 2.0.pow(grade) - 1
            gain / log2((index + 2).toDouble())
        }.sum()
    }
}

/**
 * 단일 쿼리의 핵심 지표 값.
 */
data class QueryMetrics(
    val precisionAtK: Double,
    val recallAtK: Double,
    val mrr: Double,
    val ndcgAtK: Double,
    val relevantJudgements: Int,
    val relevantRetrieved: Int
)

/**
 * 여러 쿼리 결과의 평균 지표 요약.
 */
data class EvaluationMetricsSummary(
    val topK: Int,
    val totalQueries: Int,
    val meanPrecisionAtK: Double,
    val meanRecallAtK: Double,
    val meanMrr: Double,
    val meanNdcgAtK: Double
)
