//go:build androidhost

package gui

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const androidEditorTestKey = "fixture-manager-secret-that-must-not-leak"

func androidEditorRequest(method, path, key string) *http.Request {
	r := httptest.NewRequest(method, "http://127.0.0.1:3430"+path, strings.NewReader("{}"))
	if key != "" {
		r.Header.Set(androidManagerHeader, key)
	}
	return r
}

func androidEditorPage(apiCalls *int) http.Handler {
	raw := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/":
			if r.Header.Get("If-None-Match") != "" || r.Header.Get("Range") != "" {
				w.WriteHeader(http.StatusNotModified)
				return
			}
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write([]byte(`<html><script src="boot.js"></script></html>`))
		case r.URL.Path == "/boot.js":
			_, _ = w.Write([]byte(`window.bootPrefs = {};`))
		case r.URL.Path == "/api/redirect":
			http.Redirect(w, r, "https://foreign.example/landing", http.StatusFound)
		default:
			(*apiCalls)++
			w.WriteHeader(http.StatusNoContent)
		}
	})
	return mobileAPI(androidHostEditorProof(raw))
}

func mintAndroidEditorTicket(t *testing.T, h http.Handler) (string, string) {
	t.Helper()
	w := httptest.NewRecorder()
	h.ServeHTTP(w, androidEditorRequest(http.MethodPost, androidEditorMintPath, androidEditorTestKey))
	if w.Code != http.StatusOK || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("ticket mint failed: status=%d headers=%v", w.Code, w.Header())
	}
	var response struct {
		URL string `json:"url"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(response.URL)
	if err != nil {
		t.Fatal(err)
	}
	values := parsed.Query()["ticket"]
	if parsed.Scheme != "http" || parsed.Host != "127.0.0.1:3430" ||
		parsed.Path != androidEditorEntryPath || len(values) != 1 || !validAndroidEditorRandom(values[0]) {
		t.Fatalf("ticket URL contract differs: %s", response.URL)
	}
	return response.URL, values[0]
}

func exchangeAndroidEditorTicket(t *testing.T, h http.Handler, link string) string {
	t.Helper()
	entry := httptest.NewRequest(http.MethodGet, link, nil)
	entry.Host = "127.0.0.1:3430"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, entry)
	if w.Code != http.StatusOK ||
		w.Header().Get("Content-Type") != "text/html; charset=utf-8" ||
		w.Header().Get("Cache-Control") != "no-store" ||
		w.Header().Get("Referrer-Policy") != "no-referrer" ||
		w.Header().Get("X-Content-Type-Options") != "nosniff" ||
		w.Header().Get("Set-Cookie") != "" {
		t.Fatalf("ticket exchange boundary differs: status=%d headers=%v", w.Code, w.Header())
	}
	marker := `sessionStorage.setItem("magpie.androidEditorProof","`
	start := strings.Index(w.Body.String(), marker)
	if start < 0 {
		t.Fatal("browser proof not delivered")
	}
	start += len(marker)
	end := strings.Index(w.Body.String()[start:], `"`)
	if end != 64 {
		t.Fatal("browser proof has invalid length or response shape")
	}
	proof := w.Body.String()[start : start+end]
	if !validAndroidEditorRandom(proof) || strings.Contains(w.Body.String(), androidEditorTestKey) {
		t.Fatal("ticket exchange exposed the manager key or a noncanonical proof")
	}
	return proof
}

func TestAndroidHostBrowserEditorUsesShortLivedHeaderProofNotManagerCookie(t *testing.T) {
	now := time.Unix(123456789, 0)
	apiCalls := 0
	page := androidEditorPage(&apiCalls)
	h := androidHostEditorWithClock("3430", androidEditorTestKey, 0, nil, page, func() time.Time { return now })

	w := httptest.NewRecorder()
	h.ServeHTTP(w, androidEditorRequest(http.MethodPost, androidEditorMintPath, ""))
	if w.Code != http.StatusUnauthorized {
		t.Fatal("anonymous editor ticket minted")
	}
	r := androidEditorRequest(http.MethodPost, androidEditorMintPath, "")
	r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: androidEditorTestKey})
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized || w.Header().Get("Set-Cookie") != "" {
		t.Fatal("legacy manager cookie authenticated the Android host")
	}

	link, _ := mintAndroidEditorTicket(t, h)
	if strings.Contains(link, androidEditorTestKey) {
		t.Fatal("manager key reached the browser link")
	}
	root := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:3430/", nil)
	root.Header.Set("If-None-Match", `"stale"`)
	root.Header.Set("Range", "bytes=0-9")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, root)
	if w.Code != http.StatusOK ||
		strings.Contains(w.Body.String(), `<script src="boot.js"></script>`) ||
		!strings.Contains(w.Body.String(), `sessionStorage.getItem("magpie.androidEditorProof")`) ||
		!strings.Contains(w.Body.String(), `redirect:"error"`) ||
		!strings.Contains(w.Body.String(), `HTMLAnchorElement.prototype.click`) {
		t.Fatalf("editor shell did not install the proof bootstrap: status=%d", w.Code)
	}

	proof := exchangeAndroidEditorTicket(t, h, link)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, link, nil))
	if w.Code != http.StatusNotFound {
		t.Fatal("single-use browser ticket was replayed")
	}

	for _, request := range []*http.Request{
		httptest.NewRequest(http.MethodGet, "http://127.0.0.1:3430/api/plugins", nil),
		androidEditorRequest(http.MethodGet, "/api/plugins", ""),
	} {
		if strings.HasSuffix(request.URL.Path, "/api/plugins") {
			request.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: androidEditorTestKey})
		}
		w = httptest.NewRecorder()
		h.ServeHTTP(w, request)
		if w.Code != http.StatusUnauthorized || apiCalls != 0 {
			t.Fatal("API accepted no proof, a manager cookie, or another invalid credential")
		}
	}

	api := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:3430/api/plugins", nil)
	api.Header.Set(androidEditorProofHeader, proof)
	api.Header.Set("Origin", "http://127.0.0.1:3430")
	api.Header.Set("Sec-Fetch-Site", "same-origin")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, api)
	if w.Code != http.StatusNoContent || apiCalls != 1 {
		t.Fatalf("valid same-origin proof failed: status=%d calls=%d", w.Code, apiCalls)
	}

	crossSite := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:3430/api/plugins", nil)
	crossSite.Header.Set(androidEditorProofHeader, proof)
	crossSite.Header.Set("Origin", "https://foreign.example")
	crossSite.Header.Set("Sec-Fetch-Site", "cross-site")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, crossSite)
	if w.Code != http.StatusForbidden || apiCalls != 1 {
		t.Fatal("valid proof bypassed the upstream own-page guard")
	}

	boot := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:3430/boot.js", nil)
	boot.Header.Set(androidEditorProofHeader, proof)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, boot)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "bootPrefs") {
		t.Fatal("proof-authenticated boot.js failed")
	}

	redirect := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:3430/api/redirect", nil)
	redirect.Header.Set(androidEditorProofHeader, proof)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, redirect)
	if w.Code != http.StatusBadGateway || w.Header().Get("Location") != "" ||
		!strings.Contains(w.Body.String(), "redirect blocked") {
		t.Fatalf("proof was allowed through an API redirect: status=%d headers=%v", w.Code, w.Header())
	}

	now = now.Add(androidEditorProofTTL + time.Second)
	expired := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:3430/api/plugins", nil)
	expired.Header.Set(androidEditorProofHeader, proof)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, expired)
	if w.Code != http.StatusUnauthorized {
		t.Fatal("expired browser proof was accepted")
	}
}

