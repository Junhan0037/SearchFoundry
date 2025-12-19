package com.searchfoundry.api.admin

import com.searchfoundry.index.IndexCreationService
import com.searchfoundry.index.IndexCreationResult
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Elasticsearch 인덱스 생성/관리용 Admin API.
 */
@RestController
@RequestMapping("/admin/index")
@Validated
class IndexAdminController(
    private val indexCreationService: IndexCreationService
) {

    /**
     * 버전 파라미터를 받아 docs_v{version} 인덱스를 생성한다.
     */
    @PostMapping("/create")
    fun createIndex(
        @RequestParam(name = "version", defaultValue = "1") @Min(1) version: Int
    ): IndexCreateResponse {
        val result = indexCreationService.createIndex(version)
        return IndexCreateResponse.from(result)
    }
}

/**
 * 인덱스 생성 결과를 전달하는 응답 DTO.
 */
data class IndexCreateResponse(
    val indexName: String,
    val acknowledged: Boolean,
    val shardsAcknowledged: Boolean
) {
    companion object {
        fun from(result: IndexCreationResult) = IndexCreateResponse(
            indexName = result.indexName,
            acknowledged = result.acknowledged,
            shardsAcknowledged = result.shardsAcknowledged
        )
    }
}
