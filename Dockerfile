FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -B -DskipTests

FROM eclipse-temurin:21-jre-alpine

# Apply latest Alpine security patches over whatever ships in the upstream
# eclipse-temurin:21-jre-alpine layer. The temurin tag is a moving ref so a
# fresh build picks up older Alpine patch levels until temurin itself rebuilds;
# applying `apk upgrade` here closes that window every time we build.
#
# Concrete fix on this commit: gnutls 3.8.12-r0 -> 3.8.13-r0 (CVE-2026-33845
# HIGH + 12 bundled gnutls CVEs all resolved by the same package bump).
RUN apk upgrade --no-cache

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /app/target/cycles-server-events-*.jar app.jar
USER appuser
EXPOSE 7980 9980
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:9980/actuator/health || exit 1
ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
  "-jar", "app.jar"]
