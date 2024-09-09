FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY /build/libs/no.nav.nks-bob-api-all.jar app.jar
COPY /build/resources/ /app/resources/
ENV TZ="Europe/Oslo"
EXPOSE 8080
CMD [ "-Xms256m", "-Xmx1024m", "-jar", "app.jar" ]
