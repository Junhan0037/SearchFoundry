package com.searchfoundry.core.document

import java.time.Instant
import java.util.UUID

/**
 * 검색 인덱스와 평가 파이프라인이 공유하는 핵심 문서 도메인 모델.
 */
data class Document(
    val id: UUID,
    val title: String,
    val summary: String?,
    val body: String,
    val tags: List<String>,
    val category: String,
    val author: String,
    val publishedAt: Instant,
    val popularityScore: Double
) {
    init {
        require(title.isNotBlank()) { "title은 비어 있을 수 없습니다." }
        require(body.isNotBlank()) { "body는 비어 있을 수 없습니다." }
        require(category.isNotBlank()) { "category는 비어 있을 수 없습니다." }
        require(author.isNotBlank()) { "author는 비어 있을 수 없습니다." }
        require(popularityScore >= 0.0) { "popularityScore는 0 이상이어야 합니다." }
    }
}
