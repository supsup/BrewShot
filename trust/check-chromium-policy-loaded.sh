#!/bin/sh
# Proves Chromium LOADED the distrust policy, which guard-chromium-policy.sh
# (a file check) cannot. Launches this image's Chromium through BrewShot
# itself, opens chrome://policy, and reads the browser's own loaded-policy
# state with chromium-policy-reader.js. Then, from what Chromium reports:
# CADistrustedCertificates is present, mandatory, from the platform source,
# with no error, warning or ignored flag, and its values decode to DER whose
# SHA-256 includes both forbidden roots.
#
# Exit 0 PASS, 1 FAIL (Chromium reports the policy absent or not in force, or
# a forbidden root missing from its loaded value), 2 VOID (BrewShot or the
# reader did not complete, or its report is not the exact v1 grammar).
# CLASSPATH is the BrewShot jar or classes directory. EXPECTED_VERSION, when
# given, must prefix the Chromium version the page reports (binds the check to
# the pinned package).
set -u
export LC_ALL=C
[ "$#" -ge 1 ] && [ "$#" -le 2 ] || { echo 'usage: check-chromium-policy-loaded.sh CLASSPATH [EXPECTED_VERSION]' >&2; exit 2; }
cp=$1; expected=${2:-}
here=$(dirname "$0")
reader="$here/chromium-policy-reader.js"
g3=86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b
aaa=d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4
void() { echo "CHECK chromium-policy-loaded VOID (exit 2): $*" >&2; exit 2; }
fail() { echo "CHECK chromium-policy-loaded FAIL (exit 1): $*" >&2; exit 1; }
[ -e "$cp" ] || void "classpath $cp does not exist"
[ -s "$reader" ] || void "reader $reader is missing"
work=$(mktemp -d) || void 'cannot create a scratch directory'
trap 'rm -rf "$work"' EXIT
trap 'exit 2' HUP INT TERM
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
cat "$work/stdout"

# Strict v1 grammar, line by line. Present form:
#   READER, VERSION, ENTRY present, LEVEL, SOURCE, SCOPE, ERROR, WARNING,
#   IGNORED, COUNT n, n VALUE lines, END.  Absent form: READER, VERSION, ENTRY absent, END.
awk -v out="$work" '
  function bad() { failed=1; exit 3 }
  NR==1 { if ($0 != "READER brewshot-chromium-policy v1") bad(); next }
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
' "$work/stdout" || void "reader output is not the v1 grammar"

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
[ "$LEVEL" = mandatory ] || fail "policy level is $LEVEL, not mandatory"
[ "$SOURCE" = platform ] || fail "policy source is $SOURCE, not platform"
[ "$ERROR" = - ] || fail "Chromium reports a policy error: $ERROR"
[ "$WARNING" = - ] || fail "Chromium reports a policy warning: $WARNING"
[ "$IGNORED" = false ] || fail "Chromium reports the policy ignored"
count=$(cat "$work/count") || void 'no count'
have_g3=0; have_aaa=0; i=1
while [ "$i" -le "$count" ]; do
    e="$work/value.$i"
    tr -d '\n' < "$e" > "$e.in" && openssl base64 -d -A -in "$e.in" -out "$e.der" 2>/dev/null \
        && [ -s "$e.der" ] && openssl dgst -sha256 -r "$e.der" > "$e.sum" \
        || void "loaded value $i does not decode"
    read -r sum rest < "$e.sum"
    case "$sum" in "$g3") have_g3=1 ;; "$aaa") have_aaa=1 ;; esac
    i=$((i + 1))
done
[ "$have_g3" -eq 1 ] || fail "loaded policy does not distrust emSign ECC Root CA - G3"
[ "$have_aaa" -eq 1 ] || fail "loaded policy does not distrust AAA Certificate Services"
echo "CHECK chromium-policy-loaded PASS: Chromium $version has CADistrustedCertificates in force (mandatory, platform, OK) with emSign ECC Root CA - G3 and AAA Certificate Services"
