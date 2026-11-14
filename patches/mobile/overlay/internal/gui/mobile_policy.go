package gui

import (
	"fmt"
	"net"
	"net/http"
	"path"
	"strings"

	"github.com/yetone/magpie/internal/mobilepolicy"
)

// mobileWebAddr fails closed before listening or starting any backend. Literal
// loopback IPs only: no wildcard, LAN interface or DNS name can expose admin.
func mobileWebAddr(addr string) error {
	if !mobilepolicy.Enabled {
		return nil
	}
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("mobile admin requires a literal loopback host:port: %w", err)
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return fmt.Errorf("mobile admin requires a literal loopback address")
	}
	return nil
}

// This is deliberately an exact method/path allowlist, not a prefix allowlist.
// Every desktop execution, native update, backup and future API is absent until
// separately reviewed. The original webGuard/webOwnPage remain outside it.
func mobileAPIAllowed(method, p string) bool {
	if method == http.MethodGet {
		switch p {
		case "/api/mobile/capabilities", "/api/caller-keys", "/api/caller-keys/models", "/api/state", "/api/providers",
			"/api/usage/requests", "/api/usage/heatmap", "/api/usage/quotas", "/api/usage/quotas/history",
			"/api/notices", "/api/usage", "/api/model-speeds", "/api/catalog/models":
			return true
		}
	}
	return mobileProviderAPIAllowed(method, p) || method == http.MethodPost && (p == "/api/caller-keys/add-key" || p == "/api/caller-keys/copy-key" || p == "/api/caller-keys/models-key" || p == "/api/caller-keys/aliases-key" || p == "/api/caller-keys/tier-key")
}

func mobileAPI(next http.Handler) http.Handler {
	if !mobilepolicy.Enabled {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		if p == "/api" || strings.HasPrefix(p, "/api/") {
			allowed := mobileAPIAllowed(r.Method, p)
			if mobilepolicy.FullAdmin {
				allowed = p != "/api" && !androidHostOwnedAPI(r.Method, p)
			}
			if r.URL.RawPath != "" || path.Clean(p) != p || !allowed {
				http.NotFound(w, r)
				return
			}
			if p == "/api/mobile/capabilities" {
				if r.Method != http.MethodGet { http.NotFound(w, r); return }
				w.Header().Set("Cache-Control", "no-store")
				if mobilepolicy.FullAdmin {
					writeJSON(w, map[string]any{
						"schemaVersion": 1, "coreVersion": Version, "mobile": false,
						"androidHost": true, "fullAdmin": true, "webEditor": true,
						"loopbackKeys": true, "debugEndpoints": false, "adminLoopbackOnly": true,
						"nativeAutoUpdate": false, "providerSchemaVersion": 1,
						"providerManagement": true, "providerModelDiscovery": true,
					})
					return
				}
				writeJSON(w, struct {
					SchemaVersion    int    `json:"schemaVersion"`
					CoreVersion      string `json:"coreVersion"`
					Mobile           bool   `json:"mobile"`
					LoopbackKeys     bool   `json:"loopbackKeys"`
					DebugEndpoints   bool   `json:"debugEndpoints"`
					AdminLoopbackOnly bool   `json:"adminLoopbackOnly"`
					NativeAutoUpdate bool   `json:"nativeAutoUpdate"`
					ProviderSchemaVersion int `json:"providerSchemaVersion"`
					ProviderManagement bool `json:"providerManagement"`
					ProviderModelDiscovery bool `json:"providerModelDiscovery"`
				}{1, Version, true, true, false, true, false, 1, true, true})
				return
			}
			if mobileProviderServe(w, r) { return }
		}
		next.ServeHTTP(w, r)
	})
}

// These actions belong to the Android service/installer. Configuration APIs
// (including update preferences and gateway-mode) retain their upstream behavior.
func androidHostOwnedAPI(method, p string) bool {
	switch p {
	case "/api/quit", "/api/restart", "/api/window/restart":
		return true
	}
	if method != http.MethodGet && method != http.MethodHead {
		return p == "/api/update" || strings.HasPrefix(p, "/api/update/") || p == "/api/cli" || p == "/api/settings/login"
	}
	return false
}
