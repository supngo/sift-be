# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# runs the unit tests (*Test); integration tests (*IT) need a running Postgres, so run `mvn verify` on the host
RUN mvn -B -q package

# ---- run ----
FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 1001 --home /app sift
WORKDIR /app
COPY --from=build --chown=sift /workspace/target/sift-*-SNAPSHOT.jar /app/app.jar
USER sift
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
