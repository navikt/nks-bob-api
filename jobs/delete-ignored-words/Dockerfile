# ---------- build stage ----------
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jdk:openjdk-25@sha256:2b755aa2920bc176eebdc06346bb88bd4fb877aeab871f896c73108b27bf94ba AS build
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
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25@sha256:198712504ade409f57c7e7a7dfca92a1eb9dca7d9ad5af1746ef9c66414baea8
WORKDIR /app

COPY --from=build /workspace/jobs/delete-ignored-words/build/install/delete-ignored-words/lib/ /app/lib/
COPY --from=build /workspace/jobs/delete-ignored-words/build/libs/delete-ignored-words-*.jar /app/app.jar

ENV TZ="Europe/Oslo"

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/app.jar:/app/lib/*", "no.nav.nks_ai.delete_ignored_words.MainKt"]
