package com.searchfoundry.eval.experiment

import com.searchfoundry.core.search.MultiMatchType
import com.searchfoundry.eval.EvaluationReport
import com.searchfoundry.eval.EvaluationRunResult
import java.time.Instant

/**
 * multi_match 타입 비교 실험 케이스/결과 모델.
 */
data class QueryExperimentCase(
    val name: String,
    val description: String,
    val multiMatchType: MultiMatchType
) {
    init {
        require(name.isNotBlank()) { "실험 케이스 이름은 비어 있을 수 없습니다." }
    }
}

/**
 * 쿼리 실험 실행 요청 파라미터.
 */
data class QueryExperimentRequest(
    val datasetId: String,
    val topK: Int,
    val worstQueries: Int,
    val targetIndex: String? = null,
    val caseNames: List<String> = emptyList(),
    val generateReport: Boolean = true
)

enum class QueryExperimentStatus {
    SUCCESS,
    FAILED
}

/**
 * 단일 케이스 실행 결과.
 */
data class QueryExperimentResult(
    val case: QueryExperimentCase,
    val evaluationResult: EvaluationRunResult?,
    val report: EvaluationReport?,
    val status: QueryExperimentStatus,
    val errorMessage: String?
)

/**
 * 실험 배치 실행 결과.
 */
data class QueryExperimentSuiteResult(
    val runId: String,
    val datasetId: String,
    val topK: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val results: List<QueryExperimentResult>
) {
    val successCount: Int = results.count { it.status == QueryExperimentStatus.SUCCESS }
}
