package gui

import (
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"sync"
	"time"

	"github.com/yetone/magpie/internal/mobilepolicy"
	"github.com/yetone/magpie/internal/plugin"
)

const androidEditorMintPath = "/api/androidhost/browser-session"
const androidEditorEntryPath = "/androidhost/browser-entry"
const androidPluginGatewayRestorePath = "/api/androidhost/plugin-gateway-access/restore"

// Each web server owns its tickets. No long-lived management key is placed in
// an Android Intent, page URL, response body or log. Tickets are single-use.
func androidHostEditor(port, key string, keep time.Duration, box boxSignIn, page http.Handler) http.Handler {
	return androidHostEditorWithClock(port, key, keep, box, page, time.Now)
}

func androidHostEditorWithClock(port, key string, keep time.Duration, box boxSignIn, page http.Handler, clock func() time.Time) http.Handler {
	if !mobilepolicy.FullAdmin {
		return webGuard("magpie_web_"+port, key, keep, box, webOwnPage(page))
	}
	var mu sync.Mutex
	tickets := make(map[string]time.Time)
	guarded := webGuard("magpie_web_"+port, key, keep, box, webOwnPage(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == androidPluginGatewayRestorePath {
			if r.Method != http.MethodPost || r.URL.RawPath != "" || r.URL.RawQuery != "" {
				http.NotFound(w, r)
				return
			}
			w.Header().Set("Cache-Control", "no-store")
			if err := plugin.RestoreAndroidHostBridge(); err != nil {
				http.Error(w, "plugin gateway access could not be restored", http.StatusInternalServerError)
				return
			}
			writeJSON(w, map[string]bool{"ok": true})
			return
		}
		if r.URL.Path != androidEditorMintPath {
			page.ServeHTTP(w, r)
			return
		}
		if r.Method != http.MethodPost || r.URL.RawPath != "" || r.URL.RawQuery != "" {
			http.NotFound(w, r)
			return
		}
		var bytes [32]byte
		if _, err := rand.Read(bytes[:]); err != nil {
			http.Error(w, "could not open browser editor", http.StatusInternalServerError)
			return
		}
		ticket := hex.EncodeToString(bytes[:])
		now := clock()
		mu.Lock()
		for value, until := range tickets {
			if !until.After(now) {
				delete(tickets, value)
			}
		}
		if len(tickets) >= 8 {
			mu.Unlock()
			http.Error(w, "too many pending editor sessions", http.StatusTooManyRequests)
			return
		}
		tickets[ticket] = now.Add(30 * time.Second)
		mu.Unlock()
		w.Header().Set("Cache-Control", "no-store")
		writeJSON(w, map[string]string{"url": "http://127.0.0.1:" + port + androidEditorEntryPath + "?ticket=" + ticket})
	})))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != androidEditorEntryPath {
			guarded.ServeHTTP(w, r)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		query := r.URL.Query()
		values := query["ticket"]
		if r.Method != http.MethodGet || r.Host != "127.0.0.1:"+port || r.URL.RawPath != "" || len(query) != 1 || len(values) != 1 || len(values[0]) != 64 {
			http.NotFound(w, r)
			return
		}
		mu.Lock()
		until, exists := tickets[values[0]]
		delete(tickets, values[0])
		mu.Unlock()
		if !exists || !until.After(clock()) {
			http.Error(w, "editor link expired; open it again from the app", http.StatusUnauthorized)
			return
		}
		http.SetCookie(w, &http.Cookie{Name: "magpie_web_" + port, Value: key, Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode})
		http.Redirect(w, r, "/", http.StatusSeeOther)
	})
}
