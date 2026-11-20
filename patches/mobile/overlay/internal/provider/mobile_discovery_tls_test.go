//go:build mobile

package provider

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"errors"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// Every fixture has a distinct leaf. The normal URL validator remains in the
// production path; only the private factory's dialing and trust roots differ.
func mobileDiscoveryTLSCertificate(t *testing.T) tls.Certificate {
	t.Helper()
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil { t.Fatal(err) }
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if err != nil { t.Fatal(err) }
	template := &x509.Certificate{
		SerialNumber: serial, Subject: pkix.Name{CommonName: "models.pin.test"},
		DNSNames: []string{"models.pin.test"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IsCA: true, BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, public, private)
	if err != nil { t.Fatal(err) }
	leaf, err := x509.ParseCertificate(der)
	if err != nil { t.Fatal(err) }
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: private, Leaf: leaf}
}

func mobileDiscoveryTLSPin(cert *x509.Certificate) string {
	digest := sha256.Sum256(cert.Raw)
	return hex.EncodeToString(digest[:])
}

func mobileDiscoveryTLSFixture(t *testing.T, handler http.HandlerFunc) (*httptest.Server, string, mobileDiscoveryNetwork) {
	t.Helper()
	server := httptest.NewUnstartedServer(handler)
	server.Config.ErrorLog = log.New(io.Discard, "", 0)
	server.TLS = &tls.Config{Certificates: []tls.Certificate{mobileDiscoveryTLSCertificate(t)}, MinVersion: tls.VersionTLS12}
	server.StartTLS()
	t.Cleanup(server.Close)
	base := strings.Replace(server.URL, "127.0.0.1", "models.pin.test", 1)
	network := mobileDiscoveryNetwork{dialContext: func(ctx context.Context, kind, address string) (net.Conn, error) {
		u, err := url.Parse(base)
		if err != nil || address != u.Host { return nil, errors.New("unexpected model directory dial") }
		return (&net.Dialer{Timeout: mobileModelsTimeout}).DialContext(ctx, kind, server.Listener.Addr().String())
	}}
	return server, base, network
}

func mobileDiscoveryTrustedNetwork(server *httptest.Server, network mobileDiscoveryNetwork) mobileDiscoveryNetwork {
	network.rootCAs = x509.NewCertPool()
	network.rootCAs.AddCert(server.Certificate())
	return network
}

func TestMobileModelsDiscoveryTLSExactLeafPinAndSystemTrust(t *testing.T) {
	mobileProviderHome(t)
	var calls, authorizations atomic.Int64
	key := "directory-pin-private-key-928473"
	server, base, untrusted := mobileDiscoveryTLSFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") == "Bearer "+key { authorizations.Add(1) } else { t.Error("wrong directory authorization") }
		if r.Method != "GET" || r.URL.Path != "/v1/models" || r.Header.Get("Cookie") != "" { t.Error("unexpected pinned directory request") }
		_, _ = w.Write([]byte(`{"data":[{"id":"pinned-model"}]}`))
	})
	pin := mobileDiscoveryTLSPin(server.Certificate())
	wrong := strings.Repeat("0", 64)
	trusted := mobileDiscoveryTrustedNetwork(server, untrusted)
	for _, entry := range []struct { name string; pin *string; network mobileDiscoveryNetwork; code string }{
		{"correct self-signed pin", &pin, untrusted, ""},
		{"wrong self-signed pin", &wrong, untrusted, "provider_models_tls_pin"},
		{"trusted CA with wrong pin", &wrong, trusted, "provider_models_tls_pin"},
		{"explicit empty pin with trusted CA", mobileDiscoveryStringPointer(""), trusted, ""},
		{"explicit empty pin with untrusted CA", mobileDiscoveryStringPointer(""), untrusted, "provider_models_tls_certificate"},
		{"omitted pin with untrusted CA", nil, untrusted, "provider_models_tls_certificate"},
	} {
		t.Run(entry.name, func(t *testing.T) {
			before, beforeAuth := calls.Load(), authorizations.Load()
			catalog, err := mobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: base+"/v1", Key: &key, ServerPin: entry.pin}, entry.network)
			if entry.code == "" {
				if err != nil || len(catalog.Models) != 1 || catalog.Models[0].ID != "pinned-model" || calls.Load()-before != 1 || authorizations.Load()-beforeAuth != 1 { t.Fatal("valid pinned/system TLS directory failed", err) }
			} else {
				mobileDiscoveryStatus(t, err, 502, entry.code)
				if calls.Load() != before || authorizations.Load() != beforeAuth { t.Fatal("HTTP or Authorization reached vendor before TLS verification") }
				for _, secret := range []string{key, pin, wrong, "models.pin.test", server.Listener.Addr().String()} {
					if strings.Contains(err.Error(), secret) { t.Fatal("TLS error exposed private connection details") }
				}
			}
		})
	}
	if _, err := os.Stat(Path()); !os.IsNotExist(err) { t.Fatal("TLS discovery created canonical config") }
}

