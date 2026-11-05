FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jdk:openjdk-25 AS build
USER root
WORKDIR /workspace
COPY gradlew gradle.properties settings.gradle.kts ./
COPY gradle ./gradle
COPY api/build.gradle.kts ./api/build.gradle.kts
COPY shared/build.gradle.kts ./shared/build.gradle.kts
COPY jobs/delete-conversations/build.gradle.kts ./jobs/delete-conversations/build.gradle.kts
COPY jobs/delete-ignored-words/build.gradle.kts ./jobs/delete-ignored-words/build.gradle.kts
COPY jobs/upload-starred-messages/build.gradle.kts ./jobs/upload-starred-messages/build.gradle.kts
COPY jobs/delete-active-connections/build.gradle.kts ./jobs/delete-active-connections/build.gradle.kts
RUN ./gradlew --version
COPY shared/src ./shared/src
COPY jobs/delete-active-connections/src ./jobs/delete-active-connections/src
RUN ./gradlew :jobs:delete-active-connections:installDist --no-daemon

FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25
WORKDIR /app
COPY --from=build /workspace/jobs/delete-active-connections/build/install/delete-active-connections/lib/ /app/lib/
ENV TZ="Europe/Oslo"
ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/lib/*", "no.nav.nks_ai.delete_active_connections.MainKt"]
