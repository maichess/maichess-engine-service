FROM eclipse-temurin:21-jdk-noble AS build

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    curl -fL "https://github.com/sbt/sbt/releases/download/v1.10.11/sbt-1.10.11.tgz" | \
    tar -xz -C /usr/local --strip-components=1 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY project/ project/
COPY build.sbt ./

RUN --mount=type=secret,id=GITHUB_TOKEN \
    --mount=type=secret,id=GITHUB_ACTOR \
    GITHUB_TOKEN=$(cat /run/secrets/GITHUB_TOKEN) \
    GITHUB_ACTOR=$(cat /run/secrets/GITHUB_ACTOR) \
    sbt update

COPY src/ src/

RUN --mount=type=secret,id=GITHUB_TOKEN \
    --mount=type=secret,id=GITHUB_ACTOR \
    GITHUB_TOKEN=$(cat /run/secrets/GITHUB_TOKEN) \
    GITHUB_ACTOR=$(cat /run/secrets/GITHUB_ACTOR) \
    sbt 'set coverageEnabled := false' assembly


FROM eclipse-temurin:21-jre-noble AS runtime

WORKDIR /app

COPY --from=build /app/target/scala-3.8.3/app.jar ./app.jar

EXPOSE 50056

CMD ["java", "-jar", "app.jar"]
