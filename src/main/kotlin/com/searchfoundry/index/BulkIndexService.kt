package com.searchfoundry.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch.core.BulkResponse
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import com.searchfoundry.core.document.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * docs_write alias(또는 지정된 타겟)에 배치 색인을 수행하는 서비스.
 * - Bulk API 응답의 부분 실패를 수집해 리포트로 반환하고, 최대 N회 재시도한다.
 * - alias 기반 전환(blue/green)을 고려해 기본 타겟을 alias로 둔다.
 */
@Service
class BulkIndexService(
    private val elasticsearchClient: ElasticsearchClient
) {
    private val logger = LoggerFactory.getLogger(BulkIndexService::class.java)

    private val defaultTargetAlias = "docs_write"
    private val defaultChunkSize = 500
    private val defaultMaxRetries = 2

    /**
    * 문서 리스트를 Bulk API로 색인한다.
    * chunk 단위로 처리하며, 실패 항목은 제한된 횟수 내에서 재시도한다.
    */
    fun bulkIndex(
        documents: List<Document>,
        targetAlias: String = defaultTargetAlias,
        chunkSize: Int = defaultChunkSize,
        maxRetries: Int = defaultMaxRetries
    ): BulkIndexResult {
        require(chunkSize > 0) { "chunkSize는 0보다 커야 합니다." }
        require(maxRetries >= 0) { "maxRetries는 0 이상이어야 합니다." }

        if (documents.isEmpty()) {
            return BulkIndexResult(
                target = targetAlias,
                total = 0,
                success = 0,
                failed = 0,
                failures = emptyList(),
                attempts = 0,
                tookMs = 0
            )
        }

        val startTime = System.nanoTime() // 전체 수행 시간 측정용.
        var remaining = documents // 이번에 처리할 문서 집합. (처음엔 전체)
        var attempt = 0 // 현재 시도 횟수.
        var successCount = 0 // 전체 성공 누적 카운트.
        val finalFailures = mutableListOf<BulkIndexFailure>() // 마지막까지 실채한 문서의 상세 기록.

        // "실패 문서만" 재시도하는 루프
        while (remaining.isNotEmpty() && attempt <= maxRetries) {
            attempt++
            val nextRetryCandidates = mutableListOf<Document>()

            remaining.chunked(chunkSize).forEach { chunk ->
                val outcome = executeBulkChunk(targetAlias, chunk, attempt)
                successCount += outcome.successCount

                if (attempt <= maxRetries) {
                    // "이번 attempt에서 실패한 문서"를 다음 attempt 대상으로 올림.
                    nextRetryCandidates += outcome.failedItems.map { it.document }
                } else {
                    // 더이상 재시도 못하면 실패 상세를 확정 기록.
                    finalFailures += outcome.failedItems.map { failed ->
                        BulkIndexFailure(
                            id = failed.document.id.toString(),
                            status = failed.status,
                            reason = failed.reason,
                            attempt = attempt
                        )
                    }
                }
            }

            remaining = nextRetryCandidates
            if (remaining.isNotEmpty()) {
                logger.warn(
                    "Bulk 색인 실패 {}건 재시도 진행(attempt={}/{}, target={})",
                    remaining.size,
                    attempt,
                    maxRetries + 1,
                    targetAlias
                )
            }
        }

        val tookMs = Duration.ofNanos(System.nanoTime() - startTime).toMillis()
        val failureCount = finalFailures.size

        return BulkIndexResult(
            target = targetAlias,
            total = documents.size,
            success = successCount,
            failed = failureCount,
            failures = finalFailures,
            attempts = attempt,
            tookMs = tookMs
        )
    }

    /**
     * chunk 단위로 Bulk 요청을 실행하고, 부분 실패를 수집한다.
     */
    private fun executeBulkChunk(targetAlias: String, documents: List<Document>, attempt: Int): BulkAttemptOutcome {
        if (documents.isEmpty()) {
            return BulkAttemptOutcome(successCount = 0, failedItems = emptyList())
        }

        try {
            // 문서들을 BulkOperation(index)로 변환.
            val operations = documents.map { doc ->
                BulkOperation.of { op ->
                    op.index { idx ->
                        idx
                            .index(targetAlias) // 실제 인덱스가 아니라 alias로 색인. (블루/그린에 유리)
                            .id(doc.id.toString()) // 문서 ID를 명시. (중복 문서 생성 위험을 줄임)
                            .document(doc) // 실제 저장할 문서 본문
                    }
                }
            }

            // bulk 요청 실행. 한 chunk의 모든 작업을 한 번에 전송.
            val response = elasticsearchClient.bulk { builder ->
                builder.operations(operations)
            }

            // 응답을 문서별로 분석
            return analyzeBulkResponse(response, documents, attempt)
        } catch (ex: ElasticsearchException) { // 요청 자체가 실패한 경우 "chunk 전체 실패"로 묶음
            logger.error("Bulk 색인 중 Elasticsearch 예외 발생(target={}, attempt={}): {}", targetAlias, attempt, ex.message, ex)
            val failures = documents.map { doc ->
                FailedDocument(
                    document = doc,
                    status = null,
                    reason = ex.message ?: "ElasticsearchException 발생",
                    attempt = attempt
                )
            }

            return BulkAttemptOutcome(successCount = 0, failedItems = failures)
        } catch (ex: Exception) {
            logger.error("Bulk 색인 중 알 수 없는 예외 발생(target={}, attempt={}): {}", targetAlias, attempt, ex.message, ex)
            val failures = documents.map { doc ->
                FailedDocument(
                    document = doc,
                    status = null,
                    reason = ex.message ?: "알 수 없는 예외 발생",
                    attempt = attempt
                )
            }

            return BulkAttemptOutcome(successCount = 0, failedItems = failures)
        }
    }

    /**
     * Bulk 응답을 해석하여 성공/실패 항목을 분리한다.
     */
    private fun analyzeBulkResponse(
        response: BulkResponse,
        documents: List<Document>,
        attempt: Int
    ): BulkAttemptOutcome {
        val failedItems = mutableListOf<FailedDocument>()
        var successCount = 0

        response.items().forEachIndexed { index, item ->
            val error = item.error()
            if (error != null) {
                val reason = "${error.type()}: ${error.reason()}"
                val status = item.status()
                val failedDoc = documents.getOrNull(index)

                if (failedDoc != null) {
                    failedItems += FailedDocument(
                        document = failedDoc,
                        status = status,
                        reason = reason,
                        attempt = attempt
                    )
                } else {
                    logger.warn("Bulk 응답과 문서 리스트 간 인덱스 불일치(index={})가 발견되었습니다.", index)
                }
            } else {
                successCount++
            }
        }

        return BulkAttemptOutcome(
            successCount = successCount,
            failedItems = failedItems
        )
    }
}

/**
 * Bulk 색인 결과 요약 모델.
 */
data class BulkIndexResult(
    val target: String,
    val total: Int,
    val success: Int,
    val failed: Int,
    val failures: List<BulkIndexFailure>,
    val attempts: Int,
    val tookMs: Long
)

/**
 * 최종 실패 항목 리포트.
 */
data class BulkIndexFailure(
    val id: String,
    val status: Int?,
    val reason: String,
    val attempt: Int
)

private data class FailedDocument(
    val document: Document,
    val status: Int?,
    val reason: String,
    val attempt: Int
)

private data class BulkAttemptOutcome(
    val successCount: Int,
    val failedItems: List<FailedDocument>
)
