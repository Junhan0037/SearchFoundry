package com.searchfoundry.api.admin

import com.searchfoundry.core.document.Document
import com.searchfoundry.index.AliasState
import com.searchfoundry.index.BlueGreenReindexRequest
import com.searchfoundry.index.BlueGreenReindexResult
import com.searchfoundry.index.BlueGreenReindexService
import com.searchfoundry.index.BulkIndexFailure
import com.searchfoundry.index.BulkIndexResult
import com.searchfoundry.index.BulkIndexService
import com.searchfoundry.index.CountValidationResult
import com.searchfoundry.index.HashValidationResult
import com.searchfoundry.index.IndexCreationResult
import com.searchfoundry.index.IndexCreationService
import com.searchfoundry.index.ReindexRollbackCommand
import com.searchfoundry.index.ReindexRollbackResult
import com.searchfoundry.index.ReindexRollbackService
import com.searchfoundry.index.ReindexFailure
import com.searchfoundry.index.ReindexValidationOptions
import com.searchfoundry.index.ReindexValidationResult
import com.searchfoundry.index.SampleQueryDiff
import com.searchfoundry.index.SampleQueryValidationResult
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Elasticsearch 인덱스 생성/관리용 Admin API.
 */
@RestController
@RequestMapping("/admin/index")
@Validated
class IndexAdminController(
    private val indexCreationService: IndexCreationService,
    private val bulkIndexService: BulkIndexService,
    private val reindexRollbackService: ReindexRollbackService,
    private val blueGreenReindexService: BlueGreenReindexService
) {

    /**
     * 버전 파라미터를 받아 docs_v{version} 인덱스를 생성한다.
     */
    @PostMapping("/create")
    fun createIndex(
        @RequestParam(name = "version", defaultValue = "1") @Min(1) version: Int
    ): IndexCreateResponse {
        val result = indexCreationService.createIndex(version)
        return IndexCreateResponse.from(result)
    }

    /**
     * docs_write alias(또는 지정된 타겟)로 문서 배치 색인을 수행한다.
     */
    @PostMapping("/bulk")
    fun bulkIndex(
        @RequestBody @Valid request: BulkIndexRequest
    ): BulkIndexResponse {
        val documents = request.documents.map { it.toDocument() }
        val target = request.targetAlias ?: "docs_write"
        val result = bulkIndexService.bulkIndex(documents, targetAlias = target)
        return BulkIndexResponse.from(result)
    }

    /**
     * 블루그린 reindex를 자동화한다.
     * - 새 인덱스 생성 → reindex → 검증 → alias 스위치 → 보관 manifest 기록을 한 번에 실행한다.
     */
    @PostMapping("/reindex")
    fun blueGreenReindex(
        @RequestBody @Valid request: BlueGreenReindexRequestDto
    ): BlueGreenReindexResponse {
        val result = blueGreenReindexService.reindex(request.toDomain())
        return BlueGreenReindexResponse.from(result)
    }

    /**
     * 블루그린 스위치 직후 장애 발생 시 이전 인덱스로 alias를 원자적으로 롤백한다.
     */
    @PostMapping("/rollback")
    fun rollback(
        @RequestBody @Valid request: ReindexRollbackRequest
    ): ReindexRollbackResponse {
        val result = reindexRollbackService.rollback(
            ReindexRollbackCommand(
                currentIndex = request.currentIndex,
                rollbackToIndex = request.rollbackToIndex
            )
        )
        return ReindexRollbackResponse.from(result)
    }
}

/**
 * 인덱스 생성 결과를 전달하는 응답 DTO.
 */
data class IndexCreateResponse(
    val indexName: String,
    val acknowledged: Boolean,
    val shardsAcknowledged: Boolean
) {
    companion object {
        fun from(result: IndexCreationResult) = IndexCreateResponse(
            indexName = result.indexName,
            acknowledged = result.acknowledged,
            shardsAcknowledged = result.shardsAcknowledged
        )
    }
}

/**
 * Bulk 색인 요청 페이로드.
 */
data class BulkIndexRequest(
    val targetAlias: String? = null,
    @field:NotEmpty(message = "documents는 최소 1개 이상이어야 합니다.")
    val documents: List<@Valid BulkIndexDocumentRequest>
)

/**
 * Bulk 색인 문서 단위 요청 DTO.
 */
