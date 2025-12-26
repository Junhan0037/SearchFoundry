package com.searchfoundry.eval

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.searchfoundry.core.document.Document
import com.searchfoundry.eval.dataset.Judgement
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EvaluationReportGeneratorTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val metricCalculator = EvaluationMetricCalculator()

    @Test
    fun `리포트 생성 시 metrics와 summary 파일이 생성되고 Worst Query가 포함된다`(@TempDir tempDir: Path) {
        val generator = EvaluationReportGenerator(objectMapper, tempDir.resolve("reports").toString())
        val runResult = sampleRunResult()

        val report = generator.generate(runResult, worstQueriesCount = 1)

        assertTrue(Files.exists(report.metricsPath))
        assertTrue(Files.exists(report.summaryPath))

        val json = objectMapper.readValue(report.metricsPath.toFile(), EvaluationReportJson::class.java)
        assertEquals(1, json.worstQueries.size)
        assertEquals("q2", json.worstQueries.first().queryId)

        val summaryContent = Files.readString(report.summaryPath)
        assertTrue(summaryContent.contains("Worst Queries"))
    }

    private fun sampleRunResult(): EvaluationRunResult {
        val doc1 = document("doc-1")
        val doc2 = document("doc-2")
        val doc3 = document("doc-3")

        val judgements = listOf(
            Judgement(queryId = "q1", docId = doc1.id, grade = 3),
            Judgement(queryId = "q1", docId = doc2.id, grade = 2),
            Judgement(queryId = "q2", docId = doc3.id, grade = 3)
        )

        // q1: 상단 정답 → 높은 nDCG
        val q1Hits = listOf(
            EvaluatedHit(rank = 1, document = doc1, score = 10.0, grade = 3, judged = true),
            EvaluatedHit(rank = 2, document = doc2, score = 8.0, grade = 2, judged = true),
            EvaluatedHit(rank = 3, document = doc3, score = 1.0, grade = 0, judged = true)
        )
        val q1Metrics = metricCalculator.calculateQueryMetrics(q1Hits, judgements.filter { it.queryId == "q1" }, 3)

        // q2: 정답이 하위 순위 → 낮은 nDCG
        val q2Hits = listOf(
            EvaluatedHit(rank = 1, document = doc1, score = 5.0, grade = 0, judged = false),
            EvaluatedHit(rank = 2, document = doc2, score = 4.0, grade = 0, judged = false),
            EvaluatedHit(rank = 3, document = doc3, score = 3.0, grade = 3, judged = true)
        )
        val q2Metrics = metricCalculator.calculateQueryMetrics(q2Hits, judgements.filter { it.queryId == "q2" }, 3)

        val evaluated = listOf(
            EvaluatedQueryResult(
                queryId = "q1",
                intent = "info",
                topK = 3,
                tookMs = 15,
                totalHits = 100,
                judgedHits = 3,
                relevantHits = 2,
                metrics = q1Metrics,
                hits = q1Hits
            ),
            EvaluatedQueryResult(
                queryId = "q2",
                intent = "nav",
                topK = 3,
                tookMs = 12,
                totalHits = 50,
                judgedHits = 1,
                relevantHits = 1,
                metrics = q2Metrics,
                hits = q2Hits
            )
        )

        val summary = metricCalculator.summarize(evaluated)

        return EvaluationRunResult(
            datasetId = "baseline",
            topK = 3,
            totalQueries = evaluated.size,
            startedAt = Instant.parse("2024-01-01T00:00:00Z"),
            completedAt = Instant.parse("2024-01-01T00:00:01Z"),
            elapsedMs = 1000,
            metricsSummary = summary,
            results = evaluated
        )
    }

    private fun document(idSeed: String): Document =
        Document(
            id = UUID.nameUUIDFromBytes(idSeed.toByteArray()),
            title = "title-$idSeed",
            summary = "summary-$idSeed",
            body = "body-$idSeed",
            tags = listOf("tag1"),
            category = "tech",
            author = "author",
            publishedAt = Instant.parse("2023-12-31T00:00:00Z"),
            popularityScore = 0.1
        )
}
