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
    mainClass.set("no.nav.nks_ai.delete_ignored_words.MainKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.arrow.core)
    implementation(libs.kotlin.logging)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.call.id)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.core)
    implementation(libs.logback.classic)
    implementation(libs.logback.core)
    implementation(libs.logstash.logback.encoder)

    constraints {
        implementation("tools.jackson.core:jackson-core:3.1.3") {
            because("GHSA-2m67-wjpj-xhg9,  GHSA-72hv-8253-57qq and CVE-2026-29062")
        }
    }
}