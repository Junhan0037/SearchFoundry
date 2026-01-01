package com.searchfoundry.core.search

import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode

/**
 * function_score 기반 최신성/인기도 가중치 튜닝 설정.
 */
data class RankingTuning(
    val recency: RecencyTuning = RecencyTuning(),
    val popularity: PopularityTuning = PopularityTuning(),
    val scoreMode: FunctionScoreMode = FunctionScoreMode.Sum,
    val boostMode: FunctionBoostMode = FunctionBoostMode.Sum
) {
    companion object {
        fun default(): RankingTuning = RankingTuning()
    }
}

/**
 * 최신성 decay 설정.
 */
data class RecencyTuning(
    val enabled: Boolean = true,
    val scale: String = "30d",
    val decay: Double = 0.5,
    val weight: Double = 1.0
) {
    init {
        require(scale.isNotBlank()) { "recency scale은 비어 있을 수 없습니다." }
        require(decay in 0.0..1.0) { "recency decay는 0~1 사이여야 합니다." }
        require(weight > 0) { "recency weight는 0보다 커야 합니다." }
    }
}

/**
 * popularityScore 가중치 적용 방식.
 */
enum class PopularityMode {
    FIELD_VALUE_FACTOR,
    RANK_FEATURE
}

/**
 * popularityScore 가중치 설정.
 * - rank_feature 타입을 field_value_factor/log1p 또는 saturation(rank_feature)로 실험한다.
 */
data class PopularityTuning(
    val enabled: Boolean = true,
    val mode: PopularityMode = PopularityMode.RANK_FEATURE,
    val factor: Double = 1.0,
    val modifier: FieldValueFactorModifier? = null,
    val missing: Double = 0.0,
    val weight: Double = 1.0,
    val saturationPivot: Double = 20.0,
    val rankFeatureBoost: Double = 1.0
) {
    init {
        require(factor >= 0) { "popularity factor는 0 이상이어야 합니다." }
        require(missing >= 0) { "missing 기본값은 0 이상이어야 합니다." }
        require(weight > 0) { "popularity weight는 0보다 커야 합니다." }
        require(saturationPivot > 0) { "saturation pivot은 0보다 커야 합니다." }
        require(rankFeatureBoost > 0) { "rank_feature boost는 0보다 커야 합니다." }
    }
}
