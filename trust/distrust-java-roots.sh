#!/bin/sh
# b882214a: operate on this image's default cacerts, never a host-mounted store.
# Certificate identity is DER SHA-256, independent of vendor alias or subject label.
set -eu
# Exit contract: 0 OK, 2 VOID. Any other non-zero exit (keytool or awk aborting
# under set -e, including a forbidden root still present after deletion), a
# failed scratch cleanup, or HUP/INT/TERM is VOID (2). Cleanup runs with errexit
# OFF and its status is checked explicitly, so a failed rm can neither abort the
# trap nor leak its own status (it used to turn 2 into 1, and OK into 1). A
# signal ends the script: it used to remove the scratch and keep running. A
# HUP/INT/TERM that arrives DURING cleanup is recorded, never ignored: cleanup
# finishes (no policy work resumes) and the exit is VOID (2), even after
# successful work.
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
        echo "distrust-java-roots: VOID (exit 2): failed with exit $rc" >&2; status=2
    fi
    if [ -n "$work" ]; then
        rm -rf "$work"; cleanup=$?
        # Once a trap has run, dash reports the EXIT status in $?, not rm's,
        # so after a signal only the existence check counts.
        [ -z "$interrupted" ] || cleanup=0
        if [ "$cleanup" -ne 0 ] || [ -e "$work" ]; then
            [ -z "$interrupted" ] || cleanup="status unknown after SIG$interrupted"
            echo "distrust-java-roots: CLEANUP FAILED, scratch $work not removed (rm: $cleanup)" >&2
            [ "$rc" -ne 0 ] || echo "distrust-java-roots: VOID (exit 2): scratch cleanup failed" >&2
            status=2
        fi
    fi
    if [ -n "$interrupted" ]; then
        echo "distrust-java-roots: VOID (exit 2): interrupted by SIG$interrupted during cleanup" >&2; status=2
    fi
    exit "$status"
}
on_signal() { echo "distrust-java-roots: VOID interrupted by SIG$1" >&2; exit 2; }
# Installed before mktemp, so even a failed scratch creation is VOID.
trap 'on_exit "$?"' EXIT
trap 'on_signal HUP' HUP
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
[ "$#" -eq 1 ] && [ -s "$1" ] || { echo 'usage: distrust-java-roots.sh IMAGE_CACERTS' >&2; exit 2; }
store=$1
umask 077
export LC_ALL=C
# Assign the trap-owned $work only after mktemp succeeds AND yields a directory:
# a failed mktemp's stdout must never become a path the EXIT cleanup removes.
scratch=$(mktemp -d) && [ -d "$scratch" ] || { echo 'distrust-java-roots: cannot create a scratch directory' >&2; exit 2; }
work=$scratch

scan() {
    keytool -J-Duser.language=en -J-Duser.country=US -list -v -keystore "$store" \
        -storepass changeit > "$work/store"
    awk -v mode="$1" '
      BEGIN {
        bad1="86A1ECBA089C4A8D3BBE2734C612BA341D813E043CF9E8A862CD5C57A36BBE6B"
        bad2="D7A7A0FB5D7E2731D771E9484EBCDEF71D5F0C3E0A2948782BC83EE0EA699EF4"
        good1="96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6"
        good2="CB3CCBB76031E5E0138F8DD39A23F9DE47FFC35E43C1144CEA27D46A5AB1CB5F"
      }
      function finish_entry() {
        if (aliases && (entry_types!=1 || entry_fingerprints!=1)) invalid=1
      }
      /^Your keystore contains [0-9]+ entr/ { declared=$4; headers++ }
      /^Alias name: / {
        finish_entry(); alias=substr($0,13); aliases++
        entry_types=0; entry_fingerprints=0; next
      }
      /^Entry type: / {
        entry_types++
        if ($0 != "Entry type: trustedCertEntry") invalid=1
      }
      /^[[:space:]]*SHA256: / {
        fp=$0; sub(/^[[:space:]]*SHA256: /,"",fp); gsub(/:/,"",fp)
        if (length(fp)!=64 || fp ~ /[^0-9A-F]/ || alias=="") invalid=1
        entry_fingerprints++; count++; if (fp==good1) control1++; if (fp==good2) control2++
        if (fp==bad1 || fp==bad2) { forbidden++; if(mode=="remove") print alias }
      }
      END {
        finish_entry()
        if (invalid || headers!=1 || count==0 || count!=aliases || count!=declared || !control1 || !control2) exit 2
        if (mode=="verify") {
          if (forbidden) exit 1
          printf "Java cacerts: %d certificates, both pinned controls present, forbidden=0\n",count
        }
      }
    ' "$work/store"
}

# Complete successful enumeration and controls precede any deletion.
scan remove > "$work/aliases"
while IFS= read -r alias; do
    keytool -J-Duser.language=en -J-Duser.country=US -delete -keystore "$store" \
        -storepass changeit -alias "$alias"
done < "$work/aliases"
scan verify
