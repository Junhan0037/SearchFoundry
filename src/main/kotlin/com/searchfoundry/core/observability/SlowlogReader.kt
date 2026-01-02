package com.searchfoundry.core.observability

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.util.ArrayDeque

/**
 * Elasticsearch 슬로우로그 파일에서 tail 구간만 읽어 구조화된 스냅샷을 만든다.
 * - 로그 파일이 없으면 빈 스냅샷을 반환해 호출부가 graceful하게 처리할 수 있게 한다.
 */
@Component
class SlowlogReader {
    private val logger = LoggerFactory.getLogger(SlowlogReader::class.java)
    private val slowlogPattern = Regex(
        """\[(?<timestamp>[^\]]+)]\[(?<level>[^\]]+)]\[(?<logger>[^\]]+)]\s*\[(?<node>[^\]]+)]\s*\[(?<index>[^\]]+)]\[(?<shard>[^\]]+)]\s*took\[(?<took>[^\]]+)](?:,\s*took_millis\[(?<tookMillis>\d+)])?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 지정된 파일 경로에서 tailLines 만큼의 엔트리를 읽어 파싱한다.
     */
    fun read(path: String, tailLines: Int): SlowlogSnapshot {
        require(tailLines > 0) { "tailLines는 1 이상이어야 합니다." }

        val filePath = Paths.get(path)
        if (!Files.exists(filePath)) {
            logger.warn("슬로우로그 파일을 찾을 수 없습니다(path={})", filePath.toAbsolutePath())
            return SlowlogSnapshot(
                path = filePath.toAbsolutePath().toString(),
                totalLines = 0,
                collected = 0,
                entries = emptyList(),
                missing = true
            )
        }

        val buffer = ArrayDeque<String>(tailLines)
        var totalLines = 0
        Files.newBufferedReader(filePath).use { reader ->
            reader.lineSequence().forEach { line ->
                totalLines += 1
                if (buffer.size == tailLines) {
                    buffer.removeFirst()
                }
                buffer.addLast(line)
            }
        }

        val entries = buffer.map { line -> parseLine(line) }
        logger.info(
            "슬로우로그 tail 수집 완료(path={}, totalLines={}, collected={})",
            filePath.toAbsolutePath(),
            totalLines,
            entries.size
        )

        return SlowlogSnapshot(
            path = filePath.toAbsolutePath().toString(),
            totalLines = totalLines,
            collected = entries.size,
            entries = entries,
            missing = false
        )
    }

    private fun parseLine(line: String): SlowlogEntry {
        val match = slowlogPattern.find(line)
        return if (match != null) {
            SlowlogEntry(
                timestamp = match.groups["timestamp"]?.value,
                level = match.groups["level"]?.value?.trim(),
                logger = match.groups["logger"]?.value,
                node = match.groups["node"]?.value,
                index = match.groups["index"]?.value,
                shard = match.groups["shard"]?.value,
                took = match.groups["took"]?.value,
                tookMillis = match.groups["tookMillis"]?.value?.toLongOrNull(),
                raw = line
            )
        } else {
            SlowlogEntry(
                timestamp = null,
                level = null,
                logger = null,
                node = null,
                index = null,
                shard = null,
                took = null,
                tookMillis = null,
                raw = line
            )
        }
    }
}

/**
 * 슬로우로그 tail 결과.
 */
data class SlowlogSnapshot(
    val path: String,
    val totalLines: Int,
    val collected: Int,
    val entries: List<SlowlogEntry>,
    val missing: Boolean
)

/**
 * 슬로우로그 단일 엔트리 파싱 결과.
 */
data class SlowlogEntry(
    val timestamp: String?,
    val level: String?,
    val logger: String?,
    val node: String?,
    val index: String?,
    val shard: String?,
    val took: String?,
    val tookMillis: Long?,
    val raw: String
)
