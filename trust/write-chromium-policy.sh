#!/bin/sh
# Write Chromium's managed CADistrustedCertificates policy from the PEMs in
# trust/blocked-roots/, the single source for the policy values: each entry is
# the base64 DER of one file there, and every file must be one of the two
# pinned forbidden roots (emSign ECC Root CA - G3, AAA Certificate Services).
# Output is one canonical line, the only form guard-chromium-policy.sh accepts:
#   {"CADistrustedCertificates":["<b64 DER>","<b64 DER>"]}
# Exit 0 written, 2 refused (nothing is written unless every input checks out).
set -eu
export LC_ALL=C
# Exit contract: 0 written, 2 refused. Any other non-zero exit (a producer
# aborting under set -e), a failed cleanup, or HUP/INT/TERM is 2. Cleanup runs
# with errexit OFF and its status is checked explicitly, so a failed rm can
# neither abort the trap nor leak its own status. The scratch directory is
# removed (and checked) BEFORE the policy is moved into place, so a cleanup
# failure leaves no output. A HUP/INT/TERM that arrives DURING the exit cleanup
# is recorded, never ignored: cleanup finishes (no writer work resumes) and the
# exit is 2. A signal during the pre-publish scratch removal stops the writer
# before the move (exit 2, nothing published); a signal during the move itself
# lets that mv finish, so the policy can be in place although the exit is 2.
work=""; tmp=""
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
        echo "policy writer: refused (exit 2): failed with exit $rc" >&2; status=2
    fi
    if [ -n "$tmp" ]; then
        rm -f "$tmp"; cleanup=$?
        # Once a trap has run, dash reports the EXIT status in $?, not rm's,
        # so after a signal only the existence check counts.
        [ -z "$interrupted" ] || cleanup=0
        if [ "$cleanup" -ne 0 ] || [ -e "$tmp" ] || [ -L "$tmp" ]; then
            echo "policy writer: CLEANUP FAILED, temp policy $tmp not removed (rm: $cleanup)" >&2; status=2
        fi
    fi
    if [ -n "$work" ]; then
        rm -rf "$work"; cleanup=$?
        # Once a trap has run, dash reports the EXIT status in $?, not rm's,
        # so after a signal only the existence check counts.
        [ -z "$interrupted" ] || cleanup=0
        if [ "$cleanup" -ne 0 ] || [ -e "$work" ] || [ -L "$work" ]; then
            echo "policy writer: CLEANUP FAILED, scratch $work not removed (rm: $cleanup)" >&2; status=2
        fi
    fi
    if [ -n "$interrupted" ]; then
        echo "policy writer: refused (exit 2): interrupted by SIG$interrupted during cleanup" >&2; status=2
    fi
    exit "$status"
}
on_signal() { echo "policy writer: refused, interrupted by SIG$1" >&2; exit 2; }
# Installed before the first producer, so even mktemp or dirname failing is 2.
trap 'on_exit "$?"' EXIT
trap 'on_signal HUP' HUP
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
[ "$#" -eq 2 ] || { echo 'usage: write-chromium-policy.sh BLOCKED_ROOTS_DIR OUTPUT_FILE' >&2; exit 2; }
roots=$1; out=$2
[ -d "$roots" ] && [ ! -L "$roots" ] || { echo "policy writer: $roots is not a directory" >&2; exit 2; }
outdir=$(dirname "$out") || { echo "policy writer: dirname failed on $out" >&2; exit 2; }
[ -d "$outdir" ] && [ ! -L "$outdir" ] || { echo "policy writer: $outdir is not a directory" >&2; exit 2; }
# Assign the trap-owned $work only after mktemp succeeds AND yields a real
# (non-symlink) directory: no other path may become an rm -rf target or have
# anything written beneath it.
scratch=$(mktemp -d) && [ -n "$scratch" ] && [ -d "$scratch" ] && [ ! -L "$scratch" ] \
    || { echo "policy writer: mktemp -d did not yield a scratch directory: '${scratch:-}'" >&2; exit 2; }
work=$scratch

g3=86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b
aaa=d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4
seen_g3=0; seen_aaa=0; files=0; list=''
for entry in "$roots"/* "$roots"/.[!.]*; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    case "$entry" in *.pem) ;; *) echo "policy writer: unexpected entry $entry" >&2; exit 2 ;; esac
    [ -f "$entry" ] && [ ! -L "$entry" ] || { echo "policy writer: $entry is not a regular file" >&2; exit 2; }
    files=$((files + 1))
    certs=$(grep -c -e '-----BEGIN CERTIFICATE-----' "$entry") \
        || { echo "policy writer: grep failed or found no certificate in $entry" >&2; exit 2; }
    [ "$certs" = 1 ] || { echo "policy writer: $entry must hold exactly one certificate" >&2; exit 2; }
    openssl x509 -in "$entry" -outform DER -out "$work/$files.der"
    openssl dgst -sha256 -r "$work/$files.der" > "$work/$files.sum"
    read -r sum rest < "$work/$files.sum" || { echo "policy writer: cannot read the digest of $entry" >&2; exit 2; }
    case "$sum" in
      "$g3") seen_g3=$((seen_g3 + 1)) ;;
      "$aaa") seen_aaa=$((seen_aaa + 1)) ;;
      *) echo "policy writer: $entry (sha256 $sum) is not a pinned forbidden root" >&2; exit 2 ;;
    esac
    openssl base64 -A -in "$work/$files.der" -out "$work/$files.b64"
    b64=$(cat "$work/$files.b64") || { echo "policy writer: cannot read the base64 of $entry" >&2; exit 2; }
    case "$b64" in ''|*[!A-Za-z0-9+/=]*) echo "policy writer: bad base64 for $entry" >&2; exit 2 ;; esac
    list="$list${list:+,}\"$b64\""
done
[ "$files" -eq 2 ] && [ "$seen_g3" -eq 1 ] && [ "$seen_aaa" -eq 1 ] \
    || { echo "policy writer: need exactly the two pinned roots, got files=$files g3=$seen_g3 aaa=$seen_aaa" >&2; exit 2; }
candidate="$outdir/.write-chromium-policy.$$"
[ ! -e "$candidate" ] && [ ! -L "$candidate" ] || { echo "policy writer: $candidate already exists" >&2; exit 2; }
tmp=$candidate
printf '{"CADistrustedCertificates":[%s]}\n' "$list" > "$tmp"
chmod 0644 "$tmp"
# Remove the scratch before publishing: a cleanup failure must leave no output.
if rm -rf "$work"; then cleanup=0; else cleanup=$?; fi
if [ "$cleanup" -ne 0 ] || [ -e "$work" ] || [ -L "$work" ]; then
    echo "policy writer: CLEANUP FAILED, scratch $work not removed (rm: $cleanup)" >&2; exit 2
fi
work=""
mv -f "$tmp" "$out"
tmp=""
echo "policy writer: wrote $out (2 entries: emSign ECC Root CA - G3, AAA Certificate Services)"
