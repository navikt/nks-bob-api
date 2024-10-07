import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

val kotlin_version: String by project
val logback_version: String by project
val exposed_version: String by project
val h2_version: String by project
val prometheus_version: String by project
val hikari_version: String by project
val postgres_version: String by project
val flyway_version: String by project

plugins {
    kotlin("jvm") version "2.0.20"
    id("io.ktor.plugin") version "2.3.12"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    id("com.gradleup.shadow") version "8.3.2"
}

group = "no.nav.nks_ai"
version = "0.0.1"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "no.nav.nks_ai.ApplicationKt",
        )
    }
}

tasks.withType<ShadowJar> {
    mergeServiceFiles()
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("com.h2database:h2:$h2_version")
    implementation("com.sksamuel.aedile:aedile-core:1.3.1")
    implementation("com.ucasoft.ktor:ktor-simple-cache-jvm:0.4.3")
    implementation("com.ucasoft.ktor:ktor-simple-memory-cache-jvm:0.4.3")
    implementation("com.zaxxer:HikariCP:$hikari_version")
    implementation("io.arrow-kt:arrow-core:1.2.4")
    implementation("io.github.config4k:config4k:0.7.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("io.github.smiley4:ktor-swagger-ui:3.3.1")
    implementation("io.ktor:ktor-client-apache")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-auth-jvm")
    implementation("io.ktor:ktor-server-auth-jwt-jvm")
    implementation("io.ktor:ktor-server-call-id-jvm")
    implementation("io.ktor:ktor-server-call-logging-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-metrics-jvm")
    implementation("io.ktor:ktor-server-metrics-micrometer-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-webjars-jvm")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.micrometer:micrometer-registry-prometheus:$prometheus_version")
    implementation("no.nav.security:token-validation-ktor-v2:5.0.5")
    implementation("org.flywaydb:flyway-core:$flyway_version")
    implementation("org.flywaydb:flyway-database-postgresql:$flyway_version")
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposed_version")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("org.postgresql:postgresql:$postgres_version")
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}
