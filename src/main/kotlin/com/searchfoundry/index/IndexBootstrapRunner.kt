package com.searchfoundry.index

import com.searchfoundry.support.config.IndexBootstrapProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 로컬/CI에서 필요 시 자동으로 인덱스를 생성하는 러너.
 * app.index.bootstrap.enabled=true 일 때만 동작해 의도치 않은 생성 작업을 막는다.
 */
@Component
class IndexBootstrapRunner(
    private val properties: IndexBootstrapProperties,
    private val indexCreationService: IndexCreationService
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(IndexBootstrapRunner::class.java)

    /**
     * 애플리케이션 기동 시점에 인덱스 생성을 트리거한다.
     */
    override fun run(args: ApplicationArguments) {
        if (!properties.enabled) {
            logger.info("인덱스 부트스트랩이 비활성화되어 실행을 건너뜁니다.")
            return
        }

        logger.info("인덱스 부트스트랩 실행: docs_v{}", properties.version)
        try {
            indexCreationService.createIndex(properties.version)
        } catch (ex: IllegalStateException) {
            logger.warn("인덱스 생성이 건너뛰어졌습니다: {}", ex.message)
        }
    }
}
