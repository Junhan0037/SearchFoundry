package com.searchfoundry.index

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * alias 스위치 직후 문제가 발생했을 때 이전 인덱스로 즉시 복구하는 롤백 서비스.
 * - 현재 alias가 요청된 현재 인덱스를 바라보고 있는지 검증해 오작동을 방지한다.
 * - read/write alias를 하나의 updateAliases 호출로 이동시켜 일관성을 유지한다.
 */
@Service
class ReindexRollbackService(
    private val indexAliasService: IndexAliasService
) {
    private val logger = LoggerFactory.getLogger(ReindexRollbackService::class.java)

    /**
     * 블루그린 스위치 직후 이전 인덱스로 원자적 롤백을 수행한다.
     * - alias가 기대한 현재 인덱스를 바라보지 않으면 중단해 잘못된 롤백을 막는다.
     */
    fun rollback(request: ReindexRollbackCommand): ReindexRollbackResult {
        val beforeAlias = indexAliasService.currentAliasState()
        validateCurrentAlias(beforeAlias, request.currentIndex)

        // read/write alias를 이전 인덱스로 이동한다. (switchToIndex 내부에서 인덱스 존재 여부 재검증)
        val switchResult = indexAliasService.switchToIndex(request.rollbackToIndex)
        val afterAlias = indexAliasService.currentAliasState()

        logger.warn(
            "alias 롤백 완료(current={}, rollbackTo={}, beforeRead={}, beforeWrite={}, afterRead={}, afterWrite={})",
            request.currentIndex,
            request.rollbackToIndex,
            beforeAlias.readTargets,
            beforeAlias.writeTargets,
            afterAlias.readTargets,
            afterAlias.writeTargets
        )

        return ReindexRollbackResult(
            rollbackToIndex = switchResult.targetIndex,
            currentIndex = request.currentIndex,
            aliasBefore = beforeAlias,
            aliasAfter = afterAlias
        )
    }

    /**
     * 현재 alias가 기대하는 인덱스를 향하고 있는지 검증한다.
     */
    private fun validateCurrentAlias(aliasState: AliasState, expectedCurrentIndex: String) {
        if (aliasState.readTargets.isEmpty() || aliasState.writeTargets.isEmpty()) {
            throw IllegalStateException("현재 alias가 설정되어 있지 않아 롤백할 수 없습니다.")
        }

        val expected = setOf(expectedCurrentIndex)
        val readMatches = aliasState.readTargets.toSet() == expected
        val writeMatches = aliasState.writeTargets.toSet() == expected
        if (!readMatches || !writeMatches) {
            throw IllegalStateException(
                "현재 alias(read=${aliasState.readTargets}, write=${aliasState.writeTargets})가 요청된 현재 인덱스($expectedCurrentIndex)만을 가리키지 않습니다."
            )
        }
    }
}

data class ReindexRollbackCommand(
    val currentIndex: String,
    val rollbackToIndex: String
)

data class ReindexRollbackResult(
    val rollbackToIndex: String,
    val currentIndex: String,
    val aliasBefore: AliasState,
    val aliasAfter: AliasState
)
