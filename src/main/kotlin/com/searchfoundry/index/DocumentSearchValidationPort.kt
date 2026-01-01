package com.searchfoundry.index

import com.searchfoundry.core.search.DocumentSearchService
import com.searchfoundry.core.search.SearchQuery
import com.searchfoundry.core.search.SearchResult
import org.springframework.stereotype.Component

/**
 * DocumentSearchService를 감싼 검증용 검색 포트 구현.
 * - 검증에서 필요한 최소 기능만 노출해 테스트 시 스텁으로 쉽게 대체한다.
 */
@Component
class DocumentSearchValidationPort(
    private val documentSearchService: DocumentSearchService
) : ReindexSearchPort {
    override fun search(request: SearchQuery): SearchResult = documentSearchService.search(request)
}
