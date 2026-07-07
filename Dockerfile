# BrewShot in a box — jar-on-JVM + Chromium, fully self-contained.
# GIFs work here (JVM ImageIO), unlike the macOS native binary. This is the
# CI/pipeline shape: reproducible browser, no "which Chrome does the runner
# have" drift.
#
#   docker build -t brewshot .
#   docker run --rm -v "$PWD:/work" brewshot https://example.com -o /work/page.png
#   cat page.html | docker run --rm -i -v "$PWD:/work" brewshot - -o /work/page.png

# ---- build the jar -------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon jar \
    && cp $(ls build/libs/brewshot-*.jar | grep -v sources) /brewshot.jar

# ---- runtime: JRE + chromium + fonts ------------------------------------
# Alpine, deliberately: Ubuntu/Debian-slim images ship a snap-stub `chromium`
# that cannot run inside a container; Alpine's package is the real browser.
FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache chromium font-liberation ttf-dejavu font-noto-emoji

# Chrome's sandbox needs privileges containers don't grant by default;
# BrewShot appends these flags to the launch.
ENV BREWSHOT_CHROME=/usr/bin/chromium-browser \
    BREWSHOT_CHROME_ARGS="--no-sandbox --disable-dev-shm-usage"

COPY --from=build /brewshot.jar /opt/brewshot.jar
# Non-root: chromium + --no-sandbox as root is the worst combination; a
# dedicated user keeps renderer compromise contained to nothing.
RUN adduser -D brewshot
USER brewshot
WORKDIR /work
ENTRYPOINT ["java", "-jar", "/opt/brewshot.jar"]
CMD ["--help"]
