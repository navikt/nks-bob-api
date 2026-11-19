# ---------- build stage ----------
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jdk:openjdk-25@sha256:efed4de7b209d89589204ad776afa8109d40235dd0b28fb70efd365f374fc493 AS build
USER root
WORKDIR /workspace

# Copy gradle wrapper and build files first for better caching
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts jobs/delete-ignored-words/build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml ./
RUN ./gradlew --version

COPY . .

RUN ./gradlew :jobs:delete-ignored-words:installDist --no-daemon

# ---------- runtime stage ----------
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25@sha256:734fe4196c703f8f1f332dadde4fee56c1f09971ececa775cb2578d8475d730f
WORKDIR /app

COPY --from=build /workspace/jobs/delete-ignored-words/build/install/delete-ignored-words/lib/ /app/lib/
COPY --from=build /workspace/jobs/delete-ignored-words/build/libs/delete-ignored-words-*.jar /app/app.jar

ENV TZ="Europe/Oslo"

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/app.jar:/app/lib/*", "no.nav.nks_ai.delete_ignored_words.MainKt"]
