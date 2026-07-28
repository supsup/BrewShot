# BrewShot in a box — jar-on-JVM + Chromium, with the original one-shot CLI
# and an additive container-only folder worker. GIFs work here (JVM ImageIO),
# unlike the macOS native binary. This is the CI/pipeline shape: reproducible
# browser, no "which Chrome does the runner have" drift.
#
#   docker build -t brewshot .
#   docker run --rm -v "$PWD:/work" brewshot https://example.com -o /work/page.png
#   cat page.html | docker run --rm -i -v "$PWD:/work" brewshot - -o /work/page.png

# Tags remain as human-readable provenance; immutable multi-platform index
# digests make the selected bytes fail-closed instead of drifting underneath
# the same tag. Package versions below are likewise exact: a repository that no
# longer carries them fails the build rather than silently changing Chrome.
ARG TEMURIN_JDK_IMAGE=eclipse-temurin:25-jdk@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea
ARG TEMURIN_JDK_ALPINE_IMAGE=eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151
ARG TEMURIN_JRE_ALPINE_IMAGE=eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
ARG CHROMIUM_VERSION=149.0.7827.53-r0
ARG FONT_LIBERATION_VERSION=2.1.5-r2
ARG FONT_DEJAVU_VERSION=2.37-r6
ARG FONT_NOTO_EMOJI_VERSION=2.048-r0

# ---- build the jar -------------------------------------------------------
FROM ${TEMURIN_JDK_IMAGE} AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon clean jar \
    && mkdir -p /out \
    && jar_path="$(find build/libs -maxdepth 1 -type f -name 'brewshot-*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)" \
    && test -n "$jar_path" \
    && cp "$jar_path" /out/brewshot.jar

RUN mkdir -p /worker-classes \
    && javac --release 21 -cp /out/brewshot.jar -d /worker-classes \
        docker/BrewShotFolderWorker.java \
    && jar --create --file /out/brewshot-worker.jar \
        --main-class com.brewshot.BrewShotFolderWorker \
        -C /worker-classes .

# ---- reproducible real-browser CI lane ----------------------------------
# `docker build --target chrome-test .` runs only the source-derived Chrome
# test catalog against the same pinned Chromium/font set the runtime ships.
FROM ${TEMURIN_JDK_ALPINE_IMAGE} AS chrome-test
ARG CHROMIUM_VERSION
ARG FONT_LIBERATION_VERSION
ARG FONT_DEJAVU_VERSION
ARG FONT_NOTO_EMOJI_VERSION
RUN apk add --no-cache \
        chromium="${CHROMIUM_VERSION}" \
        font-liberation="${FONT_LIBERATION_VERSION}" \
        font-dejavu="${FONT_DEJAVU_VERSION}" \
        font-noto-emoji="${FONT_NOTO_EMOJI_VERSION}"
ENV BREWSHOT_CHROME=/usr/bin/chromium-browser \
    BREWSHOT_CHROME_ARGS="--no-sandbox --disable-dev-shm-usage" \
    BREWSHOT_REQUIRE_CHROME=1
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon chromeTest --stacktrace

# ---- runtime: JRE + chromium + fonts ------------------------------------
# Alpine, deliberately: Ubuntu/Debian-slim images ship a snap-stub `chromium`
# that cannot run inside a container; Alpine's package is the real browser.
FROM ${TEMURIN_JRE_ALPINE_IMAGE}
ARG CHROMIUM_VERSION
ARG FONT_LIBERATION_VERSION
ARG FONT_DEJAVU_VERSION
ARG FONT_NOTO_EMOJI_VERSION
RUN apk add --no-cache \
        chromium="${CHROMIUM_VERSION}" \
        font-liberation="${FONT_LIBERATION_VERSION}" \
        font-dejavu="${FONT_DEJAVU_VERSION}" \
        font-noto-emoji="${FONT_NOTO_EMOJI_VERSION}"

# Chrome's sandbox needs privileges containers don't grant by default;
# BrewShot appends these flags to the launch.
ENV BREWSHOT_CHROME=/usr/bin/chromium-browser \
    BREWSHOT_CHROME_ARGS="--no-sandbox --disable-dev-shm-usage"

# Non-root: chromium + --no-sandbox as root is the worst combination. Fixed
# numeric ownership also makes the bind-mount contract explicit on Linux.
RUN addgroup -S -g 10001 brewshot \
    && adduser -S -D -u 10001 -G brewshot brewshot \
    && mkdir -p /opt/brewshot \
        /home/brewshot \
        /brewshot/input/processing \
        /brewshot/input/finished \
        /brewshot/input/failed/pending \
        /brewshot/output \
    && chown -R brewshot:brewshot /home/brewshot /brewshot

COPY --from=build /out/brewshot.jar /opt/brewshot/brewshot.jar
COPY --from=build /out/brewshot-worker.jar /opt/brewshot/brewshot-worker.jar
COPY docker/entrypoint.sh /opt/brewshot/entrypoint.sh
RUN chmod 0555 /opt/brewshot/entrypoint.sh \
    && chmod 0444 /opt/brewshot/brewshot.jar /opt/brewshot/brewshot-worker.jar

USER 10001:10001
ENV HOME=/home/brewshot
# Preserve the original container CLI's relative-path contract. Watch mode
# uses absolute /brewshot/input and /brewshot/output roots, so it does not
# depend on the process working directory.
WORKDIR /work
ENTRYPOINT ["/opt/brewshot/entrypoint.sh"]
CMD ["--help"]
