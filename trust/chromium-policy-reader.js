// Evaluated by BrewShot (--eval-file) on chrome://policy. Asks the page's own
// policy backend for the policies Chromium has LOADED (the JSON behind the
// page's Copy/Export buttons, BrowserProxy.getPolicies), not the file on disk,
// and prints the CADistrustedCertificates entry for check-chromium-policy-loaded.sh.
// Platform policy loads asynchronously at startup, so it polls for up to 8 s.
// Any failure throws, which BrewShot reports as a non-zero exit (VOID).
// Only the browser's own group is read: key "chrome" (kChromePoliciesId in
// components/policy/core/browser/policy_conversions.h), the Chrome-domain
// namespace that browser-wide policy like CADistrustedCertificates lives in.
// An entry under any other group (extension, precedence, ...) is not in force
// for the browser and is reported absent.
(async () => {
  const clean = (v) => (v === undefined || v === null || v === '')
    ? '-' : String(v).replace(/[^A-Za-z0-9 ._:,()+-]/g, '_').slice(0, 400);
  const {BrowserProxy} = await import('chrome://policy/browser_proxy.js');
  const {GetPoliciesReason} = await import('chrome://policy/policy.mojom-webui.js');
  const deadline = Date.now() + 8000;
  let doc = null;
  let entry = null;
  for (;;) {
    doc = JSON.parse(await BrowserProxy.getPolicies(GetPoliciesReason.kCopy));
    // kPolicyValuesKey is "policyValues" in Chromium 149 and "policyGroups" by 155
    // (components/policy/core/browser/webui/policy_webui_constants.cc); exactly one.
    const named = ['policyValues', 'policyGroups'].filter((k) => doc && k in doc);
    if (named.length !== 1) {
      throw new Error('getPolicies JSON has ' + named.length + ' of policyValues/policyGroups');
    }
    const groups = doc[named[0]];
    if (!groups || typeof groups !== 'object') {
      throw new Error('getPolicies ' + named[0] + ' is not an object');
    }
    const own = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
    const chrome = own(groups, 'chrome') ? groups.chrome : null;
    if (!chrome || typeof chrome !== 'object' || !chrome.policies || typeof chrome.policies !== 'object') {
      throw new Error('getPolicies ' + named[0] + ' has no chrome (browser) group with a policies object');
    }
    entry = own(chrome.policies, 'CADistrustedCertificates') ? chrome.policies.CADistrustedCertificates : null;
    if (entry !== null && (typeof entry !== 'object' || Array.isArray(entry))) {
      throw new Error('chrome CADistrustedCertificates entry is not an object');
    }
    if (entry || Date.now() > deadline) { break; }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  const version = doc.chromeMetadata && doc.chromeMetadata.version;
  const out = ['READER brewshot-chromium-policy v2', 'VERSION ' + clean(version)];
  if (!entry) {
    out.push('ENTRY absent', 'END');
    return out.join('\n');
  }
  const values = Array.isArray(entry.value) ? entry.value : null;
  // Chromium sets "ignored" only when true (policy_conversions_client.cc:
  // `if (policy.ignored()) value.Set("ignored", true);`), so a policy in force
  // has no key. v2 reports exactly what is there: absent, the boolean false,
  // the boolean true, or invalid (any other value: the string 'true', a number,
  // null, ...), which the shell treats as a malformed report (VOID).
  const ignored = !Object.prototype.hasOwnProperty.call(entry, 'ignored') ? 'absent'
    : entry.ignored === false ? 'false' : entry.ignored === true ? 'true' : 'invalid';
  out.push('ENTRY present',
    'LEVEL ' + clean(entry.level),
    'SOURCE ' + clean(entry.source),
    'SCOPE ' + clean(entry.scope),
    'ERROR ' + clean(entry.error),
    'WARNING ' + clean(entry.warning),
    'IGNORED ' + ignored,
    'COUNT ' + (values ? values.length : 'not-a-list'));
  for (const v of values || []) {
    out.push((typeof v === 'string' && /^[A-Za-z0-9+/]+=*$/.test(v)) ? 'VALUE ' + v : 'VALUE-INVALID');
  }
  out.push('END');
  return out.join('\n');
})()
