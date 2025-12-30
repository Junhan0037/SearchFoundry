package com.searchfoundry.eval.experiment

import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode
import com.searchfoundry.core.search.MultiMatchType
import com.searchfoundry.core.search.PopularityMode
import com.searchfoundry.core.search.PopularityTuning
import com.searchfoundry.core.search.RankingTuning
import com.searchfoundry.core.search.RecencyTuning
import com.searchfoundry.eval.EvaluationReport
import com.searchfoundry.eval.EvaluationRunResult
import java.time.Instant

/**
 * function_score 기반 랭킹 실험 케이스/결과 모델.
 */
data class RankingExperimentCase(
    val name: String,
    val description: String,
    val recencyScale: String,
    val recencyDecay: Double,
    val recencyWeight: Double,
    val popularityMode: PopularityMode,
    val popularityFactor: Double,
    val popularityModifier: FieldValueFactorModifier? = null,
    val popularityWeight: Double,
    val saturationPivot: Double,
    val rankFeatureBoost: Double,
    val scoreMode: FunctionScoreMode,
    val boostMode: FunctionBoostMode
) {
    init {
        require(name.isNotBlank()) { "실험 케이스 이름은 비어 있을 수 없습니다." }
    }

    /**
     * 케이스 파라미터를 DocumentSearchService에서 소화 가능한 RankingTuning으로 변환한다.
     */
    fun toRankingTuning(): RankingTuning = RankingTuning(
        recency = RecencyTuning(
            scale = recencyScale,
            decay = recencyDecay,
            weight = recencyWeight
        ),
        popularity = PopularityTuning(
            mode = popularityMode,
            factor = popularityFactor,
            modifier = popularityModifier,
            weight = popularityWeight,
            saturationPivot = saturationPivot,
            rankFeatureBoost = rankFeatureBoost
        ),
        scoreMode = scoreMode,
        boostMode = boostMode
    )
}

/**
 * 랭킹 실험 실행 요청 파라미터.
 */
data class RankingExperimentRequest(
    val datasetId: String,
    val topK: Int,
    val worstQueries: Int,
    val targetIndex: String? = null,
    val caseNames: List<String> = emptyList(),
    val multiMatchType: MultiMatchType = MultiMatchType.BEST_FIELDS,
    val generateReport: Boolean = true
)

enum class RankingExperimentStatus {
    SUCCESS,
    FAILED
}

/**
 * 단일 랭킹 케이스 실행 결과.
 */
data class RankingExperimentResult(
    val case: RankingExperimentCase,
    val evaluationResult: EvaluationRunResult?,
    val report: EvaluationReport?,
    val status: RankingExperimentStatus,
    val errorMessage: String?
)

/**
 * 랭킹 실험 배치 실행 결과.
 */
data class RankingExperimentSuiteResult(
    val runId: String,
    val datasetId: String,
    val topK: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val results: List<RankingExperimentResult>
) {
    val successCount: Int = results.count { it.status == RankingExperimentStatus.SUCCESS }
}
