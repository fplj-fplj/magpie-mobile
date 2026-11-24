//go:build mobile

package gui

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/yetone/magpie/internal/provider"
)

func TestMobileModelsDiscoveryHTTPAuthAndNoVendorPOST(t *testing.T) {
	const manager = "model-discovery-management-key-012345"
	var vendorGets, vendorPosts atomic.Int64
	vendor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "GET" { vendorGets.Add(1) } else { vendorPosts.Add(1) }
		if r.URL.Path != "/v1/models" || r.Header.Get("Cookie") != "" { t.Error("unexpected directory vendor request") }
		_, _ = w.Write([]byte(`{"data":[{"id":"catalog-choice","name":"Catalog choice"}]}`))
	}))
	defer vendor.Close()
	desktopCalls := 0
	h := webHandler("3430", manager, "", nil, mobileAPI(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { desktopCalls++; w.WriteHeader(418) })))
	body, _ := json.Marshal(map[string]any{"chat": vendor.URL+"/v1", "key": "temporary-directory-secret-012345"})
	call := func(method, p, cookie, payload string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, "http://127.0.0.1:3430"+p, strings.NewReader(payload))
		if cookie != "" { r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: cookie}) }
		w := httptest.NewRecorder(); h.ServeHTTP(w, r); return w
	}
	for _, cookie := range []string{"", "wrong"} { if call("POST", "/api/mobile/providers/models", cookie, string(body)).Code != 401 { t.Fatal("discovery bypassed management cookie") } }
	if vendorGets.Load() != 0 { t.Fatal("unauthorized request contacted vendor") }
	result := call("POST", "/api/mobile/providers/models", manager, string(body))
	var catalog struct { Models []struct { ID, Name string }; BaseURL string `json:"baseUrl"` }
	if result.Code != 200 || json.Unmarshal(result.Body.Bytes(), &catalog) != nil || len(catalog.Models) != 1 || catalog.Models[0].ID != "catalog-choice" || catalog.BaseURL != vendor.URL+"/v1" { t.Fatal("directory HTTP response contract failed") }
	if strings.Contains(result.Body.String(), "temporary-directory-secret-012345") || vendorGets.Load() != 1 || vendorPosts.Load() != 0 || desktopCalls != 0 { t.Fatal("directory leaked key or used unsafe vendor/desktop action") }
	for _, method := range []string{"GET", "HEAD", "DELETE", "OPTIONS"} { if call(method, "/api/mobile/providers/models", manager, string(body)).Code != 404 { t.Fatal("non-POST discovery transport accepted") } }
	for _, path := range []string{"/api/provider/list", "/api/provider/test", "/api/provider/detect", "/api/mobile/providers/test", "/api/mobile/providers/detect"} { if call("POST", path, manager, string(body)).Code != 404 { t.Fatal("generic provider action opened") } }
	bad, _ := json.Marshal(map[string]any{"chat": vendor.URL+"/v1", "test": true})
	if call("POST", "/api/mobile/providers/models", manager, string(bad)).Code != 400 || vendorGets.Load() != 1 { t.Fatal("unknown discovery field contacted vendor") }
}

func TestMobileModelsDiscoveryHTTPFixedErrorAndCORS(t *testing.T) {
	const manager = "model-discovery-management-key-012345"
	const secret = "supplier-sensitive-error-key-012345"
	for _, status := range []int{401, 429} {
		var calls atomic.Int64
		vendor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.WriteHeader(status); _, _ = w.Write([]byte(secret+" free-form vendor response")) }))
		h := webHandler("3430", manager, "", nil, mobileAPI(http.NotFoundHandler()))
		body, _ := json.Marshal(map[string]string{"chat": vendor.URL+"/v1"})
		r := httptest.NewRequest("POST", "http://127.0.0.1:3430/api/mobile/providers/models", strings.NewReader(string(body)))
		r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: manager})
		w := httptest.NewRecorder(); h.ServeHTTP(w, r)
		if w.Code != status || calls.Load() != 1 || strings.Contains(w.Body.String(), secret) || strings.Contains(w.Body.String(), "free-form") || strings.Contains(w.Body.String(), vendor.URL) { t.Fatal("upstream status error leaked credentials/body or retried") }
		r = httptest.NewRequest("POST", "http://127.0.0.1:3430/api/mobile/providers/models", strings.NewReader(string(body)))
		r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: manager})
		r.Header.Set("Origin", "https://foreign.invalid"); r.Header.Set("Sec-Fetch-Site", "cross-site")
		w = httptest.NewRecorder(); h.ServeHTTP(w, r)
		if w.Code != 403 || calls.Load() != 1 { t.Fatal("discovery bypassed original CORS") }
		vendor.Close()
	}
}

