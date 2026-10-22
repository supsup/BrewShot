#!/bin/sh
# Build guard: FAIL (exit 1) if either forbidden root (emSign ECC Root CA - G3,
# AAA Certificate Services) remains in the named image OS bundle or Java store;
# VOID (exit 2) on any parse failure or missing pinned control.
# It reuses the two copied policy scripts on scratch copies and never mutates
# the stores it inspects. Run it as the last RUN of every stage it guards.
set -eu
[ "$#" -eq 2 ] && [ -s "$1" ] && [ -s "$2" ] || { echo 'usage: guard-no-forbidden.sh OS_BUNDLE JAVA_CACERTS' >&2; exit 2; }
here=$(dirname "$0")
bundle=$1; store=$2
# Alpine/Debian may leave an unfiltered package bundle beside a modified one.
for stale in "$bundle.apk-new" "$bundle.dpkg-new"; do
    [ ! -e "$stale" ] || { echo "GUARD: unfiltered package bundle left at $stale" >&2; exit 1; }
done
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir "$work/os"
cp "$bundle" "$work/os/bundle.pem"
report=$(sh "$here/distrust-os-sources.sh" "$work/os")
printf '%s\n' "$report"
case "$report" in
  *', 0 forbidden removed, both pinned controls present') ;;
  *) echo "GUARD: forbidden root present in $bundle" >&2; exit 1 ;;
esac
cp "$store" "$work/cacerts"
sh "$here/distrust-java-roots.sh" "$work/cacerts"
# The policy script deletes only forbidden aliases; any byte change means one was present.
cmp -s "$store" "$work/cacerts" || { echo "GUARD: forbidden root present in $store" >&2; exit 1; }
echo 'GUARD: no forbidden root in OS bundle or Java cacerts'
