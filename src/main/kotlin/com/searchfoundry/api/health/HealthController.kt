package com.searchfoundry.api.health

import com.searchfoundry.core.health.HealthCheckService
import com.searchfoundry.core.health.HealthStatus
import com.searchfoundry.support.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 헬스 체크 엔드포인트.
 */
@RestController
@RequestMapping("/api/health")
class HealthController(
    private val healthCheckService: HealthCheckService
) {
    /**
     * 애플리케이션의 상태를 반환한다.
     */
    @GetMapping
    fun health(): ApiResponse<HealthStatus> {
        val status = healthCheckService.check()
        return ApiResponse.success(status)
    }
}