func TestAndroidHostEditorExactHostMethodQueryAndNativeGuards(t *testing.T) {
	pageCalls := 0
	h := androidHostEditor("3430", androidEditorTestKey, 0, nil, androidEditorPage(&pageCalls))
	for _, tc := range []struct {
		name, method, path, host, key, origin, site, referer, fetchMode string
		want                                                             int
	}{
		{"wrong Host", http.MethodPost, androidEditorMintPath, "localhost:3430", androidEditorTestKey, "", "", "", "", http.StatusMisdirectedRequest},
		{"wrong method", http.MethodGet, androidEditorMintPath, "127.0.0.1:3430", androidEditorTestKey, "", "", "", "", http.StatusNotFound},
		{"query on mint", http.MethodPost, androidEditorMintPath + "?x=1", "127.0.0.1:3430", androidEditorTestKey, "", "", "", "", http.StatusNotFound},
		{"wrong manager", http.MethodPost, androidEditorMintPath, "127.0.0.1:3430", "wrong", "", "", "", "", http.StatusUnauthorized},
		{"foreign Origin", http.MethodPost, androidEditorMintPath, "127.0.0.1:3430", androidEditorTestKey, "https://foreign.example", "", "", "", http.StatusForbidden},
		{"foreign fetch site", http.MethodPost, androidEditorMintPath, "127.0.0.1:3430", androidEditorTestKey, "", "cross-site", "", "", http.StatusForbidden},
		{"browser Referer", http.MethodPost, androidEditorMintPath, "127.0.0.1:3430", androidEditorTestKey, "", "", "http://127.0.0.1:3430/", "", http.StatusForbidden},
		{"browser fetch mode", http.MethodPost, androidEditorMintPath, "127.0.0.1:3430", androidEditorTestKey, "", "", "", "cors", http.StatusForbidden},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest(tc.method, "http://127.0.0.1:3430"+tc.path, strings.NewReader("{}"))
			r.Host = tc.host
			if tc.key != "" {
				r.Header.Set(androidManagerHeader, tc.key)
			}
			if tc.origin != "" {
				r.Header.Set("Origin", tc.origin)
			}
			if tc.site != "" {
				r.Header.Set("Sec-Fetch-Site", tc.site)
			}
			if tc.referer != "" {
				r.Header.Set("Referer", tc.referer)
			}
			if tc.fetchMode != "" {
				r.Header.Set("Sec-Fetch-Mode", tc.fetchMode)
			}
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != tc.want {
				t.Fatalf("got HTTP %d, want %d", w.Code, tc.want)
			}
		})
	}
}

