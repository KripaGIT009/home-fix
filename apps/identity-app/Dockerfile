# identity-app: one JVM hosting auth-service, customer-service, provider-service, verification-service.
# The fat jar is built on the host first (mvn -pl apps/identity-app -am package) and copied in.
# Spring Boot's tools jar mode splits it into a thin launcher jar plus lib/, so the dependency
# layer is cached between image builds and classes load from plain jars.
FROM eclipse-temurin:25-jre-alpine AS extract
WORKDIR /build
COPY target/identity-app-1.0.0-SNAPSHOT.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=extract /build/extracted/lib/ lib/
COPY --from=extract /build/extracted/app.jar app.jar
USER app
EXPOSE 8081 8082 8083 8094
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
