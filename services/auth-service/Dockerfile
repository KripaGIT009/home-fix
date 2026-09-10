# Runtime-only image for auth-service. The fat jar is built on the host
# (mvn -DskipTests package) using the shared libs already installed in ~/.m2,
# then copied in here. Keeps the image small and the build fast for the local
# prove-out slice.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app
COPY target/auth-service-1.0.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
