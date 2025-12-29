package com.searchfoundry.eval.experiment

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component

/**
 * 인덱스 템플릿 JSON을 nori 실험 케이스에 맞춰 동적으로 변형하는 빌더.
 * - base 템플릿은 파일(예: docs_v1.json)에서 로드한 뒤 케이스별로 decompound/userdict/synonym 구성을 바꾼다.
 */
@Component
class AnalyzerIndexTemplateBuilder(
    private val objectMapper: ObjectMapper
) {
    private val userDictPath = "analysis/userdict_ko.txt"
    private val synonymPath = "analysis/synonyms_ko.txt"

    /**
     * base 템플릿 JSON 문자열을 파싱해 실험 케이스에 맞게 변형한 뒤 다시 JSON 문자열로 반환한다.
     */
    fun buildTemplate(baseTemplateJson: String, case: AnalyzerExperimentCase): String {
        val root = objectMapper.readTree(baseTemplateJson) as? ObjectNode
            ?: throw IllegalArgumentException("인덱스 템플릿이 ObjectNode 형태가 아닙니다.")

        val analysisNode = root.childObject("settings").childObject("analysis")
        val tokenizerNode = analysisNode.childObject("tokenizer").childObject("nori_tokenizer_user")
        tokenizerNode.put("decompound_mode", case.decompoundMode)

        if (case.useUserDictionary) {
            tokenizerNode.put("user_dictionary", userDictPath)
        } else {
            tokenizerNode.remove("user_dictionary")
        }

        val filterNode = analysisNode.childObject("filter")
        val searchAnalyzer = analysisNode.childObject("analyzer").childObject("ko_search")

        if (case.useSynonymGraph) {
            applySynonymFilter(filterNode)
            val filterArray = searchAnalyzer.putArray("filter")
            filterArray.add("lowercase")
            filterArray.add("ko_synonyms")
        } else {
            filterNode.remove("ko_synonyms")
            val filterArray = searchAnalyzer.putArray("filter")
            filterArray.add("lowercase")
        }

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
    }

    /**
     * synonym_graph 필터 정의를 케이스에 맞게 설정한다.
     */
    private fun applySynonymFilter(filterNode: ObjectNode) {
        val synonymNode = filterNode.childObject("ko_synonyms")
        synonymNode.put("type", "synonym_graph")
        synonymNode.put("synonyms_path", synonymPath)
        synonymNode.put("updateable", true)
    }

    /**
     * Jackson의 with(String) API가 Deprecated 되었으므로 withObject로 대체해 중첩 ObjectNode를 안전하게 생성한다.
     */
    private fun ObjectNode.childObject(fieldName: String): ObjectNode = this.withObject(fieldName)
}
