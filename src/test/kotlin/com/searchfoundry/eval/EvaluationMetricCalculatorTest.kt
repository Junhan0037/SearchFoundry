package com.searchfoundry.eval

import com.searchfoundry.core.document.Document
import com.searchfoundry.eval.dataset.Judgement
import java.time.Instant
import java.util.UUID
import kotlin.math.log2
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EvaluationMetricCalculatorTest {

    private val calculator = EvaluationMetricCalculator()

    @Test
    fun `완벽한 순위에서는 nDCG가 1이고 Precision@K가 최대값을 가진다`() {
        val doc1 = document("doc-1")
        val doc2 = document("doc-2")
        val doc3 = document("doc-3")

        val judgements = listOf(
            Judgement(queryId = "q1", docId = doc1.id, grade = 3),
            Judgement(queryId = "q1", docId = doc2.id, grade = 2),
            Judgement(queryId = "q1", docId = doc3.id, grade = 0)
        )

        val hits = listOf(
            EvaluatedHit(rank = 1, document = doc1, score = 10.0, grade = 3, judged = true),
            EvaluatedHit(rank = 2, document = doc2, score = 8.0, grade = 2, judged = true),
            EvaluatedHit(rank = 3, document = doc3, score = 1.0, grade = 0, judged = true)
        )

        val metrics = calculator.calculateQueryMetrics(hits, judgements, topK = 3)

        assertEquals(2.0 / 3.0, metrics.precisionAtK, 1e-6)
        assertEquals(1.0, metrics.recallAtK, 1e-6)
        assertEquals(1.0, metrics.mrr, 1e-6)
        assertEquals(1.0, metrics.ndcgAtK, 1e-6)
    }

    @Test
    fun `관련 문서가 하위 순위에 있을 경우 MRR과 nDCG가 낮아진다`() {
        val doc1 = document("doc-1")
        val doc2 = document("doc-2")
        val doc3 = document("doc-3")

        val judgements = listOf(
            Judgement(queryId = "q1", docId = doc1.id, grade = 3),
            Judgement(queryId = "q1", docId = doc2.id, grade = 2)
        )

        val hits = listOf(
            EvaluatedHit(rank = 1, document = doc3, score = 9.0, grade = 0, judged = false),
            EvaluatedHit(rank = 2, document = document("doc-4"), score = 8.0, grade = 0, judged = false),
            EvaluatedHit(rank = 3, document = doc1, score = 7.0, grade = 3, judged = true)
        )

        val metrics = calculator.calculateQueryMetrics(hits, judgements, topK = 3)

        assertEquals(1.0 / 3.0, metrics.precisionAtK, 1e-6)
        assertEquals(0.5, metrics.recallAtK, 1e-6)
        assertEquals(1.0 / 3.0, metrics.mrr, 1e-6)
        val expectedNdcg = ((2.0.pow(3) - 1) / log2(4.0)) /
            (((2.0.pow(3) - 1) / log2(2.0)) + ((2.0.pow(2) - 1) / log2(3.0)))
        assertEquals(expectedNdcg, metrics.ndcgAtK, 1e-6)
    }

    @Test
    fun `여러 쿼리의 지표를 평균 내어 요약할 수 있다`() {
        val doc1 = document("doc-1")
        val doc2 = document("doc-2")
        val judgements = listOf(
            Judgement(queryId = "q1", docId = doc1.id, grade = 3),
            Judgement(queryId = "q1", docId = doc2.id, grade = 2)
        )

        val perfectHits = listOf(
            EvaluatedHit(rank = 1, document = doc1, score = 9.0, grade = 3, judged = true),
            EvaluatedHit(rank = 2, document = doc2, score = 8.0, grade = 2, judged = true)
        )
        val degradedHits = listOf(
            EvaluatedHit(rank = 1, document = doc2, score = 9.0, grade = 2, judged = true),
            EvaluatedHit(rank = 2, document = document("doc-3"), score = 8.0, grade = 0, judged = false),
            EvaluatedHit(rank = 3, document = doc1, score = 7.0, grade = 3, judged = true)
        )

        val metrics1 = calculator.calculateQueryMetrics(perfectHits, judgements, topK = 3)
        val metrics2 = calculator.calculateQueryMetrics(degradedHits, judgements, topK = 3)

        val summary = calculator.summarize(
            listOf(
                evaluatedResult("q1", metrics1),
                evaluatedResult("q2", metrics2)
            )
        )

        assertEquals(3, summary.topK)
        assertEquals(2, summary.totalQueries)
        assertEquals((metrics1.precisionAtK + metrics2.precisionAtK) / 2, summary.meanPrecisionAtK, 1e-6)
        assertEquals((metrics1.recallAtK + metrics2.recallAtK) / 2, summary.meanRecallAtK, 1e-6)
        assertEquals((metrics1.mrr + metrics2.mrr) / 2, summary.meanMrr, 1e-6)
        assertEquals((metrics1.ndcgAtK + metrics2.ndcgAtK) / 2, summary.meanNdcgAtK, 1e-6)
    }

    @Test
    fun `judgement가 없을 때도 안전하게 0으로 계산된다`() {
        val metrics = calculator.calculateQueryMetrics(
            hits = emptyList(),
            judgements = emptyList(),
            topK = 5
        )

        assertEquals(0.0, metrics.precisionAtK)
        assertEquals(0.0, metrics.recallAtK)
        assertEquals(0.0, metrics.mrr)
        assertEquals(0.0, metrics.ndcgAtK)
        assertEquals(0, metrics.relevantJudgements)
        assertEquals(0, metrics.relevantRetrieved)

        val summary = calculator.summarize(emptyList())
        assertEquals(0, summary.totalQueries)
        assertEquals(0.0, summary.meanPrecisionAtK)
        assertEquals(0.0, summary.meanRecallAtK)
        assertEquals(0.0, summary.meanMrr)
        assertEquals(0.0, summary.meanNdcgAtK)
    }

    private fun document(idSeed: String): Document =
        Document(
            id = UUID.nameUUIDFromBytes(idSeed.toByteArray()),
            title = "title-$idSeed",
            summary = "summary-$idSeed",
            body = "body-$idSeed",
            tags = listOf("tag1", "tag2"),
            category = "tech",
            author = "author",
            publishedAt = Instant.parse("2024-01-01T00:00:00Z"),
            popularityScore = 0.5
        )

    private fun evaluatedResult(queryId: String, metrics: QueryMetrics): EvaluatedQueryResult =
        EvaluatedQueryResult(
            queryId = queryId,
            intent = "info",
            topK = 3,
            tookMs = 0,
            totalHits = 0,
            judgedHits = 0,
            relevantHits = 0,
            metrics = metrics,
            hits = emptyList()
        )
}
