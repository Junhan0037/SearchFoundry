package com.searchfoundry.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.GetResponse
import co.elastic.clients.elasticsearch.cat.indices.IndicesRecord
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.searchfoundry.core.document.Document
import com.searchfoundry.core.search.DocumentSearchService
import com.searchfoundry.core.search.SearchQuery
import com.searchfoundry.core.search.SearchQueryBuilder
import com.searchfoundry.core.search.SearchSort
import com.searchfoundry.index.template.IndexTemplateLoader
import java.time.Instant
import java.util.UUID
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.DefaultResourceLoader
import org.testcontainers.elasticsearch.ElasticsearchContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile

/**
 * 실제 Elasticsearch(Testcontainers)와 통신하며 인덱스 생성→색인→검색→reindex 흐름을 검증하는 통합 테스트.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElasticsearchIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        private val elasticsearch: ElasticsearchContainer = ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3")
        ).apply {
            withEnv("xpack.security.enabled", "false")
            withEnv("discovery.type", "single-node")
            withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            // nori 플러그인을 사전 설치해 인덱스 템플릿 요구사항을 충족한다.
            withCommand("sh", "-c", "bin/elasticsearch-plugin install --batch analysis-nori && /usr/local/bin/docker-entrypoint.sh elasticsearch")
            withCopyFileToContainer(
                MountableFile.forClasspathResource("analysis/userdict_ko.txt"),
                "/usr/share/elasticsearch/config/analysis/userdict_ko.txt"
            )
            withCopyFileToContainer(
                MountableFile.forClasspathResource("analysis/synonyms_ko.txt"),
                "/usr/share/elasticsearch/config/analysis/synonyms_ko.txt"
            )
        }
    }

    private lateinit var restClient: RestClient
    private lateinit var client: ElasticsearchClient
    private lateinit var indexCreationService: IndexCreationService
    private lateinit var aliasService: IndexAliasService
    private lateinit var bulkIndexService: BulkIndexService
    private lateinit var searchService: DocumentSearchService
    private lateinit var reindexService: BlueGreenReindexService

    @BeforeAll
    fun setUp() {
        restClient = RestClient.builder(HttpHost.create(elasticsearch.httpHostAddress)).build()
        val objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val transport = RestClientTransport(restClient, JacksonJsonpMapper(objectMapper))
        client = ElasticsearchClient(transport)

        val templateLoader = IndexTemplateLoader(DefaultResourceLoader())
        indexCreationService = IndexCreationService(client, templateLoader)
        aliasService = IndexAliasService(client)
        bulkIndexService = BulkIndexService(client)
        val queryBuilder = SearchQueryBuilder()
        searchService = DocumentSearchService(client, queryBuilder)

        val indexReader = ElasticsearchReindexIndexReader(client)
        val searchPort = DocumentSearchValidationPort(searchService)
        val validationProperties = ReindexValidationProperties(
            enableCountValidation = true,
            enableSampleQueryValidation = false,
            enableHashValidation = false
        )
        val validationService = ReindexValidationService(indexReader, searchPort, validationProperties)
        val retentionLogger = ReindexRetentionLogger()
        reindexService = BlueGreenReindexService(
            client,
            indexCreationService,
            aliasService,
            retentionLogger,
            validationService
        )
    }

    @AfterAll
    fun tearDown() {
        if (::restClient.isInitialized) {
            restClient.close()
        }
    }

    @BeforeEach
    fun cleanIndexes() {
        // 테스트 간 상태 격리를 위해 기존 docs_v* 인덱스를 모두 제거한다.
        val indices: List<IndicesRecord> = client.cat().indices { builder ->
            builder.index("docs_v*")
        }.valueBody()
        val indexNames = indices.mapNotNull { it.index() }.distinct()
        if (indexNames.isNotEmpty()) {
            client.indices().delete { builder ->
                builder.index(indexNames).ignoreUnavailable(true)
            }
        }
    }

    @Test
    fun `인덱스 생성과 검색이 end-to-end로 동작한다`() {
        val documents = seedBaseIndex()

        val searchResult = searchService.search(
            SearchQuery(
                query = "쿠버네티스",
                category = null,
                tags = emptyList(),
                author = null,
                publishedFrom = null,
                publishedTo = null,
                sort = SearchSort.RELEVANCE,
                page = 0,
                size = 5
            )
        )

        assertTrue(searchResult.total >= 1)
        assertTrue(searchResult.hits.any { it.document.title.contains("쿠버네티스") })

        val aliasState = aliasService.currentAliasState()
        assertEquals(listOf("docs_v1"), aliasState.readTargets)
        assertEquals(listOf("docs_v1"), aliasState.writeTargets)

        // 인덱스 내부 문서가 제대로 저장되었는지 확인한다.
        val stored: GetResponse<Document> = client.get({ b -> b.index("docs_v1").id(documents.first().id.toString()) }, Document::class.java)
        assertTrue(stored.found())
    }

    @Test
    fun `블루그린 reindex 후 alias가 새 인덱스를 가리키고 검색이 유지된다`() {
        val documents = seedBaseIndex()

        val reindexResult = reindexService.reindex(
            BlueGreenReindexRequest(
                sourceVersion = 1,
                targetVersion = 2,
                validationOptions = ReindexValidationOptions(
                    enableCountValidation = true,
                    enableSampleQueryValidation = false,
                    enableHashValidation = false
                )
            )
        )

        assertEquals(documents.size.toLong(), reindexResult.targetCount)
        assertEquals(listOf("docs_v2"), reindexResult.aliasAfter.readTargets)
        assertEquals(listOf("docs_v2"), reindexResult.aliasAfter.writeTargets)

        val searchResult = searchService.search(
            SearchQuery(
                query = "메시징",
                category = null,
                tags = listOf("kafka"),
                author = null,
                publishedFrom = null,
                publishedTo = null,
                sort = SearchSort.RELEVANCE,
                page = 0,
                size = 5
            )
        )

        assertTrue(searchResult.hits.any { it.document.title.contains("메시징") })
    }

    /**
     * docs_v1 인덱스를 생성하고 alias를 부트스트랩한 뒤 샘플 문서를 색인한다.
     */
    private fun seedBaseIndex(): List<Document> {
        indexCreationService.createIndex(1)
        aliasService.bootstrap(1)

        val documents = listOf(
            document(
                idSeed = "doc-1",
                title = "쿠버네티스 로그 수집 파이프라인",
                summary = "쿠버네티스와 EFK를 활용한 로그 수집",
                body = "쿠버네티스 환경에서 Fluent Bit와 Elasticsearch로 로그 수집 파이프라인을 구성합니다.",
                tags = listOf("kubernetes", "observability"),
                category = "devops"
            ),
            document(
                idSeed = "doc-2",
                title = "카프카 기반 실시간 메시징",
                summary = "카프카 토픽 설계와 파티셔닝 전략",
                body = "카프카를 활용한 메시징 설계와 컨슈머 그룹 운영 노하우를 다룹니다.",
                tags = listOf("kafka", "streaming"),
                category = "backend"
            ),
            document(
                idSeed = "doc-3",
                title = "엘라스틱서치 쿼리 튜닝",
                summary = "nori 분석기와 function_score를 이용한 한글 검색 튜닝",
                body = "동의어와 랭킹 튜닝으로 nDCG를 개선한 사례를 설명합니다.",
                tags = listOf("elasticsearch", "search"),
                category = "search"
            )
        )

        val bulkResult = bulkIndexService.bulkIndex(documents)
        assertEquals(documents.size, bulkResult.success, "Bulk 색인 실패: ${bulkResult.failures}")
        client.indices().refresh { it.index(listOf("docs_v1")) }
        return documents
    }

    private fun document(
        idSeed: String,
        title: String,
        summary: String,
        body: String,
        tags: List<String>,
        category: String
    ): Document = Document(
        id = UUID.nameUUIDFromBytes(idSeed.toByteArray()),
        title = title,
        summary = summary,
        body = body,
        tags = tags,
        category = category,
        author = "tester",
        publishedAt = Instant.parse("2024-01-01T00:00:00Z"),
        popularityScore = 1.0
    )
}
