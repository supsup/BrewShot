#!/bin/sh
# Build guard for Chromium's managed CADistrustedCertificates policy file
# (written by write-chromium-policy.sh). Chromium 149 on Linux trusts its
# compiled-in Chrome Root Store, which still carries emSign ECC Root CA - G3,
# so filtering the OS bundle does not reach the browser; this file does.
#
# Exit 0 PASS, 1 FAIL, 2 VOID, like guard-no-forbidden.sh; VOID wins over FAIL.
#   VOID (2): the policy cannot be evaluated: file absent, unreadable, not the
#     one canonical line the writer emits (any other JSON shape, extra key,
#     whitespace, or second line included), an entry that is not canonical
#     base64 of one DER X.509 certificate, or a failing producer: every
#     command whose output is used has its exit status checked, and a failing
#     one is VOID even when its output looks complete. A scratch directory
#     that mktemp does not hand back as a real directory, or a scratch cleanup
#     that fails after an otherwise passing evaluation, is VOID too.
#   FAIL (1): evaluated and wrong: a forbidden root is not in the list; the
#     file is a symlink or not a regular file; the file, its directory or the
#     policies directory is not owned by OWNER_UID or is group/other-writable;
#     or the managed directory holds any other entry (another policy file
#     could set CADistrustedCertificates too).
# OWNER_UID defaults to 0; the image build passes nothing. Host controls pass
# their own uid because they cannot create root-owned files.
set -u
export LC_ALL=C
[ "$#" -ge 1 ] && [ "$#" -le 2 ] || { echo 'usage: guard-chromium-policy.sh POLICY_FILE [OWNER_UID]' >&2; exit 2; }
policy=$1; owner=${2:-0}
case "$owner" in ''|*[!0-9]*) echo 'GUARD chromium-policy VOID: OWNER_UID must be numeric' >&2; exit 2 ;; esac
g3=86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b
aaa=d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4
void=0; fail=0; work=""; entries=0
voidp() { echo "GUARD chromium-policy VOID: $*" >&2; void=1; }
failp() { echo "GUARD chromium-policy FAIL: $*" >&2; fail=1; }
# Removes the trap-owned scratch and checks it is gone; returns 1 (with a
# diagnostic) when it is not. Safe to call twice.
cleanup() {
    [ -n "$work" ] || return 0
    rm -rf "$work"; c=$?
    if [ "$c" -ne 0 ] || [ -e "$work" ] || [ -L "$work" ]; then
        echo "GUARD chromium-policy CLEANUP FAILED: scratch $work not removed (rm: $c)" >&2
        work=""; return 1
    fi
    work=""; return 0
}
finish() {
    trap '' HUP INT TERM
    # A cleanup failure turns a PASS into VOID; FAIL stays FAIL (the diagnostic
    # above is printed) and VOID stays VOID.
    if ! cleanup && [ "$void" -eq 0 ] && [ "$fail" -eq 0 ]; then
        voidp "scratch cleanup failed after a passing evaluation"
    fi
    if [ "$void" -eq 1 ]; then echo "GUARD chromium-policy VOID (exit 2): $policy" >&2; exit 2; fi
    if [ "$fail" -eq 1 ]; then echo "GUARD chromium-policy FAIL (exit 1): $policy" >&2; exit 1; fi
    echo "GUARD chromium-policy PASS: $policy distrusts emSign ECC Root CA - G3 and AAA Certificate Services ($entries entries, owner uid $owner, no group/other write)"
    exit 0
}
# Any exit that does not go through finish (a signal) still cleans up, and a
# cleanup failure can only raise the status to VOID, never lower it.
on_exit() {
    rc=$1
    trap '' HUP INT TERM
    if ! cleanup && [ "$rc" -eq 0 ]; then rc=2; fi
    exit "$rc"
}
trap 'on_exit "$?"' EXIT
trap 'exit 2' HUP INT TERM
# Assign the trap-owned $work only after mktemp succeeds AND yields a real
# (non-symlink) directory: no other path may become the rm -rf target or have
# anything written beneath it.
scratch=$(mktemp -d) && [ -n "$scratch" ] && [ -d "$scratch" ] && [ ! -L "$scratch" ] \
    || { echo "GUARD chromium-policy VOID: mktemp -d did not yield a scratch directory: '${scratch:-}'" >&2; exit 2; }
work=$scratch

# Ownership/mode of one path through find primaries (POSIX; same on BusyBox and BSD).
# Prints nothing and returns 0 when the path is owned by $owner and not g/o-writable.
meta() {
    path=$1; what=$2
    out=$(find "$path" -prune -user "$owner" -print) || { voidp "find failed on $what $path"; return; }
    [ -n "$out" ] || failp "$what $path is not owned by uid $owner"
    out=$(find "$path" -prune \( -perm -0020 -o -perm -0002 \) -print) || { voidp "find failed on $what $path"; return; }
    [ -z "$out" ] || failp "$what $path is group- or other-writable"
}

managed=$(dirname "$policy") || { voidp "dirname failed on $policy"; finish; }
policies=$(dirname "$managed") || { voidp "dirname failed on $managed"; finish; }
for d in "$managed" "$policies"; do
    if [ -L "$d" ] || [ ! -d "$d" ]; then
        if [ -e "$d" ] || [ -L "$d" ]; then failp "$d is not a plain directory"; else voidp "$d does not exist"; fi
    else
        meta "$d" directory
    fi
