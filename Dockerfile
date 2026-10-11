# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS development

# Java2D/Swing need fonts even when build and test tasks run without a display.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig libfreetype6 \
    && rm -rf /var/lib/apt/lists/*

ENV GRADLE_USER_HOME=/root/.gradle
WORKDIR /workspace

# Use the project's checksum-verified Gradle wrapper, not a global Gradle install.
COPY gradlew gradlew.bat ./
COPY gradle/ ./gradle/
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --version

COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src/ ./src/

# Calling the wrapper through sh also works with non-executable host bind mounts.
ENTRYPOINT ["sh", "./gradlew", "--no-daemon", "--console=plain"]
CMD ["test", "buildPlugin", "verifyPluginStructure"]

FROM development AS build
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --console=plain test buildPlugin verifyPluginStructure

# Export only deliverables; the plugin is installed in an IDE, not run as a service.
FROM scratch AS artifacts
COPY --from=build /workspace/build/distributions/ /distributions/
COPY --from=build /workspace/build/reports/ /reports/
COPY --from=build /workspace/build/test-results/ /test-results/
