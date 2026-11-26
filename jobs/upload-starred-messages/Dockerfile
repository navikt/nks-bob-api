# ---------- build stage ----------
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jdk:openjdk-25@sha256:b90d3d182761731690db3c03380158df86ec06f8700c45f134b0e9ff1bc31a70 AS build
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
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25@sha256:c9834a01923e35d3900d8d9436fcbe7d4a63de4a108ec5530ece647c3d26ca86
WORKDIR /app

COPY --from=build /workspace/jobs/upload-starred-messages/build/install/upload-starred-messages/lib/ /app/lib/
COPY --from=build /workspace/jobs/upload-starred-messages/build/libs/upload-starred-messages-*.jar /app/app.jar

ENV TZ="Europe/Oslo"

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/app.jar:/app/lib/*", "no.nav.nks_ai.upload_starred_messages.MainKt"]
