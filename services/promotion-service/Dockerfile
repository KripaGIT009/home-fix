# Runtime-only image for promotion-service. Fat jar built on the host and copied in.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app
COPY target/promotion-service-1.0.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8098
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
