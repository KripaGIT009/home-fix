# Runtime image for api-gateway. The fat jar is built on the host and copied in; Spring Boot's
# tools jar mode splits it into a thin launcher jar plus lib/ so the dependency layer caches.
FROM eclipse-temurin:25-jre-alpine AS extract
WORKDIR /build
COPY target/api-gateway-1.0.0-SNAPSHOT.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted

FROM eclipse-temurin:25-jre-alpine
# UID 999 as in the earlier Debian-based images, so files the services already wrote to
# bind mounts (docker/dev-sms, docker/dev-mail) stay writable.
RUN addgroup -S app && adduser -S -u 999 -G app app
WORKDIR /app
COPY --from=extract /build/extracted/lib/ lib/
COPY --from=extract /build/extracted/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
