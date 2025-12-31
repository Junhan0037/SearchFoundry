package com.searchfoundry.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import com.searchfoundry.core.document.Document
import com.searchfoundry.core.search.DocumentSearchService
import com.searchfoundry.core.search.SearchQuery
import com.searchfoundry.core.search.SearchSort
import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated

/**
 * reindex 검증 시나리오를 수행한다.
 * - 문서 수, 대표 쿼리 결과 diff, 문서 해시 비교 중 최소 2개 이상을 동시에 수행한다.
 * - 실패 시 AppException을 던져 alias 스위치 이전에 중단한다.
 */
@Service
class ReindexValidationService(
    private val elasticsearchClient: ElasticsearchClient,
    private val documentSearchService: DocumentSearchService,
    private val properties: ReindexValidationProperties
    // 새로운 검증 스텝 추가 시 AppException으로 래핑해 호출 측에서 일괄 처리한다.
) {
    private val logger = LoggerFactory.getLogger(ReindexValidationService::class.java)

    /**
     * 카운트/샘플 쿼리 diff/해시 비교를 조합해 검증을 수행한다.
     */
    fun validate(request: ReindexValidationRequest): ReindexValidationResult {
        val options = request.options.resolve(properties)

        val countResult = if (options.enableCountValidation) {
            validateCounts(request.sourceIndex, request.targetIndex)
        } else {
            null
        }

        val sampleQueryResult = if (options.enableSampleQueryValidation && options.sampleQueries.isNotEmpty()) {
            validateSampleQueries(request.sourceIndex, request.targetIndex, options)
        } else {
            null
        }

        val hashResult = if (options.enableHashValidation) {
            validateHashes(request.sourceIndex, request.targetIndex, options)
        } else {
            null
        }

        val passed = listOfNotNull(
            countResult?.passed,
            sampleQueryResult?.passed,
            hashResult?.passed
        ).all { it }

        return ReindexValidationResult(
            countValidation = countResult,
            sampleQueryValidation = sampleQueryResult,
            hashValidation = hashResult,
            passed = passed
        )
    }

    /**
     * 문서 수 카운트를 비교해 누락/중복을 빠르게 탐지한다.
     */
    private fun validateCounts(sourceIndex: String, targetIndex: String): CountValidationResult {
        val sourceCount = elasticsearchClient.count { builder -> builder.index(sourceIndex) }.count()
        val targetCount = elasticsearchClient.count { builder -> builder.index(targetIndex) }.count()
        val matched = sourceCount == targetCount

        if (!matched) {
            logger.warn("reindex 카운트 불일치(source={}, target={}): {} vs {}", sourceIndex, targetIndex, sourceCount, targetCount)
        }

        return CountValidationResult(
            sourceCount = sourceCount,
            targetCount = targetCount,
            passed = matched
        )
    }

    /**
     * 대표 쿼리 집합을 양쪽 인덱스에 실행해 topK overlap(Jaccard)을 측정한다.
     */
    private fun validateSampleQueries(
        sourceIndex: String,
        targetIndex: String,
        options: ResolvedReindexValidationOptions
    ): SampleQueryValidationResult {
        val diffs = options.sampleQueries.map { query ->
            val sourceTopIds = fetchTopIds(sourceIndex, query, options.sampleTopK)
            val targetTopIds = fetchTopIds(targetIndex, query, options.sampleTopK)
            val overlap = sourceTopIds.toSet().intersect(targetTopIds.toSet()).size
            val union = (sourceTopIds + targetTopIds).toSet().size
            val jaccard = if (union == 0) 1.0 else overlap.toDouble() / union.toDouble()
            val passed = jaccard >= options.minJaccard

            SampleQueryDiff(
                query = query,
                sourceTopIds = sourceTopIds,
                targetTopIds = targetTopIds,
                jaccard = jaccard,
                missingInTarget = sourceTopIds.filterNot { targetTopIds.contains(it) },
                missingInSource = targetTopIds.filterNot { sourceTopIds.contains(it) },
                passed = passed
            )
        }

        val passed = diffs.all { it.passed }
        if (!passed) {
            logger.warn("샘플 쿼리 diff 검증 실패(source={}, target={}, threshold={}): {}", sourceIndex, targetIndex, options.minJaccard, diffs)
        }

        return SampleQueryValidationResult(
            sampleTopK = options.sampleTopK,
            minJaccard = options.minJaccard,
            queriesEvaluated = diffs,
            passed = passed
        )
    }

    /**
     * 두 인덱스의 문서 해시를 일정 개수까지 비교해 내용 차이를 탐지한다.
     * - 해시 대상 문서 수는 hashMaxDocs로 제한해 비용을 제어한다.
     */
    private fun validateHashes(
        sourceIndex: String,
        targetIndex: String,
        options: ResolvedReindexValidationOptions
    ): HashValidationResult {
        try {
            val sourceHash = computeIndexHash(sourceIndex, options)
            val targetHash = computeIndexHash(targetIndex, options)
            val matched = sourceHash.hash == targetHash.hash && sourceHash.scanned == targetHash.scanned

            if (!matched) {
                logger.warn(
                    "문서 해시 검증 실패(source={}, target={}): hash {} vs {}, scanned {} vs {}",
                    sourceIndex,
                    targetIndex,
                    sourceHash.hash,
                    targetHash.hash,
                    sourceHash.scanned,
                    targetHash.scanned
                )
            }

            return HashValidationResult(
                sourceHash = sourceHash.hash,
                targetHash = targetHash.hash,
                sourceDocsHashed = sourceHash.scanned,
                targetDocsHashed = targetHash.scanned,
                maxDocsEvaluated = options.hashMaxDocs,
                pageSize = options.hashPageSize,
                passed = matched
            )
        } catch (ex: Exception) {
            logger.error("해시 검증 중 오류 발생(source={}, target={}): {}", sourceIndex, targetIndex, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "해시 검증 중 오류가 발생했습니다.", ex.message)
        }
    }

    /**
     * 샘플 쿼리의 topK 문서 ID를 조회한다.
     */
    private fun fetchTopIds(index: String, query: String, topK: Int): List<String> {
        val result = documentSearchService.search(
            SearchQuery(
                query = query,
                category = null,
                tags = emptyList(),
                author = null,
                publishedFrom = null,
                publishedTo = null,
                sort = SearchSort.RELEVANCE,
                page = 0,
                size = topK,
                targetIndex = index
            )
        )

        return result.hits.take(topK).map { it.document.id.toString() }
    }

    /**
     * match_all 쿼리를 사용해 문서 일부를 deterministic order로 스캔하며 해시를 만든다.
     */
    private fun computeIndexHash(index: String, options: ResolvedReindexValidationOptions): IndexHash {
        val digest = MessageDigest.getInstance("SHA-256")
        val pageSize = options.hashPageSize
        var from = 0
        var scanned = 0

        while (scanned < options.hashMaxDocs) {
            val fetchSize = minOf(pageSize, options.hashMaxDocs - scanned)
            val response = elasticsearchClient.search({ builder ->
                builder
                    .index(index)
                    .query(Query.of { q -> q.matchAll { it } })
                    .from(from)
                    .size(fetchSize)
                    .sort { sort -> sort.field { field -> field.field("_id").order(SortOrder.Asc) } }
                    .source { source ->
                        source.filter { filter ->
                            filter.includes(
                                "id",
                                "title",
                                "summary",
                                "body",
                                "tags",
                                "category",
                                "author",
                                "publishedAt",
                                "popularityScore"
                            )
                        }
                    }
            }, Document::class.java)

            val hits = response.hits().hits()
            if (hits.isEmpty()) {
                break
            }

            hits.forEach { hit ->
                val doc = hit.source() ?: return@forEach
                updateDigest(digest, doc)
            }

            scanned += hits.size
            if (hits.size < fetchSize) {
                break
            }

            from += fetchSize
        }

        return IndexHash(hash = digest.digest().toHex(), scanned = scanned.toLong())
    }

    /**
     * 문서 주요 필드를 deterministic 문자열로 직렬화 후 해시를 업데이트한다.
     */
    private fun updateDigest(digest: MessageDigest, doc: Document) {
        val serialized = buildString {
            append(doc.id)
            append('|')
            append(doc.title)
            append('|')
            append(doc.summary.orEmpty())
            append('|')
            append(doc.body)
            append('|')
            append(doc.tags.sorted().joinToString(","))
            append('|')
            append(doc.category)
            append('|')
            append(doc.author)
            append('|')
            append(doc.publishedAt)
            append('|')
            append(doc.popularityScore)
        }

        digest.update(serialized.toByteArray(StandardCharsets.UTF_8))
    }
}

