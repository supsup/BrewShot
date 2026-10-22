#!/bin/sh
# Build guard for the two forbidden roots (emSign ECC Root CA - G3, AAA
# Certificate Services) in one image's OS bundle and Java cacerts.
# Both arms always run, each on a scratch copy (the inspected stores are never
# mutated), and each yields 0 (completed, clean), 1 (completed, forbidden root
# present) or 2 (VOID: could not complete; any copy/parse/producer failure, a
# missing pinned control, or any non-zero exit of a copied policy script).
# Exit: 2 if either arm is VOID, else 1 if either arm or the leftover check
# found a problem, else 0. Run it as the last RUN of every stage it guards.
set -u
[ "$#" -eq 2 ] && [ -s "$1" ] && [ -s "$2" ] || { echo 'usage: guard-no-forbidden.sh OS_BUNDLE JAVA_CACERTS' >&2; exit 2; }
here=$(dirname "$0")
bundle=$1; store=$2
work=$(mktemp -d) || { echo 'GUARD VOID: cannot create a scratch directory' >&2; exit 2; }
trap 'rm -rf "$work"' EXIT
trap 'exit 2' HUP INT TERM

# Leftover check: Alpine/Debian may leave an unfiltered package bundle beside a
# modified one. Counts as forbidden-present (1).
leftover=0
for stale in "$bundle.apk-new" "$bundle.dpkg-new"; do
    if [ -e "$stale" ] || [ -L "$stale" ]; then
        echo "GUARD leftover FAIL: unfiltered package bundle left at $stale" >&2
        leftover=1
    fi
done

# OS arm: the copied script exits 0 only after a complete parse with both
# controls, and reports how many forbidden certificates it removed from the copy.
os=2
if mkdir "$work/os" && cp "$bundle" "$work/os/bundle.pem"; then
    report=$(sh "$here/distrust-os-sources.sh" "$work/os")
    rc=$?
    printf '%s\n' "$report"
    nl='
'
    if [ "$rc" -ne 0 ]; then
        echo "GUARD OS arm VOID: distrust-os-sources.sh exited $rc on $bundle" >&2
    else
        case "$report" in
          *"$nl"*) echo "GUARD OS arm VOID: unrecognised report for $bundle" >&2 ;;
          'OS sources: 1 files, '[0-9]*' parsed certificates, 0 forbidden removed, both pinned controls present')
            os=0 ;;
          'OS sources: 1 files, '[0-9]*' parsed certificates, '[1-9]*' forbidden removed, both pinned controls present')
            os=1; echo "GUARD OS arm FAIL: forbidden root present in $bundle" >&2 ;;
          *) echo "GUARD OS arm VOID: unrecognised report for $bundle" >&2 ;;
        esac
    fi
else
    echo "GUARD OS arm VOID: cannot copy $bundle to scratch" >&2
fi

# Java arm: the copied script deletes only forbidden aliases, after a complete
# enumeration with both controls, so on its exit 0 any byte change in the
# scratch copy means a forbidden root was present.
java=2
if cp "$store" "$work/cacerts"; then
    sh "$here/distrust-java-roots.sh" "$work/cacerts"
    rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "GUARD Java arm VOID: distrust-java-roots.sh exited $rc on $store" >&2
    else
        cmp -s "$store" "$work/cacerts"
        rc=$?
        case "$rc" in
          0) java=0 ;;
          1) java=1; echo "GUARD Java arm FAIL: forbidden root present in $store" >&2 ;;
          *) echo "GUARD Java arm VOID: cmp exited $rc comparing $store" >&2 ;;
        esac
    fi
else
    echo "GUARD Java arm VOID: cannot copy $store to scratch" >&2
fi

summary="os=$os java=$java leftover=$leftover"
if [ "$os" -eq 2 ] || [ "$java" -eq 2 ]; then
    echo "GUARD VOID (exit 2): $summary" >&2
    exit 2
fi
if [ "$os" -eq 1 ] || [ "$java" -eq 1 ] || [ "$leftover" -eq 1 ]; then
    echo "GUARD FAIL (exit 1): $summary" >&2
    exit 1
fi
echo "GUARD PASS: no forbidden root in OS bundle or Java cacerts ($summary)"
