package com.searchfoundry.eval.experiment

import com.searchfoundry.eval.EvaluationReport
import com.searchfoundry.eval.EvaluationRunResult
import com.searchfoundry.index.BulkIndexResult
import java.time.Instant

/**
 * nori 분석기 실험 케이스/실행 결과 모델 정의.
 */
data class AnalyzerExperimentCase(
    val name: String,
    val description: String,
    val decompoundMode: String,
    val useUserDictionary: Boolean,
    val useSynonymGraph: Boolean
) {
    init {
        require(name.isNotBlank()) { "실험 케이스 이름은 비어 있을 수 없습니다." }
        require(decompoundMode in setOf("none", "discard", "mixed")) {
            "decompoundMode는 none/discard/mixed 중 하나여야 합니다."
        }
    }
}

/**
 * 실험 실행 요청 모델.
 */
data class AnalyzerExperimentRequest(
    val datasetId: String,
    val topK: Int,
    val worstQueries: Int,
    val baseTemplateVersion: Int,
    val sampleDataPath: String,
    val caseNames: List<String> = emptyList(),
    val cleanupAfterRun: Boolean = true,
    val generateReport: Boolean = true
)

enum class AnalyzerExperimentStatus {
    SUCCESS,
    FAILED
}

/**
 * 단일 케이스 실행 결과.
 */
data class AnalyzerExperimentResult(
    val case: AnalyzerExperimentCase,
    val indexName: String,
    val bulkIndexResult: BulkIndexResult?,
    val evaluationResult: EvaluationRunResult?,
    val report: EvaluationReport?,
    val status: AnalyzerExperimentStatus,
    val cleanedUp: Boolean,
    val errorMessage: String?
)

/**
 * 실험 배치 실행 결과.
 */
data class AnalyzerExperimentSuiteResult(
    val runId: String,
    val datasetId: String,
    val topK: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val results: List<AnalyzerExperimentResult>
) {
    val successCount: Int = results.count { it.status == AnalyzerExperimentStatus.SUCCESS }
}
