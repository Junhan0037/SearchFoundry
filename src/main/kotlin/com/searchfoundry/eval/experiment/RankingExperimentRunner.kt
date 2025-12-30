package com.searchfoundry.eval.experiment

import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode
import com.searchfoundry.core.search.PopularityMode
import com.searchfoundry.eval.EvaluationReport
import com.searchfoundry.eval.EvaluationReportGenerator
import com.searchfoundry.eval.EvaluationRunner
import com.searchfoundry.eval.EvaluationRunResult
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * function_score(최신성 + popularityScore) 랭킹 튜닝 실험 러너.
 * - recency scale/decay/weight와 popularity 가중치(field_value_factor vs rank_feature)를 묶어서 평가한다.
 */
@Service
class RankingExperimentRunner(
    private val evaluationRunner: EvaluationRunner,
    private val evaluationReportGenerator: EvaluationReportGenerator
) {
    private val logger = LoggerFactory.getLogger(RankingExperimentRunner::class.java)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

    private val defaultCases = listOf(
        RankingExperimentCase(
            name = "baseline_sum",
            description = "기본 sum: recency 30d/decay=0.5 + popularity factor=1.0",
            recencyScale = "30d",
            recencyDecay = 0.5,
            recencyWeight = 1.0,
            popularityMode = PopularityMode.FIELD_VALUE_FACTOR,
            popularityFactor = 1.0,
            popularityModifier = null,
            popularityWeight = 1.0,
            saturationPivot = 20.0,
            rankFeatureBoost = 1.0,
            scoreMode = FunctionScoreMode.Sum,
            boostMode = FunctionBoostMode.Sum
        ),
        RankingExperimentCase(
            name = "recency_biased_14d",
            description = "최신성 편향: scale=14d/decay=0.7, recency weight=1.4, popularity factor=0.8",
            recencyScale = "14d",
            recencyDecay = 0.7,
            recencyWeight = 1.4,
            popularityMode = PopularityMode.FIELD_VALUE_FACTOR,
            popularityFactor = 0.8,
            popularityModifier = null,
            popularityWeight = 0.8,
            saturationPivot = 20.0,
            rankFeatureBoost = 1.0,
            scoreMode = FunctionScoreMode.Sum,
            boostMode = FunctionBoostMode.Sum
        ),
        RankingExperimentCase(
            name = "popularity_log1p_capped",
            description = "인기도 우선: log1p 압축 + weight=1.6, recency 완만(scale=45d)",
            recencyScale = "45d",
            recencyDecay = 0.5,
            recencyWeight = 0.8,
            popularityMode = PopularityMode.FIELD_VALUE_FACTOR,
            popularityFactor = 1.2,
            popularityModifier = FieldValueFactorModifier.Log1p,
            popularityWeight = 1.6,
            saturationPivot = 20.0,
            rankFeatureBoost = 1.0,
            scoreMode = FunctionScoreMode.Sum,
            boostMode = FunctionBoostMode.Sum
        ),
        RankingExperimentCase(
            name = "rank_feature_saturation",
            description = "rank_feature saturation + recency 21d, pivot=15, boost=1.2",
            recencyScale = "21d",
            recencyDecay = 0.55,
            recencyWeight = 1.0,
            popularityMode = PopularityMode.RANK_FEATURE,
            popularityFactor = 1.0,
            popularityModifier = null,
            popularityWeight = 1.0,
            saturationPivot = 15.0,
            rankFeatureBoost = 1.2,
            scoreMode = FunctionScoreMode.Sum,
            boostMode = FunctionBoostMode.Sum
        )
    )

    /**
     * 요청된 케이스들을 순차 실행해 평가/리포트를 생성한다.
     */
    fun run(request: RankingExperimentRequest): RankingExperimentSuiteResult {
        val cases = resolveCases(request.caseNames)
        require(cases.isNotEmpty()) { "실행할 랭킹 실험 케이스가 없습니다." }
        require(request.topK > 0) { "topK는 1 이상이어야 합니다." }
        require(request.worstQueries > 0) { "worstQueries는 1 이상이어야 합니다." }

        val runId = "ranking_${timestampFormatter.format(Instant.now())}"
        val startedAt = Instant.now()

        val results = cases.map { case ->
            runSingleCase(
                case = case,
                runId = runId,
                request = request
            )
        }

        return RankingExperimentSuiteResult(
            runId = runId,
            datasetId = request.datasetId,
            topK = request.topK,
            startedAt = startedAt,
            completedAt = Instant.now(),
            results = results
        )
    }

    /**
     * 케이스 이름을 지정하면 해당 케이스만 실행하고, 없으면 기본 케이스 전체를 실행한다.
     */
    private fun resolveCases(caseNames: List<String>): List<RankingExperimentCase> {
        if (caseNames.isEmpty()) {
            return defaultCases
        }

        val nameSet = caseNames.toSet()
        val filtered = defaultCases.filter { it.name in nameSet }
        if (filtered.isEmpty()) {
            throw IllegalArgumentException("요청한 랭킹 케이스 이름이 유효하지 않습니다. 입력=${caseNames.joinToString(",")}")
        }
        return filtered
    }

    /**
     * 단일 랭킹 케이스 실행: ranking tuning 적용 → 평가 → (옵션) 리포트 생성.
     */
    private fun runSingleCase(
        case: RankingExperimentCase,
        runId: String,
        request: RankingExperimentRequest
    ): RankingExperimentResult {
        var evaluationResult: EvaluationRunResult? = null
        var report: EvaluationReport? = null
        var status = RankingExperimentStatus.SUCCESS
        var errorMessage: String? = null

        try {
            val rankingTuning = case.toRankingTuning()
            evaluationResult = evaluationRunner.run(
                datasetId = request.datasetId,
                topK = request.topK,
                targetIndex = request.targetIndex,
                multiMatchType = request.multiMatchType,
                rankingTuning = rankingTuning
            )
            logger.info(
                "랭킹 실험 완료(case={}, mode={}, dataset={}, topK={}, meanNdcg={})",
                case.name,
                case.popularityMode,
                request.datasetId,
                request.topK,
                evaluationResult.metricsSummary.meanNdcgAtK
            )

            if (request.generateReport) {
                report = evaluationReportGenerator.generate(
                    runResult = evaluationResult,
                    worstQueriesCount = request.worstQueries,
                    reportIdPrefix = "ranking-${case.name}-$runId"
                )
            }
        } catch (ex: Exception) {
            status = RankingExperimentStatus.FAILED
            errorMessage = ex.message
            logger.error(
                "랭킹 실험 실패(case={}, dataset={}, reason={})",
                case.name,
                request.datasetId,
                ex.message,
                ex
            )
        }

        return RankingExperimentResult(
            case = case,
            evaluationResult = evaluationResult,
            report = report,
            status = status,
            errorMessage = errorMessage
        )
    }
}
