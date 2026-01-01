package com.searchfoundry.eval

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

/**
 * 회귀 평가 기본 설정.
 * - baseline 리포트 ID/데이터셋/topK/worstQueries를 프로퍼티로 관리해 운영 시 일관된 비교를 강제한다.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "eval.regression")
data class RegressionEvaluationProperties(
    @field:NotBlank
    val datasetId: String = "baseline",
    @field:NotBlank
    val baselineReportId: String = "20251226_055824",
    @field:Min(1)
    @field:Max(100)
    val topK: Int = 10,
    @field:Min(1)
    @field:Max(200)
    val worstQueries: Int = 20,
    val targetIndex: String? = null,
    val reportIdPrefix: String = "regression"
)
