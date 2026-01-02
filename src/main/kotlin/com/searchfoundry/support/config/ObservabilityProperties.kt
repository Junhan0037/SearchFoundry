package com.searchfoundry.support.config

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 검색 관측(프로파일/슬로우로그) 실행 시 사용할 기본 설정을 보관한다.
 */
@Validated
@ConfigurationProperties(prefix = "observability")
data class ObservabilityProperties(
    val reportBasePath: String = "reports/observability",
    val slowlogPath: String = "logs/elasticsearch_index_search_slowlog.log",
    @field:Min(1)
    val slowlogTail: Int = 200,
    @field:Min(1)
    val defaultTopK: Int = 10,
    val defaultTargetIndex: String = "docs_read"
)
