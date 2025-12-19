package com.searchfoundry

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = ["com.searchfoundry"])
class SearchFoundryApplication

fun main(args: Array<String>) {
    runApplication<SearchFoundryApplication>(*args)
}
