package com.searchfoundry.api.admin

import com.searchfoundry.core.observability.SearchObservationReport
import com.searchfoundry.core.observability.SearchObservationRequest
import com.searchfoundry.core.observability.SearchObservabilityService
import com.searchfoundry.support.api.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 검색 관측(슬로우로그 + profile) 수집을 트리거하는 Admin API.
 */
@RestController
@RequestMapping("/admin/observability")
@Validated
class ObservabilityAdminController(
    private val searchObservabilityService: SearchObservabilityService
) {

    /**
     * QuerySet 기반으로 profile 검색을 실행하고 슬로우로그 tail을 함께 리포트한다.
     * - 요청 파라미터가 비어 있으면 observability.* 프로퍼티 기본값을 사용한다.
     */
    @PostMapping("/search")
    fun captureSearchObservation(
        @RequestBody @Valid request: SearchObservationRequestDto
    ): ApiResponse<SearchObservationResponse> {
        val report = searchObservabilityService.capture(request.toDomain())
        return ApiResponse.success(SearchObservationResponse.from(report))
    }
}

data class SearchObservationRequestDto(
    @field:NotBlank(message = "datasetId는 필수입니다.")
    val datasetId: String,
    @field:Min(value = 1, message = "topK는 1 이상이어야 합니다.")
    val topK: Int? = null,
    val targetIndex: String? = null,
    val slowlogPath: String? = null,
    @field:Min(value = 1, message = "slowlogTail은 1 이상이어야 합니다.")
    val slowlogTail: Int? = null,
    val includeSlowlog: Boolean = true,
    val includeProfile: Boolean = true
) {
    fun toDomain(): SearchObservationRequest = SearchObservationRequest(
        datasetId = datasetId.trim(),
        topK = topK,
        targetIndex = targetIndex?.trim().takeUnless { it.isNullOrBlank() },
        slowlogPath = slowlogPath?.trim().takeUnless { it.isNullOrBlank() },
        slowlogTail = slowlogTail,
        includeSlowlog = includeSlowlog,
        includeProfile = includeProfile
    )
}

data class SearchObservationResponse(
    val runId: String,
    val datasetId: String,
    val targetIndex: String,
    val topK: Int,
    val totalQueries: Int,
    val profiledQueries: Int,
    val slowlogEntries: Int,
    val observationPath: String,
    val profilePath: String,
    val slowlogPath: String?,
    val summaryPath: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val elapsedMs: Long
) {
    companion object {
        fun from(report: SearchObservationReport): SearchObservationResponse = SearchObservationResponse(
            runId = report.runId,
            datasetId = report.datasetId,
            targetIndex = report.targetIndex,
            topK = report.topK,
            totalQueries = report.totalQueries,
            profiledQueries = report.profiledQueries,
            slowlogEntries = report.slowlogEntries,
            observationPath = report.observationPath.toAbsolutePath().toString(),
            profilePath = report.profilePath.toAbsolutePath().toString(),
            slowlogPath = report.slowlogPath?.toAbsolutePath()?.toString(),
            summaryPath = report.summaryPath.toAbsolutePath().toString(),
            startedAt = report.startedAt,
            completedAt = report.completedAt,
            elapsedMs = report.elapsedMs
        )
    }
}
