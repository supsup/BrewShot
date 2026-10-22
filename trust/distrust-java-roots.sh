#!/bin/sh
# b882214a: operate on this image's default cacerts, never a host-mounted store.
# Certificate identity is DER SHA-256, independent of vendor alias or subject label.
set -eu
[ "$#" -eq 1 ] && [ -s "$1" ] || { echo 'usage: distrust-java-roots.sh IMAGE_CACERTS' >&2; exit 2; }
store=$1
umask 077
export LC_ALL=C
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM

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