/**
 * reindex 검증 요청 모델.
 */
data class ReindexValidationRequest(
    val sourceIndex: String,
    val targetIndex: String,
    val options: ReindexValidationOptions = ReindexValidationOptions()
)

/**
 * 호출 시점에 덮어쓰는 검증 옵션. 값이 비어 있으면 기본 속성을 따른다.
 */
data class ReindexValidationOptions(
    val enableCountValidation: Boolean? = null,
    val enableSampleQueryValidation: Boolean? = null,
    val enableHashValidation: Boolean? = null,
    val sampleQueries: List<String> = emptyList(),
    val sampleTopK: Int? = null,
    val minJaccard: Double? = null,
    val hashMaxDocs: Int? = null,
    val hashPageSize: Int? = null
) {
    fun resolve(properties: ReindexValidationProperties): ResolvedReindexValidationOptions {
        val sampleTopK = (this.sampleTopK ?: properties.sampleTopK).coerceAtLeast(1)
        val hashMaxDocs = (this.hashMaxDocs ?: properties.hashMaxDocs).coerceAtLeast(1)
        val hashPageSize = (this.hashPageSize ?: properties.hashPageSize).coerceAtLeast(1)
        val minJaccard = this.minJaccard ?: properties.minJaccard
        require(minJaccard in 0.0..1.0) { "minJaccard는 0.0 이상 1.0 이하 값이어야 합니다." }

        val sampleQueries = if (this.sampleQueries.isNotEmpty()) {
            this.sampleQueries
        } else {
            properties.sampleQueries
        }

        return ResolvedReindexValidationOptions(
            enableCountValidation = this.enableCountValidation ?: properties.enableCountValidation,
            enableSampleQueryValidation = this.enableSampleQueryValidation ?: properties.enableSampleQueryValidation,
            enableHashValidation = this.enableHashValidation ?: properties.enableHashValidation,
            sampleQueries = sampleQueries,
            sampleTopK = sampleTopK,
            minJaccard = minJaccard,
            hashMaxDocs = hashMaxDocs,
            hashPageSize = hashPageSize
        )
    }
}

