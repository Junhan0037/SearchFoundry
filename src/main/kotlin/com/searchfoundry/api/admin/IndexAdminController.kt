package com.searchfoundry.api.admin

import com.searchfoundry.core.document.Document
import com.searchfoundry.index.BulkIndexFailure
import com.searchfoundry.index.BulkIndexResult
import com.searchfoundry.index.BulkIndexService
import com.searchfoundry.index.IndexCreationResult
import com.searchfoundry.index.IndexCreationService
import jakarta.validation.Valid
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
    private val bulkIndexService: BulkIndexService
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
