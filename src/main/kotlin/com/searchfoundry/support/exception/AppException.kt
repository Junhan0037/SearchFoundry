package com.searchfoundry.support.exception

/**
 * 도메인/애플리케이션 전용 예외.
 */
class AppException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.message,
    val detail: String? = null
) : RuntimeException(message)