done

if [ ! -e "$policy" ] && [ ! -L "$policy" ]; then
    voidp "policy file $policy is missing"
    finish
fi
if [ -L "$policy" ] || [ ! -f "$policy" ]; then
    failp "policy file $policy is a symlink or not a regular file"
    finish
fi
meta "$policy" 'policy file'

# The managed directory must hold this one file and nothing else.
if [ -d "$managed" ] && [ ! -L "$managed" ]; then
    # The glob yields "$managed/<name>", so the policy itself is exactly
    # "$managed/<its basename>" (no second producer per entry).
    base=$(basename "$policy") || { voidp "basename failed on $policy"; finish; }
    for other in "$managed"/* "$managed"/.[!.]* "$managed"/..?*; do
        [ -e "$other" ] || [ -L "$other" ] || continue
        [ "$other" = "$managed/$base" ] || failp "managed policy directory also holds $other"
    done
fi

# Content: exactly one canonical line. Anything else is not evaluated.
if [ ! -r "$policy" ]; then voidp "cannot read $policy"; finish; fi
size=$(wc -c < "$policy") || { voidp "cannot size $policy"; finish; }
size=${size#"${size%%[![:space:]]*}"}  # wc pads with blanks on BSD; no extra producer
case "$size" in ''|*[!0-9]*) voidp "size of $policy unreadable"; finish ;; esac
size=$((size + 0))
if [ "$size" -le 0 ] || [ "$size" -gt 65536 ]; then voidp "$policy size $size is outside 1..65536"; finish; fi
lines=$(wc -l < "$policy") || { voidp "cannot count lines of $policy"; finish; }
lines=${lines#"${lines%%[![:space:]]*}"}  # wc pads with blanks on BSD; no extra producer
case "$lines" in ''|*[!0-9]*) voidp "line count of $policy unreadable"; finish ;; esac
if [ "$((lines + 0))" -ne 1 ]; then voidp "$policy is not exactly one newline-terminated line"; finish; fi
awk '
  $0 !~ /^\{"CADistrustedCertificates":\["[A-Za-z0-9+\/]+=*"(,"[A-Za-z0-9+\/]+=*")*\]\}$/ { exit 3 }
  {
    body=$0; sub(/^\{"CADistrustedCertificates":\["/, "", body); sub(/"\]\}$/, "", body)
    n=split(body, item, /","/)
    for (i=1; i<=n; i++) print item[i] > (out "/" i ".b64")
    print n > (out "/count")
  }
' out="$work" "$policy"
rc=$?
if [ "$rc" -ne 0 ] || [ ! -s "$work/count" ]; then
    voidp "$policy is not the canonical {\"CADistrustedCertificates\":[\"<base64>\",...]} line"
    finish
fi
entries=$(cat "$work/count") || { voidp "cannot read the entry count"; finish; }
case "$entries" in ''|*[!0-9]*) voidp "entry count unreadable"; finish ;; esac
have_g3=0; have_aaa=0; i=1
while [ "$i" -le "$entries" ]; do
    e="$work/$i"
    # Decode, require canonical round trip, require one DER certificate that
    # re-encodes byte for byte, then hash the DER.
    if ! tr -d '\n' < "$e.b64" > "$e.in" \
        || ! openssl base64 -d -A -in "$e.in" -out "$e.der" 2>/dev/null \
        || [ ! -s "$e.der" ] \
        || ! openssl base64 -A -in "$e.der" -out "$e.rt" \
        || ! rt=$(cat "$e.rt") || ! inb=$(cat "$e.in") || [ "$rt" != "$inb" ] \
        || ! openssl x509 -inform DER -in "$e.der" -outform DER -out "$e.x509" 2>/dev/null \
        || ! cmp -s "$e.der" "$e.x509" \
        || ! openssl dgst -sha256 -r "$e.der" > "$e.sum"; then
        voidp "entry $i is not canonical base64 of one DER X.509 certificate"
        i=$((i + 1)); continue
    fi
    if ! read -r sum rest < "$e.sum"; then
        voidp "entry $i digest unreadable"; i=$((i + 1)); continue
    fi
    case "$sum" in ''|*[!0-9a-f]*) voidp "entry $i digest '$sum' is not hex"; i=$((i + 1)); continue ;; esac
    [ "${#sum}" -eq 64 ] || { voidp "entry $i digest '$sum' is not a sha256"; i=$((i + 1)); continue; }
    case "$sum" in
      "$g3") have_g3=1; echo "GUARD chromium-policy: entry $i is emSign ECC Root CA - G3" ;;
      "$aaa") have_aaa=1; echo "GUARD chromium-policy: entry $i is AAA Certificate Services" ;;
      *) echo "GUARD chromium-policy: entry $i is an extra distrusted certificate sha256=$sum" ;;
    esac
    i=$((i + 1))
done
[ "$have_g3" -eq 1 ] || failp "emSign ECC Root CA - G3 ($g3) is not distrusted"
[ "$have_aaa" -eq 1 ] || failp "AAA Certificate Services ($aaa) is not distrusted"
finish
