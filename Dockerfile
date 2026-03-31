FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -B -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /app/target/cycles-server-events-*.jar app.jar
USER appuser
EXPOSE 7980
HEALTHCHECK --interval=15s --timeout=5s --retries=3 CMD wget -qO- http://localhost:7980/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