func TestAndroidHostEditorExpiredTicketAndProofCapacity(t *testing.T) {
	now := time.Unix(123456789, 0)
	pageCalls := 0
	h := androidHostEditorWithClock("3430", androidEditorTestKey, 0, nil, androidEditorPage(&pageCalls), func() time.Time { return now })

	expiredLink, _ := mintAndroidEditorTicket(t, h)
	now = now.Add(androidEditorTicketTTL + time.Second)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, expiredLink, nil))
	if w.Code != http.StatusNotFound {
		t.Fatal("expired one-use ticket was accepted")
	}

	for i := 0; i < androidEditorProofLimit; i++ {
		link, _ := mintAndroidEditorTicket(t, h)
		_ = exchangeAndroidEditorTicket(t, h, link)
	}
	overflowLink, _ := mintAndroidEditorTicket(t, h)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, overflowLink, nil))
	if w.Code != http.StatusServiceUnavailable {
		t.Fatal("browser proof capacity was not bounded")
	}
	now = now.Add(androidEditorProofTTL + time.Second)
	link, _ := mintAndroidEditorTicket(t, h)
	_ = exchangeAndroidEditorTicket(t, h, link)
}

func TestAndroidHostEditorProofMiddlewareAndSafeResourceBoundary(t *testing.T) {
	calls := 0
	protected := androidHostEditorProof(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.WriteHeader(http.StatusNoContent)
	}))
	w := httptest.NewRecorder()
	protected.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/", nil))
	if w.Code != http.StatusNoContent || calls != 1 {
		t.Fatal("defense middleware blocked the public page shell")
	}
	w = httptest.NewRecorder()
	protected.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/state", nil))
	if w.Code != http.StatusUnauthorized || calls != 1 {
		t.Fatal("defense middleware allowed a direct unmarked API request")
	}

	page := androidEditorPage(&calls)
	h := androidHostEditor("3430", androidEditorTestKey, 0, nil, page)
	icon := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:3430/api/icons/provider.svg", nil)
	icon.Header.Set("Sec-Fetch-Site", "same-origin")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, icon)
	if w.Code != http.StatusNoContent {
		t.Fatal("same-origin CSS/image asset could not load without a custom header")
	}
	cross := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:3430/api/icons/provider.svg", nil)
	cross.Header.Set("Sec-Fetch-Site", "cross-site")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, cross)
	if w.Code != http.StatusUnauthorized {
		t.Fatal("cross-site asset request bypassed the editor gate")
	}
}

func TestAndroidHostEditorCapabilitiesAndPluginRestoreStayAvailable(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(t.TempDir(), "config"))
	pageCalls := 0
	h := androidHostEditor("3430", androidEditorTestKey, 0, nil, androidEditorPage(&pageCalls))

	w := httptest.NewRecorder()
	h.ServeHTTP(w, androidEditorRequest(http.MethodGet, "/api/mobile/capabilities", androidEditorTestKey))
	var capabilities map[string]any
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &capabilities) != nil ||
		capabilities["fullAdmin"] != true || capabilities["androidHost"] != true || capabilities["webEditor"] != true {
		t.Fatalf("full-admin capabilities changed: status=%d body=%s", w.Code, w.Body.String())
	}

	r := androidEditorRequest(http.MethodPost, androidPluginGatewayRestorePath, "")
	r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: androidEditorTestKey})
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized || strings.Contains(w.Body.String(), androidEditorTestKey) {
		t.Fatal("plugin gateway restore accepted the old management cookie")
	}
	w = httptest.NewRecorder()
	h.ServeHTTP(w, androidEditorRequest(http.MethodGet, androidPluginGatewayRestorePath, androidEditorTestKey))
	if w.Code != http.StatusNotFound {
		t.Fatal("read request restored plugin gateway access")
	}
	r = androidEditorRequest(http.MethodPost, androidPluginGatewayRestorePath, androidEditorTestKey)
	r.Header.Set("Origin", "https://foreign.example")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatal("browser metadata authenticated native plugin restore")
	}
	w = httptest.NewRecorder()
	h.ServeHTTP(w, androidEditorRequest(http.MethodPost, androidPluginGatewayRestorePath, androidEditorTestKey))
	if w.Code != http.StatusOK || w.Body.String() != "{\"ok\":true}\n" ||
		strings.Contains(w.Body.String(), androidEditorTestKey) {
		t.Fatal("native plugin gateway restore failed or exposed a credential")
	}
}
