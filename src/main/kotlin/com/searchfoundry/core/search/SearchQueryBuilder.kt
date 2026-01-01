package com.searchfoundry.core.search

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.Time
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreQuery
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.RankFeatureQuery
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType
import org.springframework.stereotype.Component

/**
 * 검색/자동완성에 필요한 Elasticsearch 쿼리를 조립한다.
 * - 랭킹 튜닝(최신성/인기도)에 따라 function_score를 포함할지 결정한다.
 * - 필터/멀티매치/랭킹 신호 구성을 분리해 테스트 가능한 형태로 유지한다.
 */
@Component
class SearchQueryBuilder {
    private val defaultRankingTuning = RankingTuning.default()

    /**
     * 검색 요청 파라미터를 기반으로 bool + function_score 쿼리를 생성한다.
     */
    fun buildSearchQuery(request: SearchQuery): Query {
        val baseQuery = buildBaseQuery(request)
        return applyFunctionScore(baseQuery, request.sort, request.rankingTuning)
    }

    /**
     * titleAutocomplete 기반 자동완성 쿼리를 생성한다.
     * - 자동완성은 popularity 가중치만 적용하도록 기본 튜닝을 사용한다.
     */
    fun buildSuggestQuery(request: SuggestQuery): Query {
        val baseQuery = Query.of { query ->
            query.matchPhrasePrefix { prefix ->
                prefix.field("titleAutocomplete")
                    .query(request.query)
                    .maxExpansions(50)
            }
        }

        val boolBuilder = BoolQuery.Builder()
        boolBuilder.must(baseQuery)

        request.category?.let { category ->
            boolBuilder.filter { filter ->
                filter.term { term -> term.field("category").value(category) }
            }
        }

        val boolQuery = Query.of { query -> query.bool(boolBuilder.build()) }
        return applyFunctionScore(boolQuery, SearchSort.POPULARITY, defaultRankingTuning)
    }

    /**
     * multi_match + 필터 + rank_feature should 조건을 조합해 기본 bool 쿼리를 만든다.
     */
    private fun buildBaseQuery(request: SearchQuery): Query {
        val boolBuilder = BoolQuery.Builder()

        boolBuilder.must { must ->
            val multiMatchBuilder = MultiMatchQuery.Builder()
                .query(request.query)
                .type(request.multiMatchType.toTextQueryType())
                .fields("title^4", "summary^2", "body")

            // most_fields에서 점수 쏠림을 완화하기 위해 tie_breaker를 낮게 설정한다.
            if (request.multiMatchType == MultiMatchType.MOST_FIELDS) {
                multiMatchBuilder.tieBreaker(0.2)
            }

            must.multiMatch(multiMatchBuilder.build())
        }

        request.category?.let { category ->
            boolBuilder.filter { filter ->
                filter.term { term -> term.field("category").value(category) }
            }
        }

        if (request.tags.isNotEmpty()) {
            boolBuilder.filter { filter ->
                filter.terms { terms ->
                    terms.field("tags")
                        .terms { values -> values.value(request.tags.map { tag -> FieldValue.of(tag) }) }
                }
            }
        }

        request.author?.let { author ->
            boolBuilder.filter { filter ->
                filter.term { term -> term.field("author").value(author) }
            }
        }

        if (request.publishedFrom != null || request.publishedTo != null) {
            val rangeQuery = Query.of { query ->
                query.range { range ->
                    range.date { date ->
                        date.field("publishedAt")
                        request.publishedFrom?.let { date.gte(it.toString()) }
                        request.publishedTo?.let { date.lte(it.toString()) }
                        date
                    }
                }
            }
            boolBuilder.filter(rangeQuery)
        }

        val popularity = request.rankingTuning.popularity
        if (popularity.enabled && popularity.mode == PopularityMode.RANK_FEATURE) {
            boolBuilder.should { should ->
                should.rankFeature(buildRankFeature(popularity))
            }
        }

        return Query.of { query -> query.bool(boolBuilder.build()) }
    }

    // rank_feature saturation으로 popularityScore를 점수에 반영한다.
    private fun buildRankFeature(popularity: PopularityTuning): RankFeatureQuery =
        RankFeatureQuery.Builder()
            .field("popularityScore")
            .boost(popularity.rankFeatureBoost.toFloat())
            .saturation { saturation -> saturation.pivot(popularity.saturationPivot.toFloat()) }
            .build()

    /**
     * 정렬 모드에 따라 recency/popularity function_score 조합을 적용한다.
     */
    private fun applyFunctionScore(baseQuery: Query, sort: SearchSort, rankingTuning: RankingTuning): Query {
        val functions = when (sort) {
            SearchSort.RELEVANCE -> listOfNotNull(recencyDecayFunction(rankingTuning), popularityBoostFunction(rankingTuning))
            SearchSort.RECENCY -> listOfNotNull(recencyDecayFunction(rankingTuning))
            SearchSort.POPULARITY -> listOfNotNull(popularityBoostFunction(rankingTuning))
        }

        if (functions.isEmpty()) {
            return baseQuery
        }

        val functionScoreQuery = FunctionScoreQuery.Builder()
            .query(baseQuery)
            .functions(functions)
            .scoreMode(rankingTuning.scoreMode)
            .boostMode(rankingTuning.boostMode)
            .build()

        return Query.of { query -> query.functionScore(functionScoreQuery) }
    }

    // 최신성 가중치: origin=now 기준 gauss decay를 적용한다.
    private fun recencyDecayFunction(rankingTuning: RankingTuning): FunctionScore? {
        val recency = rankingTuning.recency
        if (!recency.enabled) {
            return null
        }

        return FunctionScore.of { function ->
            function.gauss { decay ->
                decay.date { date ->
                    date.field("publishedAt")
                        .placement { placement ->
                            placement
                                .origin("now")
                                .scale(Time.of { time -> time.time(recency.scale) })
                                .decay(recency.decay)
                        }
                    date
                }
            }.weight(recency.weight)
        }
    }

    // popularityScore를 field_value_factor로 가중한다.
    private fun popularityBoostFunction(rankingTuning: RankingTuning): FunctionScore? {
        val popularity = rankingTuning.popularity
        if (!popularity.enabled || popularity.mode != PopularityMode.FIELD_VALUE_FACTOR) {
            return null
        }

        return FunctionScore.of { function ->
            function.fieldValueFactor { factor ->
                factor.field("popularityScore")
                    .factor(popularity.factor)
                    .missing(popularity.missing)
                popularity.modifier?.let { modifier -> factor.modifier(modifier) }
                factor // builder를 반환해 NullPointer 방지
            }.weight(popularity.weight)
        }
    }

    private fun MultiMatchType.toTextQueryType(): TextQueryType = when (this) {
        MultiMatchType.BEST_FIELDS -> TextQueryType.BestFields
        MultiMatchType.MOST_FIELDS -> TextQueryType.MostFields
        MultiMatchType.CROSS_FIELDS -> TextQueryType.CrossFields
    }
}
