package plugin

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/yetone/magpie/internal/access"
	"github.com/yetone/magpie/internal/edit"
	"github.com/yetone/magpie/internal/mobilepolicy"
	"github.com/yetone/magpie/internal/settings"
)

// The Android plugin host bridge. Android's gateway takes a real key from
// every caller, while a plugin's fetches to magpie's own gateway carry the
// loopback placeholder ("Bearer magpie") or nothing at all, so a host that
// started would have every gateway-bound fetch refused. The bridge gives
// the host one key of its own: magpie issues a named, short-lived gateway
// key through access — an ordinary caller key, visible and revocable in
// the key list — and the prelude below hands that secret to host.js in a
// closure: a dedicated credential that cuts the placeholder off the user's
// other keys, not a secret kept from a plugin (a plugin shares the host's
// process and can read the cached host script). The wrapper it installs is
// what host.js captures as bunFetch, so it runs closest to the wire,
// beneath every wrapper the host itself and a plugin put around fetch, and
// touches nothing but a fetch to the gateway's own origin whose
// Authorization is missing or the loopback placeholder: a key the plugin
// chose — its own gateway key or its vendor's — goes exactly as it was
// sent, and a cross-site redirect never carries what the bridge added.

const bridgeName = "Android plugin host"

// Active keys renew when the host is next needed near expiry. Off or removed
// keys are never renewed implicitly; explicit recovery has its own admin action.
const bridgeTTL = 24 * time.Hour

var (
	bridgeMu   sync.Mutex
	bridgeGood time.Time
)

var errBridgeDisabled = errors.New("plugin gateway access is disabled; enable its key or restore it from plugin management")

type bridgeRecord struct {
	ID string `json:"id"`
}

func bridgeRecordPath() string {
	return filepath.Join(settings.Dir(), "android-plugin-gateway-key.json")
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

// The record keeps only the issued key's ID, so deletion is distinguishable
// from a first install. Every new host reads the real key store, without a
// credential cache that could conceal a user's change.
func bridgeSecret() (string, error) {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	bridgeGood = time.Time{}
	raw, err := os.ReadFile(bridgeRecordPath())
	if errors.Is(err, os.ErrNotExist) {
		return issueBridgeKeyLocked("")
	}
	if err != nil {
		return "", errors.New("plugin gateway access record cannot be read")
	}
	var record bridgeRecord
	if json.Unmarshal(raw, &record) != nil || record.ID == "" {
		return "", errors.New("plugin gateway access record is invalid")
	}
	keys, err := access.Export()
	if err != nil {
		return "", err
	}
	for _, k := range keys {
		if k.ID != record.ID {
			continue
		}
		if k.Off || k.Secret == "" {
			return "", errBridgeDisabled
		}
		if !k.Expires.IsZero() && !time.Now().Add(time.Minute).Before(k.Expires) {
			return issueBridgeKeyLocked(record.ID)
		}
		bridgeGood = k.Expires
		return k.Secret, nil
	}
	return "", errBridgeDisabled
}

func issueBridgeKeyLocked(previous string) (string, error) {
	issued, err := access.IssueKey(access.Issue{Name: bridgeName, TTL: bridgeTTL})
	if err != nil {
		return "", err
	}
	raw, err := json.Marshal(bridgeRecord{ID: issued.ID})
	if err == nil {
		err = edit.WriteAtomic(bridgeRecordPath(), raw)
	}
	if err != nil {
		_, _ = access.Update("remove-key", access.Change{Key: issued.ID})
		return "", errors.New("plugin gateway access record cannot be saved")
	}
	if previous != "" && previous != issued.ID {
		// IssueKey may already have pruned an expired key. Only our recorded
		// key is removed; a user-created key with the same name is untouched.
		_, _ = access.Update("remove-key", access.Change{Key: previous})
	}
	bridgeGood = issued.Expires
	return issued.Key, nil
}

// Checked while holding hostMu, before reusing a host. The bridge lock is never
// held while notifying or retiring a host.
func androidHostBridgeNeedsRefresh() bool {
	if !mobilepolicy.FullAdmin {
		return false
	}
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	return !bridgeGood.IsZero() && !time.Now().Add(time.Minute).Before(bridgeGood)
}

// RestoreAndroidHostBridge is called only by the authenticated, explicit admin
// action. It is the way to recover from deletion without undoing it in background.
func RestoreAndroidHostBridge() error {
	if !mobilepolicy.FullAdmin {
		return errBridgeDisabled
	}
	bridgeMu.Lock()
	previous := ""
	if raw, err := os.ReadFile(bridgeRecordPath()); err == nil {
		var record bridgeRecord
		if json.Unmarshal(raw, &record) == nil {
			previous = record.ID
		}
	}
	_, err := issueBridgeKeyLocked(previous)
	bridgeMu.Unlock()
	if err == nil {
		hostStale.Store(true)
		changed()
	}
	return err
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
