package com.searchfoundry.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import com.searchfoundry.index.template.IndexTemplateLoader
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 인덱스 생성(매핑/설정 적용)을 담당하는 서비스.
 * 템플릿은 파일로 버전 관리되고, 존재 여부를 사전 검증해 안전하게 생성한다.
 */
@Service
class IndexCreationService(
    private val elasticsearchClient: ElasticsearchClient,
    private val indexTemplateLoader: IndexTemplateLoader
) {
    private val logger = LoggerFactory.getLogger(IndexCreationService::class.java)

    /**
     * 지정된 버전의 인덱스를 생성한다.
     * 이미 동일 이름의 인덱스가 존재하면 예외를 던져 오작동을 방지한다.
     */
    fun createIndex(version: Int): IndexCreationResult {
        val indexName = indexName(version)

        val exists = elasticsearchClient.indices().exists { it.index(indexName) }
        if (exists.value()) {
            throw IllegalStateException("인덱스가 이미 존재합니다: $indexName")
        }

        val templateJson = indexTemplateLoader.load(version)

        try {
            // 템플릿 JSON을 UTF-8 스트림으로 주입해 설정/매핑을 그대로 Elasticsearch에 전달한다.
            val response = elasticsearchClient.indices().create { builder ->
                builder
                    .index(indexName)
                    .withJson(templateJson.byteInputStream(StandardCharsets.UTF_8))
            }

            if (!response.acknowledged()) {
                throw IllegalStateException("Elasticsearch가 인덱스 생성을 확인하지 않았습니다: $indexName")
            }

            logger.info("인덱스 생성 완료: {} (shardsAck={})", indexName, response.shardsAcknowledged())
            return IndexCreationResult(
                indexName = indexName,
                acknowledged = response.acknowledged(),
                shardsAcknowledged = response.shardsAcknowledged()
            )
        } catch (ex: ElasticsearchException) {
            logger.error("인덱스 생성 중 Elasticsearch 예외 발생: {}", ex.message, ex)
            throw ex
        } catch (ex: Exception) {
            logger.error("인덱스 생성 실패: {}", ex.message, ex)
            throw ex
        }
    }

    // 버전 규칙에 맞춰 인덱스 이름을 생성한다.
    private fun indexName(version: Int) = "docs_v$version"
}

/**
 * 인덱스 생성 결과를 표현하는 응답 모델.
 */
data class IndexCreationResult(
    val indexName: String,
    val acknowledged: Boolean,
    val shardsAcknowledged: Boolean
)
