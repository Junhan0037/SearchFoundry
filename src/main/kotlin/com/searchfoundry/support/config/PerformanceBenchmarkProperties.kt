package com.searchfoundry.support.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

/**
 * 고정 데이터셋/쿼리셋 기반 검색 성능 벤치마크 기본 설정.
 * - 보고서 저장 경로와 반복 횟수, 비교 대상 리포트 ID를 프로퍼티로 관리한다.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "performance.benchmark")
data class PerformanceBenchmarkProperties(
    @field:NotBlank
    val datasetId: String = "baseline",
    @field:Min(1)
    val topK: Int = 10,
    @field:Min(1)
    val iterations: Int = 3,
    @field:Min(0)
    val warmups: Int = 1,
    @field:NotBlank
    val targetIndex: String = "docs_read",
    @field:NotBlank
    val reportBasePath: String = "reports/performance",
    val reportIdPrefix: String = "perf",
    val baselineReportId: String? = null
)
