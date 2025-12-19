package com.searchfoundry.support.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 요청 단위 로깅 필터.
 * - 요청 ID를 생성해 MDC에 담고, 처리 시간과 함께 기록한다.
 */
@Component
class RequestLoggingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

    /**
     * 요청 전후로 로깅을 수행한다.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestId = UUID.randomUUID().toString()
        val startMillis = System.currentTimeMillis()
        MDC.put("requestId", requestId)
        try {
            log.info(
                "incoming-request method={} uri={} clientIp={} requestId={}",
                request.method,
                request.requestURI,
                request.remoteAddr,
                requestId
            )
            filterChain.doFilter(request, response)
        } finally {
            val duration = System.currentTimeMillis() - startMillis
            log.info(
                "request-complete method={} uri={} status={} durationMs={} requestId={}",
                request.method,
                request.requestURI,
                response.status,
                duration,
                requestId
            )
            MDC.remove("requestId")
        }
    }
}
