# ---------- build stage ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy gradle wrapper and build files first for better caching
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts jobs/upload-starred-messages/build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml ./
RUN ./gradlew --version

COPY . .

# Pre-fetch dependencies to warm cache
RUN ./gradlew :jobs:upload-starred-messages:clean --no-daemon

RUN ./gradlew :jobs:upload-starred-messages:installDist --no-daemon

# ---------- runtime stage ----------
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app

COPY --from=build /workspace/jobs/upload-starred-messages/build/install/upload-starred-messages/lib/ /app/lib/
COPY --from=build /workspace/jobs/upload-starred-messages/build/libs/upload-starred-messages-*.jar /app/app.jar

ENV TZ="Europe/Oslo"

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/lib/*:/app/app.jar", "no.nav.nks_ai.upload_starred_messages.MainKt"]
