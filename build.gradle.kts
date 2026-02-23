plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.ktor)
}

group = "no.nav.nks_ai"
version = "0.0.1"

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("no.nav.nks_ai.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(libs.aedile.core)
    implementation(libs.arrow.core)
    implementation(libs.arrow.resilience)
    implementation(libs.bcrypt)
    implementation(libs.config4k)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgres)
    implementation(libs.google.cloud.bigquery)
    implementation(libs.hikari.cp)
    implementation(libs.janino)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kt.scheduler)
    implementation(libs.ktor.client.apache)
    implementation(libs.ktor.client.call.id)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.openapi)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.metrics)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.webjars)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.swagger.ui)
    implementation(libs.logback.classic)
    implementation(libs.logback.core)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.otel.ktor3)
    implementation(libs.otel.logback.mdc)
    implementation(libs.postgresql)
    implementation(libs.prometheus.tracer)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.server.test.host)
}
