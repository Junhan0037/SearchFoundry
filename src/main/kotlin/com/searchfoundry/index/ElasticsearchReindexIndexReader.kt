package com.searchfoundry.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import com.searchfoundry.core.document.Document
import org.springframework.stereotype.Component

/**
 * ElasticsearchClient 기반 count/scan 포트 구현.
 * - match_all + 정렬된 _id 스캔으로 deterministic 해시 계산을 보장한다.
 */
@Component
class ElasticsearchReindexIndexReader(
    private val elasticsearchClient: ElasticsearchClient
) : ReindexIndexReader {
    override fun count(index: String): Long =
        elasticsearchClient.count { builder -> builder.index(index) }.count()

    override fun scan(index: String, from: Int, size: Int): List<Document> {
        val response = elasticsearchClient.search({ builder ->
            builder
                .index(index)
                .query(Query.of { q -> q.matchAll { it } })
                .from(from)
                .size(size)
                .sort { sort -> sort.field { field -> field.field("_id").order(SortOrder.Asc) } }
                .source { source ->
                    source.filter { filter ->
                        filter.includes(
                            "id",
                            "title",
                            "summary",
                            "body",
                            "tags",
                            "category",
                            "author",
                            "publishedAt",
                            "popularityScore"
                        )
                    }
                }
        }, Document::class.java)
        return response.hits().hits().mapNotNull { it.source() }
    }
}