func TestMobileModelsDiscoveryTLSStoredPinInheritClearAndReplace(t *testing.T) {
	mobileProviderHome(t)
	var calls atomic.Int64
	var authorization atomic.Value
	server, base, network := mobileDiscoveryTLSFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1); authorization.Store(r.Header.Get("Authorization"))
		_, _ = w.Write([]byte(`{"data":[{"id":"stored-pin-model"}]}`))
	})
	pin := mobileDiscoveryTLSPin(server.Certificate())
	draft := mobileCreateDraft(); draft.Chat = base+"/v1"; draft.ServerPin = pin
	snapshot, err := MobileCreateProvider(draft)
	if err != nil { t.Fatal(err) }
	before, err := os.ReadFile(Path()); if err != nil { t.Fatal(err) }
	info, err := os.Stat(Path()); if err != nil { t.Fatal(err) }
	request := MobileModelDiscoveryRequest{Chat: base+"/v1", ID: &snapshot.ID, Revision: &snapshot.Revision}
	if _, err := mobileDiscoverModels(context.Background(), request, network); err != nil || calls.Load() != 1 || authorization.Load().(string) != "Bearer "+draft.Key { t.Fatal("stored pin/key did not inherit together", err) }
	newKey := "read-only-override-key-928344"
	request.Key = &newKey
	if _, err := mobileDiscoverModels(context.Background(), request, network); err != nil || calls.Load() != 2 || authorization.Load().(string) != "Bearer "+newKey { t.Fatal("same-origin explicit key lost stored pin", err) }
	request.Key = mobileDiscoveryStringPointer("")
	request.ServerPin = mobileDiscoveryStringPointer("")
	_, err = mobileDiscoverModels(context.Background(), request, network)
	mobileDiscoveryStatus(t, err, 502, "provider_models_tls_certificate")
	if calls.Load() != 2 { t.Fatal("explicit empty pin silently reused stored trust") }
	if _, err := mobileDiscoverModels(context.Background(), request, mobileDiscoveryTrustedNetwork(server, network)); err != nil || calls.Load() != 3 { t.Fatal("explicit empty pin did not restore normal trusted TLS", err) }
	wrong := strings.Repeat("f", 64)
	request.ServerPin = &wrong
	_, err = mobileDiscoverModels(context.Background(), request, mobileDiscoveryTrustedNetwork(server, network))
	mobileDiscoveryStatus(t, err, 502, "provider_models_tls_pin")
	if calls.Load() != 3 { t.Fatal("explicit pin override ignored") }
	after, err := os.ReadFile(Path()); if err != nil { t.Fatal(err) }
	afterInfo, err := os.Stat(Path()); if err != nil { t.Fatal(err) }
	if !bytes.Equal(before, after) || !info.ModTime().Equal(afterInfo.ModTime()) { t.Fatal("pin override changed provider config") }
	stored, err := MobileEditableProvider(snapshot.ID)
	if err != nil || stored.Revision != snapshot.Revision || stored.ServerPin != pin { t.Fatal("pin discovery changed canonical revision or trust") }
	// A new draft pin can replace a stale/broken stored pin for this read only.
	updated, err := MobileEditProvider(MobileProviderEdit{ID: snapshot.ID, Revision: snapshot.Revision, ServerPin: &wrong})
	if err != nil { t.Fatal(err) }
	request.Revision = &updated.Revision; request.ServerPin = &pin
	if _, err := mobileDiscoverModels(context.Background(), request, network); err != nil || calls.Load() != 4 { t.Fatal("explicit draft pin could not replace stored pin", err) }
	stored, err = MobileEditableProvider(snapshot.ID)
	if err != nil || stored.Revision != updated.Revision || stored.ServerPin != wrong { t.Fatal("draft pin replacement persisted") }
}