data class BulkIndexDocumentRequest(
    @field:NotBlank(message = "id는 비어 있을 수 없습니다.")
    val id: String,
    @field:NotBlank(message = "title은 비어 있을 수 없습니다.")
    val title: String,
    val summary: String?,
    @field:NotBlank(message = "body는 비어 있을 수 없습니다.")
    val body: String,
    val tags: List<@NotBlank(message = "tag 값은 비어 있을 수 없습니다.") String> = emptyList(),
    @field:NotBlank(message = "category는 비어 있을 수 없습니다.")
    val category: String,
    @field:NotBlank(message = "author는 비어 있을 수 없습니다.")
    val author: String,
    @field:NotNull(message = "publishedAt은 필수입니다.")
    val publishedAt: Instant,
    @field:PositiveOrZero(message = "popularityScore는 0 이상이어야 합니다.")
    val popularityScore: Double
) {
    fun toDocument(): Document = Document(
        id = UUID.fromString(id),
        title = title,
        summary = summary,
        body = body,
        tags = tags,
        category = category,
        author = author,
        publishedAt = publishedAt,
        popularityScore = popularityScore
    )
}

/**
 * Bulk 색인 응답 DTO.
 */
data class BulkIndexResponse(
    val target: String,
    val total: Int,
    val success: Int,
    val failed: Int,
    val attempts: Int,
    val tookMs: Long,
    val failures: List<BulkIndexFailureResponse>
) {
    companion object {
        fun from(result: BulkIndexResult): BulkIndexResponse =
            BulkIndexResponse(
                target = result.target,
                total = result.total,
                success = result.success,
                failed = result.failed,
                attempts = result.attempts,
                tookMs = result.tookMs,
                failures = result.failures.map { BulkIndexFailureResponse.from(it) }
            )
    }
}

data class BulkIndexFailureResponse(
    val id: String,
    val status: Int?,
    val reason: String,
    val attempt: Int
) {
    companion object {
        fun from(failure: BulkIndexFailure): BulkIndexFailureResponse =
            BulkIndexFailureResponse(
                id = failure.id,
                status = failure.status,
                reason = failure.reason,
                attempt = failure.attempt
            )
    }
}

data class BlueGreenReindexRequestDto(
    @field:Min(value = 1, message = "sourceVersion은 1 이상이어야 합니다.")
    val sourceVersion: Int,
    @field:Min(value = 1, message = "targetVersion은 1 이상이어야 합니다.")
    val targetVersion: Int,
    val waitForCompletion: Boolean = true,
    val refreshAfter: Boolean = true,
    @field:Valid
    val validation: ReindexValidationOptionsDto? = null
) {
    fun toDomain(): BlueGreenReindexRequest = BlueGreenReindexRequest(
        sourceVersion = sourceVersion,
        targetVersion = targetVersion,
        validationOptions = validation?.toDomain() ?: ReindexValidationOptions(),
        waitForCompletion = waitForCompletion,
        refreshAfter = refreshAfter
    )
}

data class ReindexValidationOptionsDto(
    val enableCountValidation: Boolean? = null,
    val enableSampleQueryValidation: Boolean? = null,
    val enableHashValidation: Boolean? = null,
    val sampleQueries: List<@NotBlank(message = "sampleQueries 항목은 비어 있을 수 없습니다.") String> = emptyList(),
    @field:Min(value = 1, message = "sampleTopK는 1 이상이어야 합니다.")
    val sampleTopK: Int? = null,
    @field:DecimalMin(value = "0.0", message = "minJaccard는 0.0 이상이어야 합니다.")
    val minJaccard: Double? = null,
    @field:Min(value = 1, message = "hashMaxDocs는 1 이상이어야 합니다.")
    val hashMaxDocs: Int? = null,
    @field:Min(value = 1, message = "hashPageSize는 1 이상이어야 합니다.")
    val hashPageSize: Int? = null
) {
    fun toDomain(): ReindexValidationOptions = ReindexValidationOptions(
        enableCountValidation = enableCountValidation,
        enableSampleQueryValidation = enableSampleQueryValidation,
        enableHashValidation = enableHashValidation,
        sampleQueries = sampleQueries,
        sampleTopK = sampleTopK,
        minJaccard = minJaccard,
        hashMaxDocs = hashMaxDocs,
        hashPageSize = hashPageSize
    )
}

data class BlueGreenReindexResponse(
    val sourceIndex: String,
    val targetIndex: String,
    val sourceCount: Long,
    val targetCount: Long,
    val reindexTookMs: Long,
    val failures: List<ReindexFailureResponse>,
    val aliasBefore: AliasStateResponse,
    val aliasAfter: AliasStateResponse,
    val readAlias: String,
    val writeAlias: String,
    val waitForCompletion: Boolean,
    val validation: ReindexValidationResponse,
    val retentionManifestPath: String
) {
    companion object {
        fun from(result: BlueGreenReindexResult): BlueGreenReindexResponse =
            BlueGreenReindexResponse(
                sourceIndex = result.sourceIndex,
                targetIndex = result.targetIndex,
                sourceCount = result.sourceCount,
                targetCount = result.targetCount,
                reindexTookMs = result.reindexTookMs,
                failures = result.failures.map { ReindexFailureResponse.from(it) },
                aliasBefore = AliasStateResponse.from(result.aliasBefore),
                aliasAfter = AliasStateResponse.from(result.aliasAfter),
                readAlias = result.readAlias,
                writeAlias = result.writeAlias,
                waitForCompletion = result.waitForCompletion,
                validation = ReindexValidationResponse.from(result.validation),
                retentionManifestPath = result.retentionManifestPath
            )
    }
}

