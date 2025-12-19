package com.searchfoundry.support.api

import java.time.Instant

/**
 * API 응답 공통 래퍼.
 * - 에러/성공을 동일한 포맷으로 반환한다.
 */
data class ApiResponse<T>(
    val code: String,
    val message: String,
    val data: T?,
    val timestamp: Instant = Instant.now()
) {
    companion object {
        fun <T> success(data: T, message: String = "success"): ApiResponse<T> =
            ApiResponse(code = "OK", message = message, data = data)

        fun <T> error(code: String, message: String, data: T? = null): ApiResponse<T> =
            ApiResponse(code = code, message = message, data = data)
    }
}
