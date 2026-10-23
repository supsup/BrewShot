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
[ "$#" -eq 2 ] || { echo 'usage: write-chromium-policy.sh BLOCKED_ROOTS_DIR OUTPUT_FILE' >&2; exit 2; }
roots=$1; out=$2
[ -d "$roots" ] && [ ! -L "$roots" ] || { echo "policy writer: $roots is not a directory" >&2; exit 2; }
outdir=$(dirname "$out")
[ -d "$outdir" ] && [ ! -L "$outdir" ] || { echo "policy writer: $outdir is not a directory" >&2; exit 2; }
tmp="$outdir/.write-chromium-policy.$$"
work=$(mktemp -d)
trap 'status=$?; rm -rf "$work"; rm -f "$tmp"; trap - EXIT; if [ "$status" -ne 0 ]; then exit 2; fi' EXIT
trap 'exit 2' HUP INT TERM

g3=86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b
aaa=d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4
seen_g3=0; seen_aaa=0; files=0; list=''
for entry in "$roots"/* "$roots"/.[!.]*; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    case "$entry" in *.pem) ;; *) echo "policy writer: unexpected entry $entry" >&2; exit 2 ;; esac
    [ -f "$entry" ] && [ ! -L "$entry" ] || { echo "policy writer: $entry is not a regular file" >&2; exit 2; }
    files=$((files + 1))
    [ "$(grep -c -e '-----BEGIN CERTIFICATE-----' "$entry")" -eq 1 ] \
        || { echo "policy writer: $entry must hold exactly one certificate" >&2; exit 2; }
    openssl x509 -in "$entry" -outform DER -out "$work/$files.der"
    openssl dgst -sha256 -r "$work/$files.der" > "$work/$files.sum"
    read -r sum rest < "$work/$files.sum"
    case "$sum" in
      "$g3") seen_g3=$((seen_g3 + 1)) ;;
      "$aaa") seen_aaa=$((seen_aaa + 1)) ;;
      *) echo "policy writer: $entry (sha256 $sum) is not a pinned forbidden root" >&2; exit 2 ;;
    esac
    openssl base64 -A -in "$work/$files.der" -out "$work/$files.b64"
    b64=$(cat "$work/$files.b64")
    case "$b64" in ''|*[!A-Za-z0-9+/=]*) echo "policy writer: bad base64 for $entry" >&2; exit 2 ;; esac
    list="$list${list:+,}\"$b64\""
done
[ "$files" -eq 2 ] && [ "$seen_g3" -eq 1 ] && [ "$seen_aaa" -eq 1 ] \
    || { echo "policy writer: need exactly the two pinned roots, got files=$files g3=$seen_g3 aaa=$seen_aaa" >&2; exit 2; }
printf '{"CADistrustedCertificates":[%s]}\n' "$list" > "$tmp"
chmod 0644 "$tmp"
mv -f "$tmp" "$out"
echo "policy writer: wrote $out (2 entries: emSign ECC Root CA - G3, AAA Certificate Services)"
