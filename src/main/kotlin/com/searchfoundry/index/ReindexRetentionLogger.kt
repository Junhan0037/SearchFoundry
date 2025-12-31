package com.searchfoundry.index

import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 블루그린 전환 시 기존 인덱스 보관/롤백을 위한 메타 정보를 기록하는 로거.
 * - reports/reindex/{timestamp}_{target}/manifest.md 형태로 기록한다.
 */
@Component
class ReindexRetentionLogger(
    @Value("\${reindex.retention.base-path:reports/reindex}") basePath: String = "reports/reindex"
) {
    private val logger = LoggerFactory.getLogger(ReindexRetentionLogger::class.java)
    private val baseDir: Path = Paths.get(basePath)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    /**
     * 구 인덱스/alias 이전 상태/카운트를 기록한다.
     */
    fun recordRetention(request: RetentionRecordRequest): Path {
        val timestamp = timestampFormatter.format(Instant.now())
        val dir = baseDir.resolve("${timestamp}_${request.targetIndex}")
        val manifestPath = dir.resolve("manifest.md")

        try {
            Files.createDirectories(dir)
            val content = renderManifest(request, timestamp)
            Files.writeString(manifestPath, content, StandardCharsets.UTF_8)
            logger.info("Reindex 보관 manifest를 생성했습니다(path={})", manifestPath.toAbsolutePath())
            return manifestPath
        } catch (ex: Exception) {
            logger.error("Reindex 보관 manifest 생성 실패(target={}): {}", request.targetIndex, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "Reindex 보관 manifest 생성 중 오류가 발생했습니다.", ex.message)
        }
    }

    private fun renderManifest(request: RetentionRecordRequest, timestamp: String): String = buildString {
        appendLine("# Reindex Retention Manifest")
        appendLine("- Timestamp (UTC): $timestamp")
        appendLine("- Source Index: ${request.sourceIndex}")
        appendLine("- Target Index: ${request.targetIndex}")
        appendLine("- Previous read alias targets: ${request.previousReadTargets.joinToString(",").ifEmpty { "none" }}")
        appendLine("- Previous write alias targets: ${request.previousWriteTargets.joinToString(",").ifEmpty { "none" }}")
        appendLine("- Source Count: ${request.sourceCount}")
        appendLine("- Target Count: ${request.targetCount}")
        appendLine("- Note: 이전 인덱스는 롤백 대비 보관 후 별도 정책에 따라 삭제하세요.")
    }
}

/**
 * 보관 기록에 필요한 정보.
 */
data class RetentionRecordRequest(
    val sourceIndex: String,
    val targetIndex: String,
    val previousReadTargets: List<String>,
    val previousWriteTargets: List<String>,
    val sourceCount: Long,
    val targetCount: Long
)
