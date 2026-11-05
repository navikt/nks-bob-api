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
    mainClass.set("no.nav.nks_ai.delete_active_connections.MainKt")
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
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.wiremock)
}
