# Container trust policy

Removes two roots distrusted following the eMudhra intermediate-key compromise
from every image stage that fetches over TLS or ships: emSign ECC Root CA - G3
(DER SHA-256 `86a1ecba...6bbe6b`) and AAA Certificate Services
(`d7a7a0fb...699ef4`). Temurin base images still carry
G3 in the JDK/JRE `cacerts`, and a `--pull` rebuild does not remove it.

- `distrust-os-sources.sh`, `distrust-java-roots.sh`: byte-for-byte copies of
  Stafficy `modules/agent-runtime-workers/trust/` at
  `b573f8e70b23e0d8e495288ceb6012a8d2f1695f`. Do not edit them here; update
  from the origin and keep the hashes equal.
- `guard-no-forbidden.sh`: runs those scripts on scratch copies of the image
  stores. Both arms (OS bundle, Java `cacerts`) always run. It fails the build
  with exit 2 (VOID) if either arm could not complete (any producer or parse
  failure, a missing pinned control, or any non-zero exit of a copied script),
  otherwise exit 1 if either arm found a forbidden root or an unfiltered
  `.apk-new`/`.dpkg-new` bundle is left, otherwise exit 0.

Each stage upgrades the distro CA package, filters the OS CA sources, regenerates
the bundle, cleans `$JAVA_HOME/lib/security/cacerts`, and ends with the guard.
This directory is identical in LatteX and Sirentide.
