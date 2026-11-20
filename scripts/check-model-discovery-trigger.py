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
assert 'withProviderRequest(sessionKey)' in s, 'Discovery must bind the full authenticated connection key'
assert 'NativeCoreSession.providerClient()' not in s, 'Provider operations must not resolve replacement credentials in an IO worker'
assert 'discoverModels(activeRequest)' in s, 'Automatic trigger must call read-only discovery'
assert 'keyFingerprint' in s and 'catalogSequence' in s, 'Bind late responses to current credentials/generation'
assert 'serverPin = requestedServerPin' in s, 'Discovery must use the visible captured draft pin, including empty'
assert 'currentServerPin = serverPin' in s and 'normalizedServerPin, sessionKey' in s, 'Bind pin changes to request currentness'
assert 'if (!requestIsCurrent()) return@LaunchedEffect' in s, 'Recheck draft pin after debounce and before RPC'
worker = s.split('val catalog = withProviderRequest(sessionKey) { client ->', 1)[1].split('val requestStillCurrent', 1)[0]
assert worker.index('if (!requestIsCurrent()) throw CancellationException') < worker.index('val activeRequest = ProviderModelDiscoveryRequest('), 'Queued IO must recheck draft pin before constructing/dispatching discovery'
assert 'if (requestIsCurrent()) discoveryError' in s, 'Late errors must pass the same pin fence as success'
pin_input = s.split('value = serverPin,', 1)[1].split('enabled = !busy,', 1)[0]
assert pin_input.index('++catalogSequence') < pin_input.index('serverPin = nextPin'), 'Fence callbacks in input event before recomposition'
print('PASS: automatic model discovery does not wait for password-field blur')
