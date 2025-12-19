package com.searchfoundry.core.health

import java.time.Instant

/**
 * 서비스 헬스 상태를 표현하는 도메인 모델.
 */
data class HealthStatus(
    val application: String,
    val status: String,
    val now: Instant
)
