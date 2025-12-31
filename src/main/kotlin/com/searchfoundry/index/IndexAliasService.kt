package com.searchfoundry.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch.indices.update_aliases.Action
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * docs_read/docs_write alias를 생성하거나 새 인덱스로 스위치하는 역할을 담당한다.
 * 블루그린 시나리오의 첫 단계로, 읽기/쓰기 alias를 함께 이동시켜 일관성을 보장한다.
 */
@Service
class IndexAliasService(
    private val elasticsearchClient: ElasticsearchClient
) {
    private val logger = LoggerFactory.getLogger(IndexAliasService::class.java)

    private val readAlias = "docs_read"
    private val writeAlias = "docs_write"

    /**
     * 주어진 버전의 인덱스(docs_v{version})로 read/write alias를 원자적으로 스위치한다.
     * - 대상 인덱스 존재 여부를 사전 검증한다.
     * - 이전 alias 연결을 모두 제거한 뒤 동일 요청에서 add하여 일관성을 유지한다.
     */
    fun bootstrap(version: Int): AliasBootstrapResult {
        val targetIndex = "docs_v$version"

        return switchToIndex(targetIndex)
    }

    /**
     * 임의의 인덱스로 read/write alias를 원자적으로 스위치한다.
     * - blue/green 전환(롤백 포함)에 사용한다.
     */
    fun switchToIndex(targetIndex: String): AliasBootstrapResult {
        val exists = elasticsearchClient.indices().exists { it.index(targetIndex) }
        if (!exists.value()) {
            throw IllegalStateException("대상 인덱스가 존재하지 않아 alias를 연결할 수 없습니다: $targetIndex")
        }

        try {
            // alias 변경을 하나의 updateAliases로 처리해 읽기/쓰기 전환을 원자적으로 보장한다.
            val actions = listOf(
                // 기존 read alias 제거.
                Action.of { action ->
                    action.remove { remove -> remove.index("*").alias(readAlias) }
                },
                // 기존 write alias 제거.
                Action.of { action ->
                    action.remove { remove -> remove.index("*").alias(writeAlias) }
                },
                // 새 인덱스에 read alias 추가.
                Action.of { action ->
                    action.add { add -> add.index(targetIndex).alias(readAlias) }
                },
                // 새 인덱스에 write alias 추가. (write alias가 여러 인덱스를 가질 수 있지만 쓰기 대상은 단 하나)
                Action.of { action ->
                    action.add { add -> add.index(targetIndex).alias(writeAlias).isWriteIndex(true) }
                }
            )

            // Elasticsearch cluster state를 한 번에 갱신. (중간 상태 없음)
            val response = elasticsearchClient.indices().updateAliases { builder ->
                builder.actions(actions)
            }

            // 클러스터가 alias 변경을 정상 반영했는지 확인.
            if (!response.acknowledged()) {
                throw IllegalStateException("Elasticsearch가 alias 갱신을 확인하지 않았습니다: $targetIndex")
            }

            logger.info(
                "alias 부트스트랩 완료: {} -> {}, {} -> {}",
                readAlias,
                targetIndex,
                writeAlias,
                targetIndex
            )

            return AliasBootstrapResult(
                targetIndex = targetIndex,
                readAlias = readAlias,
                writeAlias = writeAlias
            )
        } catch (ex: ElasticsearchException) {
            logger.error("alias 부트스트랩 중 Elasticsearch 예외 발생: {}", ex.message, ex)
            throw ex
        } catch (ex: Exception) {
            logger.error("alias 부트스트랩 실패: {}", ex.message, ex)
            throw ex
        }
    }

    /**
     * 현재 read/write alias가 가리키는 인덱스를 조회해 롤백/검증 정보로 활용한다.
     */
    fun currentAliasState(): AliasState {
        val response = elasticsearchClient.indices().getAlias { builder ->
            builder
                .name(listOf(readAlias, writeAlias))
                .ignoreUnavailable(true) // alias가 아직 없을 수 있으므로 무시하고 빈 결과 반환.
                .allowNoIndices(true)
        }

        val readTargets = mutableListOf<String>()
        val writeTargets = mutableListOf<String>()

        response.result().forEach { (indexName, indexAliases) ->
            val aliases = indexAliases.aliases()
            aliases?.forEach { (aliasName, aliasDef) ->
                if (aliasName == readAlias) {
                    readTargets += indexName
                }
                if (aliasName == writeAlias && (aliasDef.isWriteIndex() == true)) {
                    writeTargets += indexName
                }
            }
        }

        return AliasState(
            readTargets = readTargets.distinct(),
            writeTargets = writeTargets.distinct()
        )
    }
}

/**
 * alias 부트스트랩 결과를 표현하는 모델.
 */
data class AliasBootstrapResult(
    val targetIndex: String,
    val readAlias: String,
    val writeAlias: String
)

/**
 * read/write alias의 현재 연결 상태.
 */
data class AliasState(
    val readTargets: List<String>,
    val writeTargets: List<String>
)
