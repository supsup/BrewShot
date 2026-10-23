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
The files above are identical in LatteX and Sirentide; the Chromium files below
are BrewShot-only.

## Chromium

Chromium 149 (Alpine `chromium=149.0.7827.53-r0`) on Linux verifies against its
compiled-in Chrome Root Store, which carries emSign ECC Root CA - G3 as a trust
anchor, plus the NSS user database; it does not read the OS bundle, so the
filtering above never reaches the browser BrewShot points at user URLs. The
Chromium stages (`chrome-test` and runtime) therefore install the mandatory
enterprise policy `CADistrustedCertificates` (Chromium 132+, Linux included:
"any certificate with a matching SPKI will be distrusted") at
`/etc/chromium/policies/managed/stafficy-emudhra-distrust.json`, outside HOME
and the per-run profile.

- `blocked-roots/`: the two forbidden root PEMs, the single source of the policy.
- `write-chromium-policy.sh`: writes the policy as one canonical line from
  those PEMs; refuses (exit 2) unless the directory holds exactly the two
  pinned roots.
- `guard-chromium-policy.sh`: file check run right after install. Exit 2 (VOID)
  if the file is missing or is not the canonical line of canonical base64 DER
  certificates; else 1 if a forbidden root is not listed, the file is not a
  root-owned regular file, it or its directories are group/other-writable, or
  another file sits in `managed/`; else 0. Every producer whose output it
  uses is status-checked (a failing one is VOID even with complete output),
  and a scratch cleanup failure after a pass is VOID.
- `check-chromium-policy-loaded.sh` with `chromium-policy-reader.js`: runs
  BrewShot itself on `chrome://policy` and reads the policy state Chromium
  loaded (the page's own export JSON, browser `chrome` group only). Exit 1
  unless the entry is present there, mandatory, platform-sourced,
  machine-scoped, free of error/warning, not ignored (Chromium omits
  `ignored` unless it is true), and lists both roots; 2 if the run or the v2
  report cannot be evaluated (including a non-boolean `ignored`) or the
  Chromium version is not the pinned one. `chrome-test` runs it after `chromeTest`; the runtime
  stage runs it on the shipped jar.
