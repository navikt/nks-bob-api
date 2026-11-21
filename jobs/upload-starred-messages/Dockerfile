# ---------- build stage ----------
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jdk:openjdk-25@sha256:1cfe99e3b8558ca73e2a58a391ce4c7782152da38d7d9712cc780db0003ecde6 AS build
USER root
WORKDIR /workspace

# Copy gradle wrapper and build files first for better caching
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts jobs/upload-starred-messages/build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml ./
RUN ./gradlew --version

COPY . .

RUN ./gradlew :jobs:upload-starred-messages:installDist --no-daemon

# ---------- runtime stage ----------
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25@sha256:57c67205ed160d238b6e3442be9fa7ce567517d65d07adf334389f6f464b3b8f
WORKDIR /app

COPY --from=build /workspace/jobs/upload-starred-messages/build/install/upload-starred-messages/lib/ /app/lib/
COPY --from=build /workspace/jobs/upload-starred-messages/build/libs/upload-starred-messages-*.jar /app/app.jar

ENV TZ="Europe/Oslo"

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/app.jar:/app/lib/*", "no.nav.nks_ai.upload_starred_messages.MainKt"]
