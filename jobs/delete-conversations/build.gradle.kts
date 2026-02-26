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
    mainClass.set("no.nav.nks_ai.delete_conversations.MainKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.config4k)
    implementation(libs.janino)
    implementation(libs.kotlin.logging)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.call.id)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)
    implementation(libs.logback.core)
    implementation(libs.logstash.logback.encoder)
    implementation("io.ktor:ktor-client-auth:3.4.0")
}