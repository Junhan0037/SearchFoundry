package com.searchfoundry.support.exception

import org.springframework.http.HttpStatus

/**
 * API 오류 코드 정의.
 */
enum class ErrorCode(
    val httpStatus: HttpStatus,
    val message: String
) {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "내부 서버 오류가 발생했습니다.")
}
