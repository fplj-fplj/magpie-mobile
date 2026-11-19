#!/usr/bin/env python3
"""Static UX guard: keyboard focus is not a requirement for automatic discovery."""
from pathlib import Path
p=Path('app/src/main/java/cc/fplj/magpiemobile/ProvidersPanel.kt')
s=p.read_text()
assert 'keyFocused' not in s, 'Key focus must not suppress or re-arm discovery'
assert 'delay(1000)' in s, 'Settle input before automatic metadata GET'
for field, source in [('requestedBaseForm', 'baseUrl'), ('requestedKey', 'key')]:
    assert f'val {field} = {source}' in s, 'Capture current draft before dispatching discovery'
assert 'requestedBaseForm, requestedKey, requestedProviderId, requestedRevision, requestedLocalHttp' in s, 'Discovery must use the captured draft, not saved gateway catalog'
assert 'withProviderRequest(owner)' in s, 'Discovery must bind the expected authenticated session'
assert 'NativeCoreSession.providerClient()' not in s, 'Provider operations must not resolve replacement credentials in an IO worker'
assert 'discoverModels(activeRequest)' in s, 'Automatic trigger must call read-only discovery'
assert 'keyFingerprint' in s and 'catalogSequence' in s, 'Bind late responses to current credentials/generation'
print('PASS: automatic model discovery does not wait for password-field blur')
