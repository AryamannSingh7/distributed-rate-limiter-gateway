# Runtime image for the gateway. Build the jar first (`mvn -DskipTests package`),
# then this copies the Spring Boot repackaged jar onto a slim JRE. Built via the
# docker CLI (`docker compose build`), which is reliable on Docker Desktop where the
# buildpack/docker-java pull path is not.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# Exactly one fat jar matches; the *.jar.original left by repackage is excluded.
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
