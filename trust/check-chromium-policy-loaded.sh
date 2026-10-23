#!/bin/sh
# Proves Chromium LOADED the distrust policy, which guard-chromium-policy.sh
# (a file check) cannot. Launches this image's Chromium through BrewShot
# itself, opens chrome://policy, and reads the browser's own loaded-policy
# state with chromium-policy-reader.js. Then, from what Chromium reports:
# CADistrustedCertificates is present, mandatory, from the platform source,
# with no error, warning or ignored flag, and its values decode to DER whose
# SHA-256 includes both forbidden roots.
#
# Exit 0 PASS, 1 FAIL (Chromium reports the policy absent from the browser
# (chrome) group or not in force: not mandatory, not platform, not machine
# scope, an error or warning, ignored=true, or a forbidden root missing from
# its loaded value), 2 VOID (BrewShot or the reader did not complete, its
# report is not the exact v2 grammar, a producer failed, or scratch cleanup
# failed after a passing evaluation). HUP/INT/TERM before the verdict is VOID
# (2). A signal DURING the exit cleanup is recorded, never ignored: cleanup
# finishes, then PASS becomes VOID (2), FAIL stays 1 and VOID stays 2, each
# with a diagnostic. IGNORED is absent|false|true|invalid:
# absent and false are not ignored (Chromium omits the key unless true), true
# is FAIL, invalid (any non-boolean) is VOID.
# CLASSPATH is the BrewShot jar or classes directory. EXPECTED_VERSION, when
# given, must prefix the Chromium version the page reports (binds the check to
# the pinned package).
set -u
export LC_ALL=C
[ "$#" -ge 1 ] && [ "$#" -le 2 ] || { echo 'usage: check-chromium-policy-loaded.sh CLASSPATH [EXPECTED_VERSION]' >&2; exit 2; }
cp=$1; expected=${2:-}
g3=86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b
aaa=d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4
work=""; interrupted=""
void() { echo "CHECK chromium-policy-loaded VOID (exit 2): $*" >&2; exit 2; }
fail() { echo "CHECK chromium-policy-loaded FAIL (exit 1): $*" >&2; exit 1; }
# Removes the trap-owned scratch and checks it is gone. Safe to call twice.
cleanup() {
    [ -n "$work" ] || return 0
    rm -rf "$work"; c=$?
    # Once a trap has run, dash reports the EXIT status in $?, not rm's, so
    # after a signal only the existence check counts.
    [ -z "$interrupted" ] || c=0
    if [ "$c" -ne 0 ] || [ -e "$work" ] || [ -L "$work" ]; then
        echo "CHECK chromium-policy-loaded CLEANUP FAILED: scratch $work not removed (rm: $c)" >&2
        work=""; return 1
    fi
    work=""; return 0
}
# A cleanup failure raises PASS to VOID; FAIL stays 1 (diagnostic printed).
# A signal during this cleanup is recorded, never ignored (first one wins).
on_exit() {
    rc=$1
    trap 'interrupted=${interrupted:-HUP}' HUP
    trap 'interrupted=${interrupted:-INT}' INT
    trap 'interrupted=${interrupted:-TERM}' TERM
    if ! cleanup && [ "$rc" -eq 0 ]; then
        echo "CHECK chromium-policy-loaded VOID (exit 2): scratch cleanup failed" >&2; rc=2
    fi
    if [ -n "$interrupted" ]; then
        case "$rc" in
          0) echo "CHECK chromium-policy-loaded VOID (exit 2): SIG$interrupted during scratch cleanup" >&2; rc=2 ;;
          1) echo "CHECK chromium-policy-loaded: SIG$interrupted during scratch cleanup is reported; FAIL (exit 1) stands: Chromium reported the policy absent or not in force" >&2 ;;
          *) echo "CHECK chromium-policy-loaded: SIG$interrupted during scratch cleanup is reported; VOID stands" >&2 ;;
        esac
    fi
    exit "$rc"
}
trap 'on_exit "$?"' EXIT
trap 'exit 2' HUP INT TERM
here=$(dirname "$0") || void "dirname failed on $0"
reader="$here/chromium-policy-reader.js"
[ -e "$cp" ] || void "classpath $cp does not exist"
[ -s "$reader" ] || void "reader $reader is missing"
# Assign the trap-owned $work only after mktemp succeeds AND yields a real
# (non-symlink) directory.
scratch=$(mktemp -d) && [ -n "$scratch" ] && [ -d "$scratch" ] && [ ! -L "$scratch" ] \
    || void "mktemp -d did not yield a scratch directory: '${scratch:-}'"
work=$scratch
mkdir "$work/home" "$work/tmp" || void 'cannot create scratch HOME/tmp'

# Scratch HOME/XDG/tmp: the browser leaves nothing in the image, and no NSS db
# or profile from an earlier run can feed the result.
deadline=''
if command -v timeout >/dev/null 2>&1; then deadline='timeout 120'
else echo 'CHECK chromium-policy-loaded: no timeout(1); relying on BrewShot deadlines' >&2; fi
# shellcheck disable=SC2086
HOME="$work/home" XDG_CONFIG_HOME="$work/home/.config" XDG_CACHE_HOME="$work/home/.cache" \
XDG_DATA_HOME="$work/home/.local/share" \
    $deadline java "-Djava.io.tmpdir=$work/tmp" -cp "$cp" com.brewshot.Main chrome://policy \
    --settle 100 --eval-file "$reader" -o "$work/policy.png" \
    > "$work/stdout" 2> "$work/stderr"
