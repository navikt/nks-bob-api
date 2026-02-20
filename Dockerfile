# ---------- build stage ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy gradle wrapper and build files first for better caching
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew --version

# Pre-fetch dependencies to warm cache
RUN ./gradlew clean --no-daemon

COPY src src
RUN ./gradlew installDist --no-daemon

# ---------- runtime stage ----------
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app

COPY --from=build /workspace/build/install/no.nav.nks-bob-api/lib/ /app/lib/
COPY --from=build /workspace/build/install/no.nav.nks-bob-api/lib/no.nav.nks-bob-api-*.jar /app/app.jar

ENV TZ="Europe/Oslo"
EXPOSE 8080

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/lib/*:/app/app.jar", "no.nav.nks_ai.ApplicationKt"]
