# ---------- build stage ----------
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jdk:openjdk-25 AS build
WORKDIR /workspace

# Copy gradle wrapper and build files first for better caching
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts api/build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml ./
RUN ./gradlew --version

COPY . .

# Pre-fetch dependencies to warm cache
RUN ./gradlew :api:clean --no-daemon

RUN ./gradlew :api:installDist --no-daemon

# ---------- runtime stage ----------
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25
WORKDIR /app

COPY --from=build /workspace/api/build/install/api/lib/ /app/lib/
COPY --from=build /workspace/api/build/libs/api-*.jar /app/app.jar

ENV TZ="Europe/Oslo"
EXPOSE 8080

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/app.jar:/app/lib/*", "no.nav.nks_ai.api.ApplicationKt"]
