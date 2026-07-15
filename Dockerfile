FROM eclipse-temurin:21-jre

ARG JAR_FILE=target/zeebe-play-2.0.0-SNAPSHOT.jar

WORKDIR /app
COPY ${JAR_FILE} app.jar

RUN groupadd --system zeebe-play \
  && useradd --system --gid zeebe-play --home-dir /app zeebe-play \
  && chown -R zeebe-play:zeebe-play /app

USER zeebe-play

EXPOSE 8080 26500

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
