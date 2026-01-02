package com.searchfoundry.core.observability

import com.fasterxml.jackson.databind.ObjectMapper
import com.searchfoundry.support.config.ObservabilityProperties
import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.ceil

/**
 * 검색 프로파일/슬로우로그 수집 결과를 디스크에 기록해 재현 가능한 리포트를 만든다.
 */
@Component
class ObservabilityReportWriter(
    private val objectMapper: ObjectMapper,
    observabilityProperties: ObservabilityProperties
) {
    private val logger = LoggerFactory.getLogger(ObservabilityReportWriter::class.java)
    private val baseDir: Path = Paths.get(observabilityProperties.reportBasePath)

    /**
     * 관측 결과를 JSON/Markdown으로 기록하고 경로 정보를 반환한다.
     */
    fun write(result: SearchObservationResult): SearchObservationReport {
        try {
            val runDir = baseDir.resolve(result.runId)
            Files.createDirectories(runDir)

            val observationPath = runDir.resolve("observation.json")
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(observationPath.toFile(), result)

            val profilePath = runDir.resolve("profile.json")
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(profilePath.toFile(), result.profiledQueries)

            val slowlogPath = result.slowlogSnapshot?.let { snapshot ->
                val path = runDir.resolve("slowlog.json")
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), snapshot)
                path
            }

            val summaryPath = runDir.resolve("summary.md")
            Files.writeString(
                summaryPath,
                renderSummaryMarkdown(result, profilePath, slowlogPath),
                StandardCharsets.UTF_8
            )

            logger.info(
                "관측 리포트 생성 완료(runId={}, dir={})",
                result.runId,
                runDir.toAbsolutePath()
            )

            return SearchObservationReport(
                runId = result.runId,
                datasetId = result.datasetId,
                targetIndex = result.targetIndex,
                topK = result.topK,
                totalQueries = result.totalQueries,
                profiledQueries = result.profiledQueries.size,
                slowlogEntries = result.slowlogSnapshot?.entries?.size ?: 0,
                observationPath = observationPath,
                profilePath = profilePath,
                slowlogPath = slowlogPath,
                summaryPath = summaryPath,
                startedAt = result.startedAt,
                completedAt = result.completedAt,
                elapsedMs = result.elapsedMs
            )
        } catch (ex: Exception) {
            logger.error("관측 리포트 생성 실패(runId={}): {}", result.runId, ex.message, ex)
            throw AppException(ErrorCode.INTERNAL_ERROR, "검색 관측 리포트 생성 중 오류가 발생했습니다.", ex.message)
        }
    }

    /**
     * 주요 수치를 Markdown으로 요약한다.
     */
    private fun renderSummaryMarkdown(
        result: SearchObservationResult,
        profilePath: Path,
        slowlogPath: Path?
    ): String {
        val builder = StringBuilder()
        val latencySummary = summarizeLatency(result.profiledQueries.map { it.tookMs })
        val topQueries = result.profiledQueries.sortedByDescending { it.tookMs }.take(5)

        builder.appendLine("# 검색 관측 리포트")
        builder.appendLine("- Run ID: ${result.runId}")
        builder.appendLine("- Dataset: ${result.datasetId}")
        builder.appendLine("- Target Index/Alias: ${result.targetIndex}")
        builder.appendLine("- TopK: ${result.topK}")
        builder.appendLine("- Started At (UTC): ${result.startedAt}")
        builder.appendLine("- Completed At (UTC): ${result.completedAt}")
        builder.appendLine("- Elapsed(ms): ${result.elapsedMs}")
        builder.appendLine("- Profile JSON: ${profilePath.toAbsolutePath()}")
        if (slowlogPath != null) {
            builder.appendLine("- Slowlog JSON: ${slowlogPath.toAbsolutePath()}")
        } else {
            builder.appendLine("- Slowlog JSON: 수집 안 함")
        }

        builder.appendLine()
        builder.appendLine("## 검색 프로파일 지표")
        if (result.profiledQueries.isEmpty()) {
            builder.appendLine("- profile 수집이 비활성화되어 결과가 없습니다.")
        } else {
            builder.appendLine("|Stat|Value|")
            builder.appendLine("|---|---|")
            builder.appendLine("|Profiled Queries|${result.profiledQueries.size}|")
            latencySummary?.let {
                builder.appendLine("|Avg Took (ms)|${"%.2f".format(it.avgMs)}|")
                builder.appendLine("|P95 Took (ms)|${it.p95Ms}|")
                builder.appendLine("|Max Took (ms)|${it.maxMs}|")
            }

            builder.appendLine()
            builder.appendLine("### Took 상위 5개 쿼리")
            builder.appendLine("|QueryId|Took(ms)|TotalHits|Query|")
            builder.appendLine("|---|---|---|---|")
            topQueries.forEach { query ->
                builder.appendLine("|${query.queryId}|${query.tookMs}|${query.totalHits}|${query.queryText}|")
            }
        }

        builder.appendLine()
        builder.appendLine("## 슬로우로그 요약")
        result.slowlogSnapshot?.let { snapshot ->
            builder.appendLine(
                "- Path: ${snapshot.path}, collected=${snapshot.collected}/${snapshot.totalLines}, missing=${snapshot.missing}"
            )
            val topSlowlogs = snapshot.entries
                .sortedByDescending { it.tookMillis ?: 0 }
                .take(5)

            if (topSlowlogs.isEmpty()) {
                builder.appendLine("- 슬로우로그 엔트리가 없습니다.")
            } else {
                builder.appendLine("|Timestamp|Took(ms)|Index|Shard|Level|")
                builder.appendLine("|---|---|---|---|---|")
                topSlowlogs.forEach { entry ->
                    builder.appendLine(
                        "|${entry.timestamp ?: "-"}|${entry.tookMillis ?: "-"}|" +
                            "${entry.index ?: "-"}|${entry.shard ?: "-"}|${entry.level ?: "-"}|"
                    )
                }
            }
        } ?: builder.appendLine("- 슬로우로그 수집이 비활성화되었습니다.")

        return builder.toString()
    }

    private fun summarizeLatency(tooks: List<Long>): LatencySummary? {
        if (tooks.isEmpty()) return null
        val sorted = tooks.sorted()
        val avg = tooks.average()
        val p95 = percentile(sorted, 0.95)
        val max = sorted.last()
        return LatencySummary(avgMs = avg, p95Ms = p95, maxMs = max)
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        if (sorted.isEmpty()) return 0
        val rank = ceil(percentile * sorted.size).toInt() - 1
        val index = rank.coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}

/**
 * 리포트 파일 경로와 메타데이터.
 */
data class SearchObservationReport(
    val runId: String,
    val datasetId: String,
    val targetIndex: String,
    val topK: Int,
    val totalQueries: Int,
    val profiledQueries: Int,
    val slowlogEntries: Int,
    val observationPath: Path,
    val profilePath: Path,
    val slowlogPath: Path?,
    val summaryPath: Path,
    val startedAt: Instant,
    val completedAt: Instant,
    val elapsedMs: Long
)

data class LatencySummary(
    val avgMs: Double,
    val p95Ms: Long,
    val maxMs: Long
)