data class ReindexFailureResponse(
    val index: String?,
    val reason: String
) {
    companion object {
        fun from(failure: ReindexFailure): ReindexFailureResponse = ReindexFailureResponse(
            index = failure.index,
            reason = failure.reason
        )
    }
}

data class ReindexValidationResponse(
    val passed: Boolean,
    val countValidation: CountValidationResponse?,
    val sampleQueryValidation: SampleQueryValidationResponse?,
    val hashValidation: HashValidationResponse?
) {
    companion object {
        fun from(result: ReindexValidationResult): ReindexValidationResponse = ReindexValidationResponse(
            passed = result.passed,
            countValidation = result.countValidation?.let { CountValidationResponse.from(it) },
            sampleQueryValidation = result.sampleQueryValidation?.let { SampleQueryValidationResponse.from(it) },
            hashValidation = result.hashValidation?.let { HashValidationResponse.from(it) }
        )
    }
}

data class CountValidationResponse(
    val sourceCount: Long,
    val targetCount: Long,
    val passed: Boolean
) {
    companion object {
        fun from(result: CountValidationResult): CountValidationResponse = CountValidationResponse(
            sourceCount = result.sourceCount,
            targetCount = result.targetCount,
            passed = result.passed
        )
    }
}

data class SampleQueryValidationResponse(
    val sampleTopK: Int,
    val minJaccard: Double,
    val queriesEvaluated: List<SampleQueryDiffResponse>,
    val passed: Boolean
) {
    companion object {
        fun from(result: SampleQueryValidationResult): SampleQueryValidationResponse =
            SampleQueryValidationResponse(
                sampleTopK = result.sampleTopK,
                minJaccard = result.minJaccard,
                queriesEvaluated = result.queriesEvaluated.map { SampleQueryDiffResponse.from(it) },
                passed = result.passed
            )
    }
}

data class SampleQueryDiffResponse(
    val query: String,
    val sourceTopIds: List<String>,
    val targetTopIds: List<String>,
    val jaccard: Double,
    val missingInTarget: List<String>,
    val missingInSource: List<String>,
    val passed: Boolean
) {
    companion object {
        fun from(diff: SampleQueryDiff): SampleQueryDiffResponse = SampleQueryDiffResponse(
            query = diff.query,
            sourceTopIds = diff.sourceTopIds,
            targetTopIds = diff.targetTopIds,
            jaccard = diff.jaccard,
            missingInTarget = diff.missingInTarget,
            missingInSource = diff.missingInSource,
            passed = diff.passed
        )
    }
}

data class HashValidationResponse(
    val sourceHash: String,
    val targetHash: String,
    val sourceDocsHashed: Long,
    val targetDocsHashed: Long,
    val maxDocsEvaluated: Int,
    val pageSize: Int,
    val passed: Boolean
) {
    companion object {
        fun from(result: HashValidationResult): HashValidationResponse = HashValidationResponse(
            sourceHash = result.sourceHash,
            targetHash = result.targetHash,
            sourceDocsHashed = result.sourceDocsHashed,
            targetDocsHashed = result.targetDocsHashed,
            maxDocsEvaluated = result.maxDocsEvaluated,
            pageSize = result.pageSize,
            passed = result.passed
        )
    }
}

data class ReindexRollbackRequest(
    @field:NotBlank(message = "현재 alias가 가리키는 인덱스(currentIndex)는 필수입니다.")
    val currentIndex: String,
    @field:NotBlank(message = "롤백 대상 인덱스(rollbackToIndex)는 필수입니다.")
    val rollbackToIndex: String
)

data class ReindexRollbackResponse(
    val rollbackToIndex: String,
    val currentIndex: String,
    val aliasBefore: AliasStateResponse,
    val aliasAfter: AliasStateResponse
) {
    companion object {
        fun from(result: ReindexRollbackResult): ReindexRollbackResponse =
            ReindexRollbackResponse(
                rollbackToIndex = result.rollbackToIndex,
                currentIndex = result.currentIndex,
                aliasBefore = AliasStateResponse.from(result.aliasBefore),
                aliasAfter = AliasStateResponse.from(result.aliasAfter)
            )
    }
}

data class AliasStateResponse(
    val readTargets: List<String>,
    val writeTargets: List<String>
) {
    companion object {
        fun from(state: AliasState): AliasStateResponse =
            AliasStateResponse(
                readTargets = state.readTargets,
                writeTargets = state.writeTargets
            )
    }
}
