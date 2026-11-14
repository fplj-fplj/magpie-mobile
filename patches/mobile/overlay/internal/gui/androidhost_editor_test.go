//go:build androidhost

package gui

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func androidEditorRequest(method, path, key string) *http.Request {
	r := httptest.NewRequest(method, "http://127.0.0.1:3430"+path, strings.NewReader("{}"))
	if key != "" { r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: key}) }
	return r
}

func TestAndroidHostFullAPIRetainsAuthAndOriginGuard(t *testing.T) {
	const key = "android-editor-test-key-0123456789"
	calls := 0
	h := webHandler("3430", key, 0, nil, mobileAPI(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.WriteHeader(http.StatusNoContent)
	})))
	for _, path := range []string{"/api/settings/gateway-mode", "/api/provider/save", "/api/library/servers/save", "/api/plugins/install"} {
		for _, cookie := range []string{"", "wrong", key} {
			w := httptest.NewRecorder()
			h.ServeHTTP(w, androidEditorRequest("POST", path, cookie))
			want := http.StatusUnauthorized
			if cookie == key { want = http.StatusNoContent }
			if w.Code != want { t.Fatalf("%s: got %d want %d", path, w.Code, want) }
		}
	}
	if calls != 4 { t.Fatal("unauthenticated request reached API") }
	r := androidEditorRequest("POST", "/api/settings/gateway-mode", key)
	r.Header.Set("Sec-Fetch-Site", "cross-site")
	w := httptest.NewRecorder(); h.ServeHTTP(w, r)
	if w.Code != http.StatusForbidden { t.Fatal("cross-origin write accepted") }
	for _, path := range []string{"/api/quit", "/api/window/restart", "/api/update/install", "/api/settings/login"} {
		w := httptest.NewRecorder(); h.ServeHTTP(w, androidEditorRequest("POST", path, key))
		if w.Code != http.StatusNotFound { t.Fatal("host-owned action reached core", path) }
	}
}

func TestAndroidHostBrowserTicketIsSingleUseAndExpires(t *testing.T) {
	const key = "android-editor-test-key-0123456789"
	now := time.Unix(123456789, 0)
	h := androidHostEditorWithClock("3430", key, 0, nil, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}), func() time.Time { return now })
	mint := func() string {
		w := httptest.NewRecorder(); h.ServeHTTP(w, androidEditorRequest("POST", androidEditorMintPath, key))
		if w.Code != http.StatusOK || strings.Contains(w.Body.String(), key) { t.Fatal("mint failed or leaked management key") }
		var value struct { URL string `json:"url"` }
		if err := json.Unmarshal(w.Body.Bytes(), &value); err != nil { t.Fatal(err) }
		u, err := url.Parse(value.URL); if err != nil { t.Fatal(err) }
		return u.RequestURI()
	}
	w := httptest.NewRecorder(); h.ServeHTTP(w, androidEditorRequest("POST", androidEditorMintPath, ""))
	if w.Code != http.StatusUnauthorized { t.Fatal("anonymous ticket minted") }
	link := mint()
	w = httptest.NewRecorder(); h.ServeHTTP(w, androidEditorRequest("GET", link, ""))
	if w.Code != http.StatusSeeOther || w.Header().Get("Location") != "/" || w.Header().Get("Referrer-Policy") != "no-referrer" {
		t.Fatal("ticket did not redirect to a clean page")
	}
	cookies := w.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].HttpOnly || cookies[0].Value != key { t.Fatal("browser cookie missing") }
	w = httptest.NewRecorder(); h.ServeHTTP(w, androidEditorRequest("GET", link, ""))
	if w.Code != http.StatusUnauthorized { t.Fatal("ticket replay accepted") }
	link = mint(); now = now.Add(31*time.Second)
	w = httptest.NewRecorder(); h.ServeHTTP(w, androidEditorRequest("GET", link, ""))
	if w.Code != http.StatusUnauthorized { t.Fatal("expired ticket accepted") }
}

func TestAndroidHostCapabilitiesAdvertiseFullCore(t *testing.T) {
	const key = "android-editor-test-key-0123456789"
	h := webHandler("3430", key, 0, nil, mobileAPI(http.NotFoundHandler()))
	w := httptest.NewRecorder(); h.ServeHTTP(w, androidEditorRequest("GET", "/api/mobile/capabilities", key))
	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil { t.Fatal(err) }
	if got["mobile"] != false || got["fullAdmin"] != true || got["webEditor"] != true || got["androidHost"] != true { t.Fatal("full core not advertised") }
}
