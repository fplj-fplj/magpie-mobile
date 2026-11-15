package plugin

import (
	"encoding/base64"
	"errors"
	"log"
	"strings"
	"sync"

	"github.com/yetone/magpie/internal/mobilepolicy"
)

var bridgeProviderMu sync.Mutex
var bridgeProvider func() (string, error)
var bridgeRefresh func() bool

// The higher management layer owns the credential store. These callbacks
// avoid an import cycle through access -> provider -> plugin.
func ConfigureAndroidHostBridge(provider func() (string, error), refresh func() bool) {
	bridgeProviderMu.Lock()
	bridgeProvider, bridgeRefresh = provider, refresh
	bridgeProviderMu.Unlock()
}

func bridgeSecret() (string, error) {
	bridgeProviderMu.Lock()
	provider := bridgeProvider
	bridgeProviderMu.Unlock()
	if provider == nil {
		return "", errors.New("plugin gateway access provider is unavailable")
	}
	return provider()
}

func androidHostBridgeNeedsRefresh() bool {
	if !mobilepolicy.FullAdmin {
		return false
	}
	bridgeProviderMu.Lock()
	refresh := bridgeRefresh
	bridgeProviderMu.Unlock()
	return refresh != nil && refresh()
}

// Called after an authenticated explicit recovery; current requests may drain
// on their old host while future requests use the new credential.
func AndroidHostBridgeChanged() {
	hostStale.Store(true)
	changed()
}

// mobileHostJS is the host script Bun runs: desktop and the legacy mobile
// build get the embedded host.js as it is; the Android host gets the
// bridge prelude before it.
func mobileHostJS() []byte {
	if !mobilepolicy.FullAdmin {
		return hostJS
	}
	key, err := bridgeSecret()
	if err != nil {
		log.Printf("plugin: Android host bridge: %v", err)
		return hostJS
	}
	return append(bridgePrelude(key), hostJS...)
}

// bridgePrelude is the script bytes put before host.js: the key rides as
// its base64, so a quote or a newline in a future secret form can't end
// the constant early, and the secret itself is never in the literal.
func bridgePrelude(key string) []byte {
	return []byte("// magpie's Android host bridge: the gateway key this host was\n" +
		"// issued, passed in as this closure's own constant — a dedicated key\n" +
		"// for the host alone, not a secret kept from it. Installed before host.js\n" +
		"// runs, so the host captures it as bunFetch\n" +
		"// and every wrapper above it keeps working: a fetch to the gateway's own\n" +
		"// origin carries the key only when the plugin sent none or the loopback\n" +
		"// placeholder; another origin, another header value, or a cross-site\n" +
		"// redirect goes exactly as the plugin wrote it.\n" +
		strings.Replace(bridgeJS, "__MAGPIE_BRIDGE_KEY__", base64.StdEncoding.EncodeToString([]byte(key)), 1))
}

const bridgeJS = `(() => {
  let key = ""
  try { key = atob("__MAGPIE_BRIDGE_KEY__") } catch { return }
  const addr = (typeof process !== "undefined" && process.env && process.env.MAGPIE_ADDR) || ""
  if (!key || !addr) return
  let origin = ""
  try { origin = new URL("http://" + addr).origin } catch { return }
  const wire = globalThis.fetch
  if (typeof wire !== "function") return
  const placeholder = /^Bearer\s+magpie$/i
  globalThis.fetch = function (input, init) {
    try {
      const u = new URL(input instanceof Request ? input.url : String(input instanceof URL ? input.href : input))
      if (u.origin === origin) {
        const head = new Headers(init && init.headers ? init.headers : input instanceof Request ? input.headers : undefined)
        const sent = (head.get("Authorization") || "").trim()
        if (sent === "" || placeholder.test(sent)) {
          head.set("Authorization", "Bearer " + key)
          return wire(input, { ...(init || {}), headers: head })
        }
      }
    } catch {}
    return wire(input, init)
  }
})()
`
