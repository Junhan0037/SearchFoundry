package com.searchfoundry.eval.dataset

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.searchfoundry.support.exception.AppException
import com.searchfoundry.support.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

/**
 * 평가용 QuerySet/JudgementSet JSON 모델 및 로더.
 * - docs/eval/querysets/{datasetId}_queries.json
 * - docs/eval/judgements/{datasetId}_judgements.json
 */
@Component
class EvaluationDatasetLoader(
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(EvaluationDatasetLoader::class.java)
    private val basePath: Path = Paths.get("docs", "eval")

    /**
     * QuerySet/JudgementSet을 로드하고 queryId 기준으로 매핑한다.
     */
    fun load(datasetId: String): EvaluationDataset {
        val queriesPath = basePath.resolve(Paths.get("querysets", "${datasetId}_queries.json"))
        val judgementsPath = basePath.resolve(Paths.get("judgements", "${datasetId}_judgements.json"))

        validateFileExists(queriesPath)
        validateFileExists(judgementsPath)

        val queries = readQueries(queriesPath)
        val judgements = readJudgements(judgementsPath)

        validateUniqueQueryIds(datasetId, queries)
        validateQueryCoverage(datasetId, queries, judgements)

        val groupedJudgements = judgements.groupBy { it.queryId }
        logger.info("평가 데이터셋 로드 완료(datasetId={}, queries={}, judgements={})", datasetId, queries.size, judgements.size)

        return EvaluationDataset(
            datasetId = datasetId,
            queries = queries,
            judgementsByQuery = groupedJudgements
        )
    }

    /**
     * 파일 존재 여부를 먼저 검증해 친절한 에러 메시지를 제공한다.
     */
    private fun validateFileExists(path: Path) {
        if (!Files.exists(path)) {
            throw AppException(
                ErrorCode.NOT_FOUND,
                "평가 데이터 파일을 찾을 수 없습니다: ${path.toAbsolutePath()}",
                path.toAbsolutePath().toString()
            )
        }
    }

    /**
     * QuerySet JSON 파싱.
     */
    private fun readQueries(path: Path): List<EvalQuery> =
        objectMapper.readValue(path.toFile(), object : TypeReference<List<EvalQuery>>() {})

    /**
     * JudgementSet JSON 파싱.
     */
    private fun readJudgements(path: Path): List<Judgement> =
        objectMapper.readValue(path.toFile(), object : TypeReference<List<Judgement>>() {})

    /**
     * QuerySet 내 queryId 중복을 방지해 매칭 오류를 사전에 차단한다.
     */
    private fun validateUniqueQueryIds(datasetId: String, queries: List<EvalQuery>) {
        val duplicates = queries
            .groupBy { it.queryId }
            .filter { it.value.size > 1 }
            .keys
        if (duplicates.isNotEmpty()) {
            throw AppException(
                ErrorCode.BAD_REQUEST,
                "QuerySet에 중복된 queryId가 존재합니다.",
                "datasetId=$datasetId, duplicates=${duplicates.joinToString(",")}"
            )
        }
    }

    /**
     * JudgementSet의 queryId가 QuerySet에 모두 존재하는지 확인한다.
     */
    private fun validateQueryCoverage(datasetId: String, queries: List<EvalQuery>, judgements: List<Judgement>) {
        val queryIds = queries.map { it.queryId }.toSet()
        val missing = judgements.map { it.queryId }.toSet() - queryIds
        if (missing.isNotEmpty()) {
            throw AppException(
                ErrorCode.BAD_REQUEST,
                "JudgementSet에 QuerySet에 없는 queryId가 포함되어 있습니다.",
                "datasetId=$datasetId, missing=${missing.joinToString(",")}"
            )
        }
    }
}

data class EvalQueryFilters(
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val author: String? = null,
    val publishedAtFrom: Instant? = null,
    val publishedAtTo: Instant? = null
)

data class EvalQuery(
    val queryId: String,
    val queryText: String,
    val intent: String,
    val filters: EvalQueryFilters? = null
) {
    init {
        require(queryId.isNotBlank()) { "queryId는 비어 있을 수 없습니다." }
        require(queryText.isNotBlank()) { "queryText는 비어 있을 수 없습니다." }
        require(intent.isNotBlank()) { "intent는 비어 있을 수 없습니다." }
    }
}

data class Judgement(
    val queryId: String,
    val docId: UUID,
    val grade: Int,
    val notes: String? = null
) {
    init {
        require(queryId.isNotBlank()) { "queryId는 비어 있을 수 없습니다." }
        require(grade in 0..3) { "grade는 0~3 사이여야 합니다." }
    }
}

data class EvaluationDataset(
    val datasetId: String,
    val queries: List<EvalQuery>,
    val judgementsByQuery: Map<String, List<Judgement>>
)