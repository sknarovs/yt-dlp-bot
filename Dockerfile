# Stage 1: build (runs on the build host's architecture; the jars are platform independent)
FROM --platform=$BUILDPLATFORM docker.io/eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon test installDist

# Stage 2: runtime
FROM docker.io/eclipse-temurin:25-jre-alpine

RUN apk add --no-cache ffmpeg python3 deno \
    && python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir yt-dlp yt-dlp-ejs

ENV PATH="/opt/venv/bin:$PATH"

WORKDIR /app

COPY --from=builder /build/build/install/yt-dlp-bot/ /app/

RUN mkdir downloads

CMD ["/app/bin/yt-dlp-bot"]
