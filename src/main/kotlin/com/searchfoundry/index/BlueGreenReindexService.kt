package com.searchfoundry.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.OpType
import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * docs_v{n} → docs_v{n+1} 블루그린 reindex를 오케스트레이션하는 서비스.
 * - 새 인덱스 생성 → reindex API → 검증(count/샘플 쿼리 diff/해시) → alias 전환을 원자적으로 수행한다.
 * - 실패 시 alias를 변경하지 않고 예외를 던져 롤백 가능 상태를 보존한다.
 */
@Service
class BlueGreenReindexService(
    private val elasticsearchClient: ElasticsearchClient,
    private val indexCreationService: IndexCreationService,
    private val indexAliasService: IndexAliasService,
    private val reindexRetentionLogger: ReindexRetentionLogger,
    private val reindexValidationService: ReindexValidationService
) {
    private val logger = LoggerFactory.getLogger(BlueGreenReindexService::class.java)

    private val readAlias = "docs_read"
    private val writeAlias = "docs_write"

    /**
     * 블루그린 reindex를 실행한다.
     * - 검증(카운트/샘플 쿼리 diff/해시) 실패 시 alias 전환을 하지 않고 예외를 던진다.
     * - waitForCompletion=true일 때 동기 완료를 보장한다.
     */
    fun reindex(request: BlueGreenReindexRequest): BlueGreenReindexResult {
        require(request.sourceVersion != request.targetVersion) { "source/target 버전이 동일합니다." }
        val sourceIndex = indexName(request.sourceVersion)
        val targetIndex = indexName(request.targetVersion)

        val beforeAliasState = indexAliasService.currentAliasState()

        // 새 인덱스 생성 (매핑/설정 적용)
        indexCreationService.createIndex(request.targetVersion)

        // reindex 실행
        val reindexResponse = executeReindex(sourceIndex, targetIndex, request)

        // 검증 시나리오 실행(count + 샘플 쿼리 diff + 해시 비교)
        val validationResult = reindexValidationService.validate(
            ReindexValidationRequest(
                sourceIndex = sourceIndex,
                targetIndex = targetIndex,
                options = request.validationOptions
            )
        )

        if (!validationResult.passed) {
            val reasons = validationResult.failureReasons().joinToString("; ").ifBlank { "검증 실패" }
            logger.error("reindex 검증 실패(source={}, target={}): {}", sourceIndex, targetIndex, reasons)
            throw AppException(ErrorCode.INTERNAL_ERROR, "reindex 검증에 실패했습니다.", reasons)
        }

        // alias 전환 (read/write 모두 새 인덱스로 이동)
        indexAliasService.switchToIndex(targetIndex)
        val afterAliasState = indexAliasService.currentAliasState()

        val sourceCount = validationResult.countValidation?.sourceCount ?: countIndex(sourceIndex)
        val targetCount = validationResult.countValidation?.targetCount ?: countIndex(targetIndex)
        val retentionManifestPath = reindexRetentionLogger.recordRetention(
            RetentionRecordRequest(
                sourceIndex = sourceIndex,
                targetIndex = targetIndex,
                previousReadTargets = beforeAliasState.readTargets,
                previousWriteTargets = beforeAliasState.writeTargets,
                sourceCount = sourceCount,
                targetCount = targetCount
            )
        ).toAbsolutePath().toString()

        val result = BlueGreenReindexResult(
            sourceIndex = sourceIndex,
            targetIndex = targetIndex,
            sourceCount = sourceCount,
            targetCount = targetCount,
            reindexTookMs = reindexResponse.took() ?: 0L,
            failures = reindexResponse.failures().orEmpty().map { failure ->
                ReindexFailure(reason = "${failure.cause()?.reason()}", index = failure.index())
            },
            aliasBefore = beforeAliasState,
            aliasAfter = afterAliasState,
            readAlias = readAlias,
            writeAlias = writeAlias,
            waitForCompletion = request.waitForCompletion,
            validation = validationResult,
            retentionManifestPath = retentionManifestPath
        )

        return result
    }

    /**
     * ES Reindex API를 실행한다. 실패 시 예외를 래핑한다.
     */
    private fun executeReindex(
        sourceIndex: String,
        targetIndex: String,
        request: BlueGreenReindexRequest
    ) = try {
        val response = elasticsearchClient.reindex { builder ->
            builder
                .source { src -> src.index(sourceIndex) }
                .dest { dest -> dest.index(targetIndex).opType(OpType.Create) }
                .waitForCompletion(request.waitForCompletion)
                .refresh(request.refreshAfter)
        }

        val failures = response.failures().orEmpty()
        if (failures.isNotEmpty()) {
            val reason = failures.joinToString("; ") { "${it.index()}:${it.cause()?.reason()}" }
            throw AppException(ErrorCode.INTERNAL_ERROR, "reindex 중 실패 항목이 발생했습니다.", reason)
        }

        logger.info(
            "reindex 완료(source={}, target={}, tookMs={}, total={})",
            sourceIndex,
            targetIndex,
            response.took(),
            response.total()
        )
        response
    } catch (ex: ElasticsearchException) {
        logger.error("reindex 수행 중 Elasticsearch 예외(source={}, target={}): {}", sourceIndex, targetIndex, ex.message, ex)
        throw ex
    } catch (ex: Exception) {
        logger.error("reindex 수행 실패(source={}, target={}): {}", sourceIndex, targetIndex, ex.message, ex)
        throw AppException(ErrorCode.INTERNAL_ERROR, "reindex 수행 중 오류가 발생했습니다.", ex.message)
    }

    /**
     * 인덱스 문서 수를 집계한다.
     */
    private fun countIndex(index: String): Long =
        elasticsearchClient.count { builder -> builder.index(index) }.count()

    private fun indexName(version: Int) = "docs_v$version"
}

/**
 * 블루그린 reindex 요청 파라미터.
 */
data class BlueGreenReindexRequest(
    val sourceVersion: Int,
    val targetVersion: Int,
    val validationOptions: ReindexValidationOptions = ReindexValidationOptions(),
    val waitForCompletion: Boolean = true,
    val refreshAfter: Boolean = true
) {
    init {
        require(sourceVersion > 0) { "sourceVersion은 0보다 커야 합니다." }
        require(targetVersion > 0) { "targetVersion은 0보다 커야 합니다." }
    }
}

/**
 * 블루그린 reindex 실행 결과.
 */
data class BlueGreenReindexResult(
    val sourceIndex: String,
    val targetIndex: String,
    val sourceCount: Long,
    val targetCount: Long,
    val reindexTookMs: Long,
    val failures: List<ReindexFailure>,
    val aliasBefore: AliasState,
    val aliasAfter: AliasState,
    val readAlias: String,
    val writeAlias: String,
    val waitForCompletion: Boolean,
    val validation: ReindexValidationResult,
    val retentionManifestPath: String
)

/**
 * reindex 과정에서 발생한 실패 항목 요약.
 */
data class ReindexFailure(
    val reason: String,
    val index: String?
)