func TestMobileModelsDiscoveryHTTPServerPinStrictJSON(t *testing.T) {
	const manager = "pin-discovery-management-key-982374"
	key := "directory-pin-private-"+t.Name()
	var calls atomic.Int64
	vendor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Method != "GET" || r.Header.Get("Cookie") != "" || r.Header.Get("Authorization") != "Bearer "+key { t.Error("pin decode changed vendor credential boundary") }
		_, _ = w.Write([]byte(`{"data":[]}`))
	}))
	defer vendor.Close()
	h := webHandler("3430", manager, "", nil, mobileAPI(http.NotFoundHandler()))
	call := func(payload string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "http://127.0.0.1:3430/api/mobile/providers/models", strings.NewReader(payload))
		r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: manager})
		w := httptest.NewRecorder(); h.ServeHTTP(w, r); return w
	}
	chat, _ := json.Marshal(vendor.URL+"/v1")
	encodedKey, _ := json.Marshal(key)
	prefix := `{"chat":`+string(chat)+`,"key":`+string(encodedKey)
	// Missing and explicit empty pin are both valid JSON contracts. An empty
	// draft pin deliberately selects ordinary TLS (or allowed loopback HTTP).
	for _, payload := range []string{prefix+`}`, prefix+`,"serverPin":""}`} {
		if result := call(payload); result.Code != 200 { t.Fatal("supported empty/omitted serverPin rejected") }
	}
	if calls.Load() != 2 { t.Fatal("valid pin JSON did not perform one read per request") }
	pin := strings.Repeat("a", 64)
	for _, field := range []string{
		`"serverPin":null`, `"serverPin":"","serverPin":""`, `"ServerPin":""`, `"serverpin":""`,
		`"serverPin":1`, `"serverPin":true`, `"serverPin":{}`, `"serverPin":[]`,
		`"serverPin":"","unknown":true`, `"serverPin":"`+pin+`"`,
		`"serverPin":"`+strings.ToUpper(pin)+`"`, `"serverPin":" `+pin+`"`,
	} {
		result := call(prefix+`,`+field+`}`)
		if result.Code != 400 || calls.Load() != 2 { t.Fatal("invalid pin JSON contacted vendor or bypassed strict decoder") }
		if strings.Contains(result.Body.String(), pin) || strings.Contains(result.Body.String(), key) || strings.Contains(result.Body.String(), vendor.URL) { t.Fatal("pin JSON error leaked private fields") }
	}
	for _, payload := range []string{prefix+`,"serverPin":""} {}`, `{"chat":null,"serverPin":""}`, `{"chat":`+string(chat)+`,"key":null,"serverPin":""}`} {
		if call(payload).Code != 400 || calls.Load() != 2 { t.Fatal("pin route accepted null or trailing JSON") }
	}
}

func TestMobileModelsDiscoveryHTTPServerPinExactFieldDecode(t *testing.T) {
	// TLS networking is exercised with the provider's production transport
	// factory and the whole exported-RPC host smoke; this checks exact JSON
	// presence semantics without opening a second transport policy in GUI.
	pin := strings.Repeat("ab", 32)
	for _, entry := range []struct { payload string; omitted bool; pin string }{
		{`{"chat":"https://models.pin.test/v1"}`, true, ""},
		{`{"chat":"https://models.pin.test/v1","serverPin":""}`, false, ""},
		{`{"chat":"https://models.pin.test/v1","serverPin":"`+pin+`"}`, false, pin},
	} {
		var in provider.MobileModelDiscoveryRequest
		r := httptest.NewRequest("POST", "http://127.0.0.1:3430/api/mobile/providers/models", strings.NewReader(entry.payload))
		if err := mobileProviderDecode(httptest.NewRecorder(), r, &in, []string{"chat", "key", "id", "revision", "serverPin"}); err != nil { t.Fatal("valid draft pin did not decode") }
		if (in.ServerPin == nil) != entry.omitted || in.ServerPin != nil && *in.ServerPin != entry.pin { t.Fatal("decoder conflated omitted/clear/replacement pin") }
	}
}

func TestMobileModelsDiscoveryHTTPTLSPinErrorProjection(t *testing.T) {
	w := httptest.NewRecorder()
	mobileProviderFailure(w, &provider.MobileProviderModelsError{Status: 502, Code: "provider_models_tls_pin", Message: "model directory TLS certificate pin verification failed"})
	if w.Code != 502 || w.Header().Get("X-Magpie-Mobile-Error-Code") != "provider_models_tls_pin" || strings.Contains(w.Body.String(), "models.pin.test") { t.Fatal("pin failure lost its fixed public status or code") }
	var body struct { Error struct { Code, Message string } }
	if json.Unmarshal(w.Body.Bytes(), &body) != nil || body.Error.Code != "provider_models_tls_pin" || body.Error.Message != "model directory TLS certificate pin verification failed" { t.Fatal("pin failure JSON contract differs") }
}