rc=$?
if [ "$rc" -ne 0 ]; then
    cat "$work/stderr" >&2
    void "brewshot exited $rc reading chrome://policy"
fi
cat "$work/stdout" || void 'cannot replay the reader report'

# Strict v2 grammar, line by line. Present form:
#   READER, VERSION, ENTRY present, LEVEL, SOURCE, SCOPE, ERROR, WARNING,
#   IGNORED, COUNT n, n VALUE lines, END.  Absent form: READER, VERSION, ENTRY absent, END.
awk -v out="$work" '
  function bad() { failed=1; exit 3 }
  NR==1 { if ($0 != "READER brewshot-chromium-policy v2") bad(); next }
  NR==2 { if ($0 !~ /^VERSION [^ ].*$/) bad(); v=$0; sub(/^VERSION /,"",v); print v > (out "/version"); next }
  NR==3 { if ($0 == "ENTRY absent") { state="absent"; next }
          if ($0 == "ENTRY present") { state="present"; next } bad() }
  state=="absent" { if ($0 != "END" || done) bad(); done=1; next }
  NR>=4 && NR<=9 {
    split("LEVEL SOURCE SCOPE ERROR WARNING IGNORED", key, " ")
    k=key[NR-3]
    if (index($0, k " ") != 1 || length($0) <= length(k) + 1) bad()
    print substr($0, length(k) + 2) > (out "/" k); next
  }
  NR==10 { if ($0 !~ /^COUNT [0-9]+$/) bad(); count=substr($0,7)+0; next }
  NR>10 && NR<=10+count {
    if ($0 !~ /^VALUE [A-Za-z0-9+\/]+=*$/) bad()
    print substr($0,7) > (out "/value." (NR-10)); next
  }
  NR==11+count && state=="present" { if ($0 != "END") bad(); done=1; next }
  { bad() }
  END { if (failed || !done) exit 3; print state > (out "/state"); if (state=="present") print count > (out "/count") }
' "$work/stdout" || void "reader output is not the v2 grammar"

version=$(cat "$work/version") || void 'no version'
echo "CHECK chromium-policy-loaded: Chromium reports version $version"
if [ -n "$expected" ]; then
    case "$version" in "$expected"|"$expected "*) ;; *) void "Chromium version '$version' does not start with pinned $expected" ;; esac
fi
state=$(cat "$work/state") || void 'no state'
[ "$state" = present ] || fail "Chromium has no CADistrustedCertificates policy loaded"
for k in LEVEL SOURCE SCOPE ERROR WARNING IGNORED; do
    eval "$k=\$(cat \"\$work/$k\")" || void "no $k"
done
echo "CHECK chromium-policy-loaded: level=$LEVEL source=$SOURCE scope=$SCOPE error=$ERROR warning=$WARNING ignored=$IGNORED"
# A malformed report is VOID before any FAIL is decided.
case "$IGNORED" in absent|false|true) ;; *) void "reader reports IGNORED '$IGNORED' (not a boolean)" ;; esac
[ "$LEVEL" = mandatory ] || fail "policy level is $LEVEL, not mandatory"
[ "$SOURCE" = platform ] || fail "policy source is $SOURCE, not platform"
[ "$SCOPE" = machine ] || fail "policy scope is $SCOPE, not machine"
[ "$ERROR" = - ] || fail "Chromium reports a policy error: $ERROR"
[ "$WARNING" = - ] || fail "Chromium reports a policy warning: $WARNING"
[ "$IGNORED" != true ] || fail "Chromium reports the policy ignored"
count=$(cat "$work/count") || void 'no count'
case "$count" in ''|*[!0-9]*) void "count '$count' unreadable" ;; esac
have_g3=0; have_aaa=0; i=1
while [ "$i" -le "$count" ]; do
    e="$work/value.$i"
    tr -d '\n' < "$e" > "$e.in" && openssl base64 -d -A -in "$e.in" -out "$e.der" 2>/dev/null \
        && [ -s "$e.der" ] && openssl dgst -sha256 -r "$e.der" > "$e.sum" \
        || void "loaded value $i does not decode"
    read -r sum rest < "$e.sum" || void "loaded value $i digest unreadable"
    case "$sum" in "$g3") have_g3=1 ;; "$aaa") have_aaa=1 ;; esac
    i=$((i + 1))
done
[ "$have_g3" -eq 1 ] || fail "loaded policy does not distrust emSign ECC Root CA - G3"
[ "$have_aaa" -eq 1 ] || fail "loaded policy does not distrust AAA Certificate Services"
cleanup || void 'scratch cleanup failed'
echo "CHECK chromium-policy-loaded PASS: Chromium $version has CADistrustedCertificates in force (mandatory, platform, OK) with emSign ECC Root CA - G3 and AAA Certificate Services"