func TestMobileModelsDiscoveryTLSChangedOriginNeverInheritsPin(t *testing.T) {
	mobileProviderHome(t)
	var oldCalls, nextCalls atomic.Int64
	old, oldBase, _ := mobileDiscoveryTLSFixture(t, func(w http.ResponseWriter, r *http.Request) { oldCalls.Add(1) })
	key := "changed-origin-explicit-key-934769"
	next, nextBase, network := mobileDiscoveryTLSFixture(t, func(w http.ResponseWriter, r *http.Request) {
		nextCalls.Add(1)
		if r.Header.Get("Authorization") != "Bearer "+key { t.Error("changed origin got stored key") }
		_, _ = w.Write([]byte(`{"data":[]}`))
	})
	oldPin, nextPin := mobileDiscoveryTLSPin(old.Certificate()), mobileDiscoveryTLSPin(next.Certificate())
	if oldPin == nextPin { t.Fatal("TLS fixtures must use different leaf certificates") }
	draft := mobileCreateDraft(); draft.Chat = oldBase+"/v1"; draft.ServerPin = oldPin
	snapshot, err := MobileCreateProvider(draft); if err != nil { t.Fatal(err) }
	before, err := os.ReadFile(Path()); if err != nil { t.Fatal(err) }
	request := MobileModelDiscoveryRequest{Chat: nextBase+"/v1", ID: &snapshot.ID, Revision: &snapshot.Revision}
	_, err = mobileDiscoverModels(context.Background(), request, network)
	mobileDiscoveryStatus(t, err, 400, "provider_models_origin_changed")
	if nextCalls.Load() != 0 || oldCalls.Load() != 0 { t.Fatal("cross-origin stored credential read contacted vendor") }
	request.Key = &key
	if _, err := mobileDiscoverModels(context.Background(), request, mobileDiscoveryTrustedNetwork(next, network)); err != nil || nextCalls.Load() != 1 { t.Fatal("new-key cross-origin read inherited old pin", err) }
	_, err = mobileDiscoverModels(context.Background(), request, network)
	mobileDiscoveryStatus(t, err, 502, "provider_models_tls_certificate")
	if nextCalls.Load() != 1 { t.Fatal("cross-origin nil pin bypassed system TLS") }
	request.ServerPin = &nextPin
	if _, err := mobileDiscoverModels(context.Background(), request, network); err != nil || nextCalls.Load() != 2 { t.Fatal("explicit cross-origin draft pin was not followed", err) }
	request.ServerPin = &oldPin
	_, err = mobileDiscoverModels(context.Background(), request, mobileDiscoveryTrustedNetwork(next, network))
	mobileDiscoveryStatus(t, err, 502, "provider_models_tls_pin")
	if nextCalls.Load() != 2 { t.Fatal("explicit wrong cross-origin pin was ignored") }
	var httpCalls atomic.Int64
	httpServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		httpCalls.Add(1)
		if r.Header.Get("Authorization") != "Bearer "+key { t.Error("HTTP changed origin got stored key") }
		_, _ = w.Write([]byte(`{"data":[]}`))
	})); defer httpServer.Close()
	request.Chat = httpServer.URL+"/v1"; request.ServerPin = nil
	if _, err := MobileDiscoverModels(context.Background(), request); err != nil || httpCalls.Load() != 1 { t.Fatal("changed-origin HTTP inherited unrelated stored pin", err) }
	request.ServerPin = &nextPin
	if _, err := MobileDiscoverModels(context.Background(), request); !errors.Is(err, ErrMobileProviderInvalid) || httpCalls.Load() != 1 { t.Fatal("explicit HTTP pin was accepted") }
	after, err := os.ReadFile(Path()); if err != nil || !bytes.Equal(before, after) { t.Fatal("cross-origin pin reads changed canonical config") }
}

func TestMobileModelsDiscoveryTLSStaleRevisionRejectsBeforeDial(t *testing.T) {
	mobileProviderHome(t)
	var calls atomic.Int64
	server, base, network := mobileDiscoveryTLSFixture(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	pin := mobileDiscoveryTLSPin(server.Certificate())
	draft := mobileCreateDraft(); draft.Chat = base+"/v1"; draft.ServerPin = pin
	snapshot, err := MobileCreateProvider(draft); if err != nil { t.Fatal(err) }
	newPin := strings.Repeat("a", 64)
	if _, err := MobileEditProvider(MobileProviderEdit{ID: snapshot.ID, Revision: snapshot.Revision, ServerPin: &newPin}); err != nil { t.Fatal(err) }
	var dials atomic.Int64
	dial := network.dialContext
	network.dialContext = func(ctx context.Context, kind, address string) (net.Conn, error) { dials.Add(1); return dial(ctx, kind, address) }
	for _, explicit := range []*string{nil, &pin, mobileDiscoveryStringPointer("")} {
		_, err := mobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: base+"/v1", ID: &snapshot.ID, Revision: &snapshot.Revision, Key: mobileDiscoveryStringPointer("new-draft-key-239487"), ServerPin: explicit}, network)
		if !errors.Is(err, ErrMobileProviderRevision) || calls.Load() != 0 || dials.Load() != 0 { t.Fatal("stale revision fetched key/pin or contacted vendor") }
	}
}

