package com.searchfoundry.index

import com.searchfoundry.core.document.Document
import com.searchfoundry.core.search.SearchHit
import com.searchfoundry.core.search.SearchQuery
import com.searchfoundry.core.search.SearchResult
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReindexValidationServiceTest {

    @Test
    fun `카운트가 불일치하면 실패 사유를 반환한다`() {
        val indexReader = StubIndexReader(
            counts = mapOf("source-index" to 10L, "target-index" to 8L)
        )
        val searchPort = StubSearchPort(emptyList(), emptyList())
        val properties = ReindexValidationProperties(
            enableCountValidation = true,
            enableSampleQueryValidation = false,
            enableHashValidation = false
        )
        val service = ReindexValidationService(indexReader, searchPort, properties)

        val result = service.validate(ReindexValidationRequest("source-index", "target-index"))

        assertFalse(result.passed)
        assertEquals(10, result.countValidation?.sourceCount)
        assertEquals(8, result.countValidation?.targetCount)
        assertTrue(result.failureReasons().any { it.contains("문서 수 불일치") })
    }

    @Test
    fun `샘플 쿼리 diff가 임계치 미만이면 실패한다`() {
        val searchPort = StubSearchPort(
            sourceIds = listOf("doc-1", "doc-2", "doc-3"),
            targetIds = listOf("doc-1", "doc-4", "doc-5")
        )
        val indexReader = StubIndexReader(emptyMap())
        val properties = ReindexValidationProperties(
            sampleQueries = listOf("kubernetes"),
            sampleTopK = 3,
            minJaccard = 0.5,
            enableCountValidation = false,
            enableSampleQueryValidation = true,
            enableHashValidation = false
        )
        val service = ReindexValidationService(indexReader, searchPort, properties)

        val result = service.validate(ReindexValidationRequest("source-index", "target-index"))

        assertFalse(result.passed)
        val sampleValidation = result.sampleQueryValidation!!
        val diff = sampleValidation.queriesEvaluated.first()
        assertEquals(0.5, sampleValidation.minJaccard)
        assertEquals(0.2, diff.jaccard)
        val expectedSourceIds = listOf("doc-1", "doc-2", "doc-3").map { document(it).id.toString() }
        val expectedTargetIds = listOf("doc-1", "doc-4", "doc-5").map { document(it).id.toString() }
        assertEquals(expectedSourceIds.filterNot { expectedTargetIds.contains(it) }, diff.missingInTarget)
        assertEquals(expectedTargetIds.filterNot { expectedSourceIds.contains(it) }, diff.missingInSource)
        assertTrue(result.failureReasons().any { it.contains("샘플 쿼리 diff 실패") })
    }

    @Test
    fun `minJaccard가 범위를 벗어나면 resolve 시 예외가 발생한다`() {
        val options = ReindexValidationOptions(minJaccard = 1.2)
        assertThrows(IllegalArgumentException::class.java) {
            options.resolve(ReindexValidationProperties())
        }
    }

    private class StubIndexReader(
        private val counts: Map<String, Long>,
        private val scans: Map<String, List<Document>> = emptyMap()
    ) : ReindexIndexReader {
        override fun count(index: String): Long = counts[index] ?: 0L

        override fun scan(index: String, from: Int, size: Int): List<Document> {
            val docs = scans[index].orEmpty()
            return docs.drop(from).take(size)
        }
    }

    private inner class StubSearchPort(
        private val sourceIds: List<String>,
        private val targetIds: List<String>
    ) : ReindexSearchPort {
        override fun search(request: SearchQuery): SearchResult {
            val ids = if (request.targetIndex == "source-index") sourceIds else targetIds
            return SearchResult(
                total = ids.size.toLong(),
                page = request.page,
                size = request.size,
                tookMs = 1,
                hits = ids.mapIndexed { idx, id ->
                    SearchHit(
                        document = document(id),
                        score = (ids.size - idx).toDouble(),
                        highlights = emptyMap()
                    )
                }
            )
        }
    }

    private fun document(idSeed: String): Document =
        Document(
            id = UUID.nameUUIDFromBytes(idSeed.toByteArray()),
            title = "title-$idSeed",
            summary = "summary-$idSeed",
            body = "body-$idSeed",
            tags = listOf("tag1", "tag2"),
            category = "devops",
            author = "author",
            publishedAt = Instant.parse("2024-01-01T00:00:00Z"),
            popularityScore = 1.0
        )
}
