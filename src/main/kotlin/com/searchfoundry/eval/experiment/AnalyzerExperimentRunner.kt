package com.searchfoundry.eval.experiment

import co.elastic.clients.elasticsearch.ElasticsearchClient
import com.searchfoundry.core.document.DocumentFileLoader
import com.searchfoundry.core.document.Document
import com.searchfoundry.eval.EvaluationReport
import com.searchfoundry.eval.EvaluationReportGenerator
import com.searchfoundry.eval.EvaluationRunner
import com.searchfoundry.eval.EvaluationRunResult
import com.searchfoundry.index.BulkIndexResult
import com.searchfoundry.index.BulkIndexService
import com.searchfoundry.index.template.IndexTemplateLoader
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * nori 분석기 설정(분해 모드/사용자 사전/동의어) 조합을 자동으로 생성/색인/평가하는 러너.
 */
@Service
class AnalyzerExperimentRunner(
    private val indexTemplateLoader: IndexTemplateLoader,
    private val analyzerIndexTemplateBuilder: AnalyzerIndexTemplateBuilder,
    private val elasticsearchClient: ElasticsearchClient,
    private val bulkIndexService: BulkIndexService,
    private val evaluationRunner: EvaluationRunner,
    private val evaluationReportGenerator: EvaluationReportGenerator,
    private val documentFileLoader: DocumentFileLoader
) {
    private val logger = LoggerFactory.getLogger(AnalyzerExperimentRunner::class.java)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

    private val defaultCases = listOf(
        AnalyzerExperimentCase(
            name = "baseline_mixed_synonym",
            description = "기본값: decompound=mixed + 사용자 사전 + synonym_graph 검색 확장",
            decompoundMode = "mixed",
            useUserDictionary = true,
            useSynonymGraph = true
        ),
        AnalyzerExperimentCase(
            name = "discard_userdict_synonym",
            description = "복합어 분해 최소화(discard) + 사용자 사전 + synonym_graph",
            decompoundMode = "discard",
            useUserDictionary = true,
            useSynonymGraph = true
        ),
        AnalyzerExperimentCase(
            name = "mixed_no_userdict",
            description = "사용자 사전 제외 후 오분해/누락 영향 확인(decompound=mixed)",
            decompoundMode = "mixed",
            useUserDictionary = false,
            useSynonymGraph = true
        ),
        AnalyzerExperimentCase(
            name = "mixed_userdict_no_synonym",
            description = "동의어 비활성화 후 정확도/확장성 영향 확인",
            decompoundMode = "mixed",
            useUserDictionary = true,
            useSynonymGraph = false
        )
    )

    /**
     * 지정된 케이스 집합을 순차 실행하고 Bulk 색인 + 평가 + (선택) 리포트를 반환한다.
     */
    fun run(request: AnalyzerExperimentRequest): AnalyzerExperimentSuiteResult {
        val cases = resolveCases(request.caseNames)
        require(cases.isNotEmpty()) { "실행할 분석기 실험 케이스가 없습니다." }

        val baseTemplate = indexTemplateLoader.load(request.baseTemplateVersion)
        val documents = documentFileLoader.load(request.sampleDataPath)
        val runId = "analyzer_${timestampFormatter.format(Instant.now())}"
        val startedAt = Instant.now()

        val results = cases.map { case ->
            runSingleCase(
                case = case,
                runId = runId,
                baseTemplateJson = baseTemplate,
                documentsPath = request.sampleDataPath,
                documents = documents,
                request = request
            )
        }

        return AnalyzerExperimentSuiteResult(
            runId = runId,
            datasetId = request.datasetId,
            topK = request.topK,
            startedAt = startedAt,
            completedAt = Instant.now(),
            results = results
        )
    }

    /**
     * 요청된 케이스 이름이 없으면 기본 케이스 4종을 모두 실행한다.
     * 이름이 지정되면 해당 이름만 필터링한다.
     */
    private fun resolveCases(caseNames: List<String>): List<AnalyzerExperimentCase> {
        if (caseNames.isEmpty()) {
            return defaultCases
        }

        val caseNameSet = caseNames.toSet()
        val filtered = defaultCases.filter { it.name in caseNameSet }
        if (filtered.isEmpty()) {
            throw IllegalArgumentException("요청한 케이스 이름이 유효하지 않습니다. 입력=${caseNames.joinToString(",")}")
        }
        return filtered
    }

    /**
     * 단일 케이스를 위한 인덱스 생성 → 샘플 데이터 색인 → 평가 러너 실행 → (옵션) 리포트 생성까지 수행한다.
     */
    private fun runSingleCase(
        case: AnalyzerExperimentCase,
        runId: String,
        baseTemplateJson: String,
        documentsPath: String,
        documents: List<Document>,
        request: AnalyzerExperimentRequest
    ): AnalyzerExperimentResult {
        val indexName = "docs_exp_${case.name}_${runId}"
        var createdIndex = false
        var cleanedUp = false

        var bulkIndexResult: BulkIndexResult? = null
        var evaluationResult: EvaluationRunResult? = null
        var report: EvaluationReport? = null
        var status = AnalyzerExperimentStatus.SUCCESS
        var errorMessage: String? = null

        try {
            val templateJson = analyzerIndexTemplateBuilder.buildTemplate(baseTemplateJson, case)
            createIndex(indexName, templateJson)
            createdIndex = true

            bulkIndexResult = bulkIndexService.bulkIndex(documents, targetAlias = indexName)
            logger.info(
                "분석기 실험 색인 완료(case={}, index={}, documents={}, tookMs={})",
                case.name,
                indexName,
                bulkIndexResult.total,
                bulkIndexResult.tookMs
            )

            evaluationResult = evaluationRunner.run(request.datasetId, request.topK, indexName)
            logger.info(
                "분석기 실험 평가 완료(case={}, index={}, dataset={}, topK={}, meanNdcg={})",
                case.name,
                indexName,
                request.datasetId,
                request.topK,
                evaluationResult.metricsSummary.meanNdcgAtK
            )

            if (request.generateReport) {
                report = evaluationReportGenerator.generate(
                    evaluationResult,
                    request.worstQueries,
                    "analyzer-${case.name}"
                )
            }
        } catch (ex: Exception) {
            status = AnalyzerExperimentStatus.FAILED
            errorMessage = ex.message
            logger.error(
                "분석기 실험 실패(case={}, index={}, data={}, reason={})",
                case.name,
                indexName,
                documentsPath,
                ex.message,
                ex
            )
        } finally {
            if (request.cleanupAfterRun && createdIndex) {
                deleteIndexQuietly(indexName)
                cleanedUp = true
            }
        }

        return AnalyzerExperimentResult(
            case = case,
            indexName = indexName,
            bulkIndexResult = bulkIndexResult,
            evaluationResult = evaluationResult,
            report = report,
            status = status,
            cleanedUp = cleanedUp,
            errorMessage = errorMessage
        )
    }

    /**
     * 실험용 인덱스를 생성한다.
     */
    private fun createIndex(indexName: String, templateJson: String) {
        if (elasticsearchClient.indices().exists { it.index(indexName) }.value()) {
            throw IllegalStateException("이미 동일한 이름의 실험 인덱스가 존재합니다(index=$indexName)")
        }

        val response = elasticsearchClient.indices().create { builder ->
            builder
                .index(indexName)
                .withJson(templateJson.byteInputStream(StandardCharsets.UTF_8))
        }

        if (!response.acknowledged()) {
            throw IllegalStateException("Elasticsearch가 인덱스 생성 요청을 승인하지 않았습니다(index=$indexName)")
        }
    }

    /**
     * cleanup 옵션 활성화 시 실험용 인덱스를 조용히 삭제한다.
     */
    private fun deleteIndexQuietly(indexName: String) {
        try {
            if (elasticsearchClient.indices().exists { it.index(indexName) }.value()) {
                elasticsearchClient.indices().delete { it.index(indexName) }
                logger.info("실험 인덱스를 정리했습니다(index={})", indexName)
            }
        } catch (ex: Exception) {
            logger.warn("실험 인덱스 삭제 실패(index={}): {}", indexName, ex.message)
        }
    }
}
