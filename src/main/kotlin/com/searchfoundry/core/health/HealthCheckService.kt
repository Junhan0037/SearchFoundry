package com.searchfoundry.core.health

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 헬스 상태를 생성하는 유스케이스 서비스.
 */
@Service
class HealthCheckService(
    @Value("\${spring.application.name}") private val applicationName: String
) {
    /**
     * 현재 애플리케이션 이름과 상태, 타임스탬프를 반환한다.
     */
    fun check(): HealthStatus {
        return HealthStatus(
            application = applicationName,
            status = "UP",
            now = Instant.now()
        )
    }
}
