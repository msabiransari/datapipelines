package co.datapipelines.logging

import org.springframework.context.annotation.Configuration

/** The empty context [PlantBootMain]'s normal boot runs around (#337-b); it declares no beans. */
@Configuration(proxyBeanMethods = false)
class PlantBootConfiguration