/**
 * 실제 사용되는 검증 옵션(모두 값이 채워진 상태).
 */
data class ResolvedReindexValidationOptions(
    val enableCountValidation: Boolean,
    val enableSampleQueryValidation: Boolean,
    val enableHashValidation: Boolean,
    val sampleQueries: List<String>,
    val sampleTopK: Int,
    val minJaccard: Double,
    val hashMaxDocs: Int,
    val hashPageSize: Int
)

/**
 * 검증 결과 집계 모델.
 */
data class ReindexValidationResult(
    val countValidation: CountValidationResult?,
    val sampleQueryValidation: SampleQueryValidationResult?,
    val hashValidation: HashValidationResult?,
    val passed: Boolean
) {
    fun failureReasons(): List<String> {
        val reasons = mutableListOf<String>()
        if (countValidation?.passed == false) {
            reasons += "문서 수 불일치(${countValidation.sourceCount} vs ${countValidation.targetCount})"
        }
        if (sampleQueryValidation?.passed == false) {
            reasons += "샘플 쿼리 diff 실패(${sampleQueryValidation.minJaccard} 이상 불만족)"
        }
        if (hashValidation?.passed == false) {
            reasons += "해시 비교 실패(${hashValidation.sourceHash} vs ${hashValidation.targetHash})"
        }
        return reasons
    }
}

data class CountValidationResult(
    val sourceCount: Long,
    val targetCount: Long,
    val passed: Boolean
)

data class SampleQueryValidationResult(
    val sampleTopK: Int,
    val minJaccard: Double,
    val queriesEvaluated: List<SampleQueryDiff>,
    val passed: Boolean
)

data class SampleQueryDiff(
    val query: String,
    val sourceTopIds: List<String>,
    val targetTopIds: List<String>,
    val jaccard: Double,
    val missingInTarget: List<String>,
    val missingInSource: List<String>,
    val passed: Boolean
)

data class HashValidationResult(
    val sourceHash: String,
    val targetHash: String,
    val sourceDocsHashed: Long,
    val targetDocsHashed: Long,
    val maxDocsEvaluated: Int,
    val pageSize: Int,
    val passed: Boolean
)

private data class IndexHash(
    val hash: String,
    val scanned: Long
)

/**
 * reindex 검증 기본 속성. yaml로 오버라이드 가능하다.
 */
@Validated
@ConfigurationProperties(prefix = "reindex.validation")
data class ReindexValidationProperties(
    val sampleQueries: List<String> = listOf("쿠버네티스 인그레스 설정", "로그 수집 파이프라인", "nori 분석기 튜닝"),
    @field:Min(1)
    val sampleTopK: Int = 5,
    @field:DecimalMin("0.0")
    val minJaccard: Double = 0.6,
    @field:Min(1)
    val hashMaxDocs: Int = 5000,
    @field:Min(1)
    val hashPageSize: Int = 500,
    val enableCountValidation: Boolean = true,
    val enableSampleQueryValidation: Boolean = true,
    val enableHashValidation: Boolean = true
) {
    init {
        require(minJaccard in 0.0..1.0) { "minJaccard는 0.0 이상 1.0 이하 값이어야 합니다." }
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
