package com.searchfoundry.support.exception

import com.searchfoundry.support.api.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * API 전역 예외 처리기.
 * - 도메인 예외와 일반 예외를 공통 응답 포맷으로 변환한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * 도메인 예외를 처리한다.
     */
    @ExceptionHandler(AppException::class)
    fun handleAppException(ex: AppException): ResponseEntity<ApiResponse<Nothing>> {
        val body = ApiResponse.error<Nothing>(
            code = ex.errorCode.name,
            message = ex.message
        )
        log.warn("app-exception code={} message={} detail={}", ex.errorCode, ex.message, ex.detail)
        return ResponseEntity.status(ex.errorCode.httpStatus).body(body)
    }

    /**
     * 요청 검증 실패를 처리한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(ex: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val message = ex.bindingResult.fieldErrors.joinToString(", ") { "${it.field}:${it.defaultMessage}" }
        val body = ApiResponse.error<Nothing>(code = ErrorCode.BAD_REQUEST.name, message = message)
        log.warn("validation-exception {}", message)
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus).body(body)
    }

    /**
     * 예상치 못한 예외를 처리한다.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        val body = ApiResponse.error<Nothing>(
            code = ErrorCode.INTERNAL_ERROR.name,
            message = ErrorCode.INTERNAL_ERROR.message
        )
        log.error("unexpected-exception", ex)
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus).body(body)
    }
}
