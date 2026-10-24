#!/bin/sh
# b882214a: inspect ALL source certificates before changing any image source.
# Run only against explicit image directories or isolated synthetic test roots.
set -eu
export LC_ALL=C
[ "$#" -gt 0 ] || { echo 'usage: distrust-os-sources.sh SOURCE_DIRECTORY ...' >&2; exit 2; }
# Exit contract: 0 OK, 2 VOID. Any other non-zero exit (a producer aborting
# under set -e), a failed scratch cleanup, or HUP/INT/TERM is VOID (2). Cleanup
# runs with errexit OFF and its status is checked explicitly, so a failed rm can
# neither abort the trap nor leak its own status (it used to turn 2 into 1, and
# OK into 1). A HUP/INT/TERM that arrives DURING cleanup is recorded, never
# ignored: cleanup finishes (no policy work resumes) and the exit is VOID (2),
# even after successful work.
work=""
on_exit() {
    # Record, never ignore, a signal during cleanup (first one wins).
    interrupted=
    trap 'interrupted=${interrupted:-HUP}' HUP
    trap 'interrupted=${interrupted:-INT}' INT
    trap 'interrupted=${interrupted:-TERM}' TERM
    rc=$1
    set +e
    status=0
    if [ "$rc" -ne 0 ]; then
        echo "distrust-os-sources: VOID (exit 2): failed with exit $rc" >&2; status=2
    fi
    if [ -n "$work" ]; then
        rm -rf "$work"; cleanup=$?
        # Once a trap has run, dash reports the EXIT status in $?, not rm's,
        # so after a signal only the existence check counts.
        [ -z "$interrupted" ] || cleanup=0
        if [ "$cleanup" -ne 0 ] || [ -e "$work" ]; then
            [ -z "$interrupted" ] || cleanup="status unknown after SIG$interrupted"
            echo "distrust-os-sources: CLEANUP FAILED, scratch $work not removed (rm: $cleanup)" >&2
            [ "$rc" -ne 0 ] || echo "distrust-os-sources: VOID (exit 2): scratch cleanup failed" >&2
            status=2
        fi
    fi
    if [ -n "$interrupted" ]; then
        echo "distrust-os-sources: VOID (exit 2): interrupted by SIG$interrupted during cleanup" >&2; status=2
    fi
    exit "$status"
}
on_signal() { echo "distrust-os-sources: VOID interrupted by SIG$1" >&2; exit 2; }
# Installed before mktemp, so even a failed scratch creation is VOID.
trap 'on_exit "$?"' EXIT
trap 'on_signal HUP' HUP
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
umask 077
# Assign the trap-owned $work only after mktemp succeeds AND yields a directory:
# a failed mktemp's stdout must never become a path the EXIT cleanup removes.
scratch=$(mktemp -d) && [ -d "$scratch" ] || { echo 'distrust-os-sources: cannot create a scratch directory' >&2; exit 2; }
work=$scratch
if command -v timeout >/dev/null 2>&1; then deadline=timeout
elif command -v gtimeout >/dev/null 2>&1; then deadline=gtimeout
else echo 'timeout (or gtimeout) is required' >&2; exit 2; fi
: > "$work/paths"
for root do
    case "$root" in /*) ;; *) echo 'source directories must be absolute' >&2; exit 2 ;; esac
    [ -d "$root" ] && [ ! -L "$root" ] || exit 2
    find "$root" -type l -print > "$work/links"
    [ ! -s "$work/links" ] || exit 2
    # Newlines cannot be represented by the POSIX read loop; refuse them explicitly.
    find "$root" \( -name '*.crt' -o -name '*.pem' \) -exec sh -c '
      for path do case "$path" in *"
"*) exit 2 ;; esac; printf "%s\n" "$path"; done
    ' sh {} + >> "$work/paths"
done
files=0; certificates=0; bytes=0; removed=0; control1=0; control2=0; started=$(date +%s)
while IFS= read -r path; do
    [ -f "$path" ] && [ ! -L "$path" ] || exit 2
    size=$(wc -c < "$path")
    files=$((files + 1)); bytes=$((bytes + size))
    [ "$files" -le 4096 ] && [ "$size" -gt 0 ] && [ "$size" -le 1048576 ] && [ "$bytes" -le 67108864 ] || exit 2
    part="$work/$files"; mkdir "$part"
    # Split and validate boundaries/base64 alphabet; text outside blocks is VOID.
    awk -v out="$part" '
      { sub(/\r$/, "") }
      $0=="-----BEGIN CERTIFICATE-----" {
        if (inside) exit 2
        inside=1; count++; if(count>4096) exit 2
        file=out "/" count ".pem"; encoded=out "/" count ".b64"
        print > file; next
      }
      $0=="-----END CERTIFICATE-----" {
        if (!inside || !length(payload)) exit 2
        print > file; close(file); close(encoded); inside=0; payload=""; next
      }
      inside {
        if ($0 !~ /^[A-Za-z0-9+\/=]+$/ || length(payload)+length($0)>699052) exit 2
        payload=payload $0; print > file; printf "%s",$0 > encoded; next
      }
      /^[[:space:]]*$/ { next }
      { exit 2 }
      END { if(inside || !count) exit 2; print count > (out "/count") }
    ' "$path"
    count=$(cat "$part/count"); n=1; : > "$part/clean"
    while [ "$n" -le "$count" ]; do
        now=$(date +%s); [ "$((now - started))" -le 60 ] || exit 2
        certificates=$((certificates + 1)); [ "$certificates" -le 4096 ] || exit 2
        "$deadline" 3 openssl base64 -d -A -in "$part/$n.b64" -out "$part/input.der"
        decoded=$(wc -c < "$part/input.der"); [ "$decoded" -gt 0 ] && [ "$decoded" -le 524288 ] || exit 2
        "$deadline" 3 openssl base64 -A -in "$part/input.der" -out "$part/roundtrip.b64"
        cmp -s "$part/$n.b64" "$part/roundtrip.b64" || exit 2
        "$deadline" 3 openssl x509 -in "$part/$n.pem" -outform DER -out "$part/parsed.der"
        cmp -s "$part/input.der" "$part/parsed.der" || exit 2
        "$deadline" 3 openssl dgst -sha256 -r "$part/parsed.der" > "$part/fingerprint"
        read -r fingerprint rest < "$part/fingerprint"
        [ "${#fingerprint}" -eq 64 ] || exit 2
        case "$fingerprint" in *[!0-9a-f]*) exit 2 ;; esac
        case "$fingerprint" in
          86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b|d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4)
            removed=$((removed + 1)) ;;
          *) cat "$part/$n.pem" >> "$part/clean" ;;
        esac
        case "$fingerprint" in
          96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6) control1=1 ;;
          cb3ccbb76031e5e0138f8dd39a23f9de47ffc35e43c1144cea27d46a5ab1cb5f) control2=1 ;;
        esac
        n=$((n + 1))
    done
done < "$work/paths"
[ "$certificates" -gt 0 ] && [ "$control1" -eq 1 ] && [ "$control2" -eq 1 ] || exit 2
# All inputs were parsed and both controls observed before the first mutation.
n=0
while IFS= read -r path; do
    n=$((n + 1))
    if [ -s "$work/$n/clean" ]; then
        cmp -s "$path" "$work/$n/clean" || cat "$work/$n/clean" > "$path"
    else rm -f "$path"; fi
done < "$work/paths"
printf 'OS sources: %s files, %s parsed certificates, %s forbidden removed, both pinned controls present\n' "$files" "$certificates" "$removed"
