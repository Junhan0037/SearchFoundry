package com.searchfoundry.index.template

import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

/**
 * 버전별 인덱스 템플릿(JSON)을 클래스패스에서 읽어오는 역할을 담당한다.
 * 파일 기반으로 버전 관리해 분석기/매핑 변경 이력을 추적한다.
 */
@Component
class IndexTemplateLoader(
    private val resourceLoader: ResourceLoader
) {

    /**
     * 요청된 버전에 해당하는 템플릿 JSON을 문자열로 반환한다.
     * 존재하지 않는 경우 즉시 예외를 던져 잘못된 배포를 막는다.
     */
    fun load(version: Int): String {
        require(version >= 1) { "인덱스 버전은 1 이상이어야 합니다." }
        val resourcePath = "classpath:elasticsearch/index/docs_v${version}.json"
        val resource = resourceLoader.getResource(resourcePath)
        if (!resource.exists()) {
            throw IllegalArgumentException("인덱스 템플릿을 찾을 수 없습니다: $resourcePath")
        }

        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
