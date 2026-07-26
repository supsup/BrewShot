# BrewShot in a box — jar-on-JVM + Chromium, with the original one-shot CLI
# and an additive container-only folder worker. GIFs work here (JVM ImageIO),
# unlike the macOS native binary. This is the CI/pipeline shape: reproducible
# browser, no "which Chrome does the runner have" drift.
#
#   docker build -t brewshot .
#   docker run --rm -v "$PWD:/work" brewshot https://example.com -o /work/page.png
#   cat page.html | docker run --rm -i -v "$PWD:/work" brewshot - -o /work/page.png

# ---- build the jar -------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
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

# ---- runtime: JRE + chromium + fonts ------------------------------------
# Alpine, deliberately: Ubuntu/Debian-slim images ship a snap-stub `chromium`
# that cannot run inside a container; Alpine's package is the real browser.
FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache chromium font-liberation ttf-dejavu font-noto-emoji

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
WORKDIR /brewshot
ENTRYPOINT ["/opt/brewshot/entrypoint.sh"]
CMD ["--help"]
