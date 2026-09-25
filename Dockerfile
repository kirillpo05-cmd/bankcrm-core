# syntax=docker/dockerfile:1

# One Dockerfile for both services. They are modules of a single Maven reactor and differ only in
# which one is packaged, so two files would be the same file twice and would drift apart.
#
# The build context is the repository root, not the service directory: the reactor needs the parent
# pom, the sibling `common` module, and `db/migrations`, none of which a service directory can see.
# docker-compose.yml therefore sets `context: .` and passes SERVICE.

ARG JAVA_VERSION=21

# ----------------------------------------------------------------------------- build
FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build
ARG SERVICE
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Each service's pom packages its own migrations into the jar as classpath:db/migration, so the
# running service applies exactly the files compose's Flyway container applies. Without db/ the
# package step would silently produce a jar that migrates nothing.
COPY db/ db/
COPY src/ src/

# The cache mount keeps ~/.m2 across builds, so a source change does not re-download the world.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl "src/${SERVICE}" -am -DskipTests package \
 && cp "src/${SERVICE}/target/${SERVICE}"-*.jar /build/app.jar

# --------------------------------------------------------------------------- runtime
FROM eclipse-temurin:${JAVA_VERSION}-jre
ARG SERVICE

# Not root. This process reads a database and answers HTTP; it never needs to write to its own
# image, and a container that cannot is one less thing to reason about after a compromise.
RUN useradd --system --create-home --uid 10001 client360
USER client360
WORKDIR /app
COPY --from=build --chown=client360:client360 /build/app.jar app.jar

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then honours the container's memory limit
# instead of the host's, which is what makes a compose memory cap mean anything.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
