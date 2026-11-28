# ---------- build stage ----------
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jdk:openjdk-25@sha256:0b1bb9d9f71e952aac44c1d217944304d293749a11c90a710ee0683a7f09ec99 AS build
USER root
WORKDIR /workspace

# Copy gradle wrapper and build files first for better caching
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts jobs/delete-conversations/build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml ./
RUN ./gradlew --version

COPY . .

RUN ./gradlew :jobs:delete-conversations:installDist --no-daemon

# ---------- runtime stage ----------
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25@sha256:6e220de7a62b0174bb7547f7f6a7f6c53f4f60dd04bf6bdbebdf88560994951a
WORKDIR /app

COPY --from=build /workspace/jobs/delete-conversations/build/install/delete-conversations/lib/ /app/lib/
COPY --from=build /workspace/jobs/delete-conversations/build/libs/delete-conversations-*.jar /app/app.jar

ENV TZ="Europe/Oslo"

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/app.jar:/app/lib/*", "no.nav.nks_ai.delete_conversations.MainKt"]
