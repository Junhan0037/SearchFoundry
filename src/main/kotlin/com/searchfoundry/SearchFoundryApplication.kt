package com.searchfoundry

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SearchFoundryApplication

fun main(args: Array<String>) {
    runApplication<SearchFoundryApplication>(*args)
}
