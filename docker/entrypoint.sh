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
