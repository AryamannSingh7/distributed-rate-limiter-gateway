# Runtime image for the demo backend. Build the jar first (`mvn -DskipTests package`),
# then this copies the Spring Boot repackaged jar onto a slim JRE.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
