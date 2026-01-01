package com.searchfoundry.index

import com.searchfoundry.core.document.Document
import com.searchfoundry.core.search.SearchQuery
import com.searchfoundry.core.search.SearchResult

/**
 * reindex 검증에서 count/scan 동작을 추상화한 포트.
 * - 구현은 Elasticsearch 외에도 파일/메모리 등으로 확장 가능하도록 한다.
 */
interface ReindexIndexReader {
    fun count(index: String): Long
    fun scan(index: String, from: Int, size: Int): List<Document>
}

/**
 * 검증용 검색 실행 포트. 검증 시나리오에서 사용할 최소 인터페이스만 노출한다.
 */
interface ReindexSearchPort {
    fun search(request: SearchQuery): SearchResult
}