func TestMobileModelsDiscoveryTLSRejectsMalformedAndHTTPPinsBeforeDial(t *testing.T) {
	mobileProviderHome(t)
	var dials atomic.Int64
	network := mobileDiscoveryNetwork{dialContext: func(context.Context, string, string) (net.Conn, error) { dials.Add(1); return nil, errors.New("unexpected dial") }}
	pin := strings.Repeat("ab", 32)
	for _, invalid := range []string{strings.ToUpper(pin), strings.Repeat("g", 64), pin[:63], pin+"0", " "+pin, pin+"\n", " "} {
		_, err := mobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: "https://models.pin.test/v1", ServerPin: &invalid}, network)
		if !errors.Is(err, ErrMobileProviderInvalid) || dials.Load() != 0 { t.Fatal("malformed pin normalized, accepted or dialed") }
	}
	_, err := mobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: "http://127.0.0.1:42711/v1", ServerPin: &pin}, network)
	if !errors.Is(err, ErrMobileProviderInvalid) || dials.Load() != 0 { t.Fatal("HTTP pin was silently discarded") }
	if _, err := os.Stat(Path()); !os.IsNotExist(err) { t.Fatal("invalid pin created canonical config") }
	// Older/external metadata can already contain a pin on allowed HTTP. A
	// same-origin inherited pin must also fail closed before sending its key.
	draft := mobileCreateDraft(); draft.ServerPin = pin
	snapshot, err := MobileCreateProvider(draft); if err != nil { t.Fatal(err) }
	_, err = mobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: draft.Chat, ID: &snapshot.ID, Revision: &snapshot.Revision}, network)
	if !errors.Is(err, ErrMobileProviderInvalid) || dials.Load() != 0 { t.Fatal("inherited HTTP pin was silently discarded") }
}

func TestMobileModelsDiscoveryTLS404FallbackKeepsPinAndAuthorization(t *testing.T) {
	mobileProviderHome(t)
	var calls atomic.Int64
	key := "pinned-fallback-private-key-923490"
	server, base, network := mobileDiscoveryTLSFixture(t, func(w http.ResponseWriter, r *http.Request) {
		n := calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer "+key || r.Header.Get("Cookie") != "" { t.Error("fallback key or management cookie boundary failed") }
		if n == 1 { if r.URL.Path != "/v1/models" { t.Error("bad first pinned path") }; w.Header().Set("Connection", "close"); w.WriteHeader(404); return }
		if n != 2 || r.URL.Path != "/models" { t.Error("unbounded pinned fallback") }
		_, _ = w.Write([]byte(`{"data":[]}`))
	})
	pin := mobileDiscoveryTLSPin(server.Certificate())
	catalog, err := mobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: base+"/v1", Key: &key, ServerPin: &pin}, network)
	if err != nil || calls.Load() != 2 || catalog.BaseURL != base { t.Fatal("self-signed pinned fallback failed", err) }
}

func TestMobileModelsDiscoveryTLS404FallbackRejectsChangedLeaf(t *testing.T) {
	mobileProviderHome(t)
	first, replacement := mobileDiscoveryTLSCertificate(t), mobileDiscoveryTLSCertificate(t)
	var calls atomic.Int64
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Connection", "close"); w.WriteHeader(404)
	}))
	server.Config.ErrorLog = log.New(io.Discard, "", 0)
	server.TLS = &tls.Config{MinVersion: tls.VersionTLS12, GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
		if calls.Load() == 0 { return &first, nil }; return &replacement, nil
	}}
	server.StartTLS(); defer server.Close()
	base := strings.Replace(server.URL, "127.0.0.1", "models.pin.test", 1)
	network := mobileDiscoveryNetwork{dialContext: func(ctx context.Context, kind, address string) (net.Conn, error) { return (&net.Dialer{Timeout: mobileModelsTimeout}).DialContext(ctx, kind, server.Listener.Addr().String()) }}
	// Even explicitly trusting the replacement must not override the pinned leaf.
	network.rootCAs = x509.NewCertPool(); network.rootCAs.AddCert(replacement.Leaf)
	pin := mobileDiscoveryTLSPin(first.Leaf)
	_, err := mobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: base+"/v1", Key: mobileDiscoveryStringPointer("fallback-rotation-key-928374"), ServerPin: &pin}, network)
	mobileDiscoveryStatus(t, err, 502, "provider_models_tls_pin")
	if calls.Load() != 1 { t.Fatal("fallback dropped pin and sent HTTP to replacement leaf") }
}

