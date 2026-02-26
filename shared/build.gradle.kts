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

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.aedile.core)
    implementation(libs.kotlin.logging)
    implementation(libs.ktor.client.core)
}