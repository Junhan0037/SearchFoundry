package com.searchfoundry.eval.experiment

import com.searchfoundry.core.search.MultiMatchType
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
 * multi_match 타입(best_fields/most_fields/cross_fields) 별로 평가 러너를 실행하는 실험 러너.
 * - targetIndex는 EvaluationRunner에 위임하므로 별도 의존성을 주입하지 않는다.
 */
@Service
class QueryExperimentRunner(
    private val evaluationRunner: EvaluationRunner,
    private val evaluationReportGenerator: EvaluationReportGenerator
) {
    private val logger = LoggerFactory.getLogger(QueryExperimentRunner::class.java)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

    private val defaultCases = listOf(
        QueryExperimentCase(
            name = "best_fields_baseline",
            description = "기본 best_fields: 필드별 최고 점수 기반 baseline",
            multiMatchType = MultiMatchType.BEST_FIELDS
        ),
        QueryExperimentCase(
            name = "most_fields_coverage",
            description = "most_fields: 필드 누적 점수로 분산된 텍스트 커버리지 확인",
            multiMatchType = MultiMatchType.MOST_FIELDS
        ),
        QueryExperimentCase(
            name = "cross_fields_blended",
            description = "cross_fields: 필드 결합으로 띄어쓰기/필드 분산 문제 보정",
            multiMatchType = MultiMatchType.CROSS_FIELDS
        )
    )

    /**
     * 요청된 케이스 집합을 순차 실행하고 평가/리포트 결과를 반환한다.
     */
    fun run(request: QueryExperimentRequest): QueryExperimentSuiteResult {
        val cases = resolveCases(request.caseNames)
        require(cases.isNotEmpty()) { "실행할 쿼리 실험 케이스가 없습니다." }
        require(request.worstQueries > 0) { "worstQueries는 1 이상이어야 합니다." }

        val runId = "query_${timestampFormatter.format(Instant.now())}"
        val startedAt = Instant.now()

        val results = cases.map { case ->
            runSingleCase(
                case = case,
                runId = runId,
                request = request
            )
        }

        return QueryExperimentSuiteResult(
            runId = runId,
            datasetId = request.datasetId,
            topK = request.topK,
            startedAt = startedAt,
            completedAt = Instant.now(),
            results = results
        )
    }

    /**
     * 기본 케이스에서 요청된 이름만 필터링한다. 이름 미지정 시 기본 케이스 전체 실행.
     */
    private fun resolveCases(caseNames: List<String>): List<QueryExperimentCase> {
        if (caseNames.isEmpty()) {
            return defaultCases
        }

        val nameSet = caseNames.toSet()
        val filtered = defaultCases.filter { it.name in nameSet }
        if (filtered.isEmpty()) {
            throw IllegalArgumentException("요청한 케이스 이름이 유효하지 않습니다. 입력=${caseNames.joinToString(",")}")
        }
        return filtered
    }

    /**
     * 단일 multi_match 케이스를 평가 러너로 실행하고 리포트를 생성한다.
     */
    private fun runSingleCase(
        case: QueryExperimentCase,
        runId: String,
        request: QueryExperimentRequest
    ): QueryExperimentResult {
        var evaluationResult: EvaluationRunResult? = null
        var report: EvaluationReport? = null
        var status = QueryExperimentStatus.SUCCESS
        var errorMessage: String? = null

        try {
            evaluationResult = evaluationRunner.run(
                datasetId = request.datasetId,
                topK = request.topK,
                targetIndex = request.targetIndex,
                multiMatchType = case.multiMatchType
            )
            logger.info(
                "쿼리 실험 완료(case={}, multiMatchType={}, dataset={}, topK={}, meanNdcg={})",
                case.name,
                case.multiMatchType,
                request.datasetId,
                request.topK,
                evaluationResult.metricsSummary.meanNdcgAtK
            )

            if (request.generateReport) {
                report = evaluationReportGenerator.generate(
                    runResult = evaluationResult,
                    worstQueriesCount = request.worstQueries,
                    reportIdPrefix = "query-${case.name}-$runId"
                )
            }
        } catch (ex: Exception) {
            status = QueryExperimentStatus.FAILED
            errorMessage = ex.message
            logger.error(
                "쿼리 실험 실패(case={}, type={}, dataset={}, reason={})",
                case.name,
                case.multiMatchType,
                request.datasetId,
                ex.message,
                ex
            )
        }

        return QueryExperimentResult(
            case = case,
            evaluationResult = evaluationResult,
            report = report,
            status = status,
            errorMessage = errorMessage
        )
    }
}
