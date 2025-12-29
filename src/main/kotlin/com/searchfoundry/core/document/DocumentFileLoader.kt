package com.searchfoundry.core.document

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * JSON 배열 파일을 `Document` 리스트로 로드하는 유틸리티.
 * - 실험/배치 색인 시 동일한 샘플 데이터를 재사용할 때 활용한다.
 */
@Component
class DocumentFileLoader(
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(DocumentFileLoader::class.java)

    /**
     * 주어진 경로의 JSON 배열을 파싱해 Document 리스트로 반환한다.
     * - 파일이 없거나 파싱 실패 시 AppException으로 감싸 호출자에게 전달한다.
     */
    fun load(path: String): List<Document> {
        val filePath: Path = Paths.get(path)
        if (!Files.exists(filePath)) {
            throw AppException(
                ErrorCode.NOT_FOUND,
                "문서 데이터 파일을 찾을 수 없습니다: ${filePath.toAbsolutePath()}",
                filePath.toAbsolutePath().toString()
            )
        }

        return try {
            objectMapper.readValue(filePath.toFile(), object : TypeReference<List<Document>>() {})
                .also { logger.info("문서 데이터 로드 완료(path={}, count={})", filePath.toAbsolutePath(), it.size) }
        } catch (ex: Exception) {
            logger.error("문서 데이터 로드 실패(path={}): {}", filePath.toAbsolutePath(), ex.message, ex)
            throw AppException(
                ErrorCode.INTERNAL_ERROR,
                "문서 데이터 로드 중 오류가 발생했습니다.",
                ex.message ?: "unknown"
            )
        }
    }
}
