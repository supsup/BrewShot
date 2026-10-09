#!/bin/sh
set -eu

# The image's fixed user owns /home/brewshot. Linux bind-mount users may
# instead select their host UID/GID; give that override a private writable
# Chromium home without making the application directory writable.
if [ ! -d "${HOME:-}" ] || [ ! -w "$HOME" ]; then
    HOME=$(mktemp -d /tmp/brewshot-home.XXXXXX)
    export HOME
fi

case "${1-}" in
    watch)
        shift
        # Opt the long-running worker into the container-cgroup release proof, so a
        # proven capture's Chrome profile is deleted instead of retained. BrewShot
        # still refuses (UNPROVEN, profile kept) unless its strict cgroup v2 detection
        # passes. An operator can opt out with -e BREWSHOT_RELEASE_PROOF=off.
        export BREWSHOT_RELEASE_PROOF="${BREWSHOT_RELEASE_PROOF-container-cgroup}"
        exec java -cp \
            /opt/brewshot/brewshot.jar:/opt/brewshot/brewshot-worker.jar \
            com.brewshot.BrewShotFolderWorker "$@"
        ;;
    cli)
        shift
        exec java -jar /opt/brewshot/brewshot.jar "$@"
        ;;
    *)
        exec java -jar /opt/brewshot/brewshot.jar "$@"
        ;;
esac
