package com.searchfoundry.core.search

import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SearchQueryBuilderTest {

    private val builder = SearchQueryBuilder()

    @Test
    fun `필터와 rank_feature가 bool 쿼리와 function_score에 반영된다`() {
        val rankingTuning = RankingTuning(
            recency = RecencyTuning(enabled = true, scale = "14d", decay = 0.4, weight = 2.0),
            popularity = PopularityTuning(
                enabled = true,
                mode = PopularityMode.RANK_FEATURE,
                saturationPivot = 10.0,
                rankFeatureBoost = 1.2,
                weight = 1.0
            )
        )
        val request = SearchQuery(
            query = "쿠버네티스 로그 수집",
            category = "devops",
            tags = listOf("observability", "kafka"),
            author = "alice",
            publishedFrom = Instant.parse("2024-01-01T00:00:00Z"),
            publishedTo = Instant.parse("2024-02-01T00:00:00Z"),
            sort = SearchSort.RELEVANCE,
            multiMatchType = MultiMatchType.MOST_FIELDS,
            page = 0,
            size = 10,
            targetIndex = "docs_v1",
            rankingTuning = rankingTuning
        )

        val query = builder.buildSearchQuery(request)
        val functionScoreQuery = query.functionScore()
        assertNotNull(functionScoreQuery, "function_score 쿼리가 생성되어야 합니다.")

        val boolQuery = functionScoreQuery?.query()?.bool()
        assertNotNull(boolQuery, "function_score 내부 bool 쿼리가 필요합니다.")

        val multiMatch = boolQuery!!.must().first().multiMatch()
        assertEquals(TextQueryType.MostFields, multiMatch.type())
        assertEquals(listOf("title^4", "summary^2", "body"), multiMatch.fields())
        assertEquals(0.2, multiMatch.tieBreaker())

        // 카테고리/태그/작성자/기간 필터가 모두 존재한다.
        assertEquals(4, boolQuery.filter().size)
        val rankFeature = boolQuery.should().first().rankFeature()
        assertEquals("popularityScore", rankFeature?.field())
        assertEquals(1.2f, rankFeature?.boost())
        assertEquals(10.0f, rankFeature?.saturation()?.pivot())

        // recency function_score만 적용되고 scoreMode/boostMode 설정이 유지된다.
        assertEquals(1, functionScoreQuery.functions().size)
        assertNotNull(functionScoreQuery.functions().first().gauss())
        assertEquals(FunctionScoreMode.Sum, functionScoreQuery.scoreMode())
        assertEquals(FunctionBoostMode.Sum, functionScoreQuery.boostMode())
    }

    @Test
    fun `popularity field_value_factor가 설정되면 function_score에 반영된다`() {
        val rankingTuning = RankingTuning(
            recency = RecencyTuning(enabled = false, scale = "7d", decay = 0.5, weight = 1.0),
            popularity = PopularityTuning(
                enabled = true,
                mode = PopularityMode.FIELD_VALUE_FACTOR,
                factor = 2.0,
                modifier = FieldValueFactorModifier.Log1p,
                missing = 0.5,
                weight = 1.5
            ),
            scoreMode = FunctionScoreMode.Multiply,
            boostMode = FunctionBoostMode.Sum
        )
        val request = SearchQuery(
            query = "observability",
            category = null,
            tags = emptyList(),
            author = null,
            publishedFrom = null,
            publishedTo = null,
            sort = SearchSort.POPULARITY,
            multiMatchType = MultiMatchType.BEST_FIELDS,
            page = 0,
            size = 5,
            rankingTuning = rankingTuning
        )

        val query = builder.buildSearchQuery(request)
        val functionScoreQuery = requireNotNull(query.functionScore()) { "popularity 전용 function_score가 생성되어야 합니다." }
        val functionScore = requireNotNull(functionScoreQuery.functions().firstOrNull())

        val fieldValueFactor = functionScore.fieldValueFactor()
        assertNotNull(fieldValueFactor, "popularity field_value_factor가 설정되어야 합니다.")
        assertEquals("popularityScore", fieldValueFactor?.field())
        assertEquals(2.0, fieldValueFactor?.factor())
        assertEquals(FieldValueFactorModifier.Log1p, fieldValueFactor?.modifier())
        assertEquals(0.5, fieldValueFactor?.missing())
        assertEquals(1.5, functionScore.weight())
        assertEquals(FunctionScoreMode.Multiply, functionScoreQuery.scoreMode())
    }
}
