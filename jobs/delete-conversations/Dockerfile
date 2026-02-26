# ---------- build stage ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy gradle wrapper and build files first for better caching
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts api/build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml ./
RUN ./gradlew --version

COPY . .

# Pre-fetch dependencies to warm cache
RUN ./gradlew :jobs:delete-conversations:clean --no-daemon

RUN ./gradlew :jobs:delete-conversations:installDist --no-daemon

# ---------- runtime stage ----------
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app

COPY --from=build /workspace/jobs/delete-conversations/build/install/delete-conversations/lib/ /app/lib/
COPY --from=build /workspace/jobs/delete-conversations/build/libs/delete-conversations-*.jar /app/app.jar

ENV TZ="Europe/Oslo"

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/lib/*:/app/app.jar", "no.nav.nks_ai.delete-convervsations.MainKt"]