func TestMobileModelsDiscoveryTLSPinnedRedirectTargetsReceiveNothing(t *testing.T) {
	mobileProviderHome(t)
	var targetCalls atomic.Int64
	_, targetBase, _ := mobileDiscoveryTLSFixture(t, func(w http.ResponseWriter, r *http.Request) { targetCalls.Add(1) })
	for _, location := range []string{targetBase+"/models", "/new/models"} {
		var calls, dials atomic.Int64
		server, base, network := mobileDiscoveryTLSFixture(t, func(w http.ResponseWriter, r *http.Request) {
			calls.Add(1); w.Header().Set("Location", location); w.WriteHeader(302)
		})
		pin := mobileDiscoveryTLSPin(server.Certificate())
		dial := network.dialContext
		network.dialContext = func(ctx context.Context, kind, address string) (net.Conn, error) { dials.Add(1); return dial(ctx, kind, address) }
		_, err := mobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: base+"/v1", Key: mobileDiscoveryStringPointer("pinned-redirect-key-982347"), ServerPin: &pin}, network)
		mobileDiscoveryStatus(t, err, 502, "provider_models_redirect_rejected")
		if calls.Load() != 1 || dials.Load() != 1 || targetCalls.Load() != 0 { t.Fatal("pinned redirect followed with Authorization") }
	}
}

func TestMobileModelsDiscoveryTLSFactoryMissingLeafAndFixedSafeErrors(t *testing.T) {
	pin := strings.Repeat("a", 64)
	transport := mobileDiscoveryTransportFor(pin, mobileDiscoveryNetwork{})
	defer transport.CloseIdleConnections()
	if transport.Proxy != nil || !transport.TLSClientConfig.InsecureSkipVerify || transport.TLSClientConfig.VerifyConnection == nil || transport.TLSClientConfig.MinVersion != tls.VersionTLS12 || transport.TLSHandshakeTimeout != mobileModelsTimeout || transport.ResponseHeaderTimeout != mobileModelsTimeout || transport.MaxResponseHeaderBytes != 64<<10 || !transport.DisableCompression { t.Fatal("pinned transport weakened unrelated safety limits") }
	for _, certs := range [][]*x509.Certificate{nil, {nil}} {
		err := transport.TLSClientConfig.VerifyConnection(tls.ConnectionState{ServerName: "private-models.invalid", PeerCertificates: certs})
		if err == nil { t.Fatal("missing leaf bypassed pin verification") }
		public := mobileModelsNetworkError(context.Background(), err)
		mobileDiscoveryStatus(t, public, 502, "provider_models_tls_pin")
		if strings.Contains(public.Error(), pin) || strings.Contains(public.Error(), "private-models.invalid") { t.Fatal("missing leaf error leaked connection details") }
	}
	pinned, other := mobileDiscoveryTLSCertificate(t), mobileDiscoveryTLSCertificate(t)
	leafTransport := mobileDiscoveryTransportFor(mobileDiscoveryTLSPin(pinned.Leaf), mobileDiscoveryNetwork{})
	defer leafTransport.CloseIdleConnections()
	chainError := leafTransport.TLSClientConfig.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{other.Leaf, pinned.Leaf}})
	if chainError == nil { t.Fatal("pin matched a non-leaf certificate instead of the exact leaf") }
	mobileDiscoveryStatus(t, mobileModelsNetworkError(context.Background(), chainError), 502, "provider_models_tls_pin")
	ordinary := mobileDiscoveryTransportFor("", mobileDiscoveryNetwork{})
	defer ordinary.CloseIdleConnections()
	if ordinary.TLSClientConfig.InsecureSkipVerify || ordinary.TLSClientConfig.VerifyConnection != nil || ordinary.Proxy != nil { t.Fatal("empty pin did not retain ordinary system TLS") }
}
