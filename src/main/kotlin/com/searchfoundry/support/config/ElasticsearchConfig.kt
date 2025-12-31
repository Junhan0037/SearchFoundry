package com.searchfoundry.support.config

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.transport.rest_client.RestClientTransport
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.elasticsearch.client.RestClient
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import jakarta.validation.constraints.Min
import com.searchfoundry.index.ReindexValidationProperties

/**
 * Elasticsearch 클라이언트 구성을 담당한다.
 * hosts가 빈 리스트로 들어오면 애플리케이션이 기동 시점에 실패하도록 기본 검증을 건다.
 */
@Configuration
@EnableConfigurationProperties(
    value = [ElasticsearchProperties::class, IndexBootstrapProperties::class, ReindexValidationProperties::class]
)
class ElasticsearchConfig {
    private val logger = LoggerFactory.getLogger(ElasticsearchConfig::class.java)

    /**
     * 인증 정보를 포함한 RestClient를 생성한다.
     * - 보안 비활성화 환경에서는 username/password를 비워둔다.
     */
    @Bean
    fun restClient(properties: ElasticsearchProperties): RestClient {
        require(properties.hosts.isNotEmpty()) { "최소 한 개 이상의 Elasticsearch 호스트가 필요합니다." }

        // 여러 Elasticsearch 노드를 한 번에 등록. (클러스터 대응 구조)
        val builder = RestClient.builder(*properties.hosts.map { HttpHost.create(it) }.toTypedArray())

        if (!properties.username.isNullOrBlank()) {
            val credentialsProvider = BasicCredentialsProvider().apply {
                setCredentials(AuthScope.ANY, UsernamePasswordCredentials(properties.username, properties.password))
            }

            // 내부 Apache HTTP Client에 기본 인증 제공자 주입.
            builder.setHttpClientConfigCallback { httpClientBuilder: HttpAsyncClientBuilder ->
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            }
        } else {
            logger.info("Elasticsearch 기본 인증을 사용하지 않고 연결합니다.")
        }

        return builder.build()
    }

    /**
     * 공식 Java API 클라이언트를 구성한다.
     * RestClientTransport가 Jackson 기반으로 JSON 직렬화를 처리한다.
     */
    @Bean
    fun elasticsearchClient(restClient: RestClient): ElasticsearchClient {
        val transport = RestClientTransport(restClient, JacksonJsonpMapper())
        return ElasticsearchClient(transport)
    }
}

/**
 * Elasticsearch 연결 속성을 표현한다.
 */
@Validated
@ConfigurationProperties(prefix = "app.elasticsearch")
data class ElasticsearchProperties(
    val hosts: List<String>,
    val username: String? = null,
    val password: String? = null
)

/**
 * 인덱스 부트스트랩 동작을 제어하는 속성이다.
 * 기본값을 안전하게 false로 두어 의도된 경우에만 생성하도록 한다.
 */
@Validated
@ConfigurationProperties(prefix = "app.index.bootstrap")
data class IndexBootstrapProperties(
    val enabled: Boolean = false,
    @Min(1)
    val version: Int = 1
)
