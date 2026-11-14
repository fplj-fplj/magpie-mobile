//go:build androidhost

package provider

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func androidHostProviderHome(t *testing.T) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	if err := os.MkdirAll(filepath.Dir(Path()), 0o700); err != nil { t.Fatal(err) }
}

func TestAndroidHostOfficialProviderSaveAndNativeRevision(t *testing.T) {
	androidHostProviderHome(t)
	p := Provider{ID: "full-provider", Name: "Full provider", Chat: "http://127.0.0.1:48763/v1", Key: "synthetic-key", Models: []string{"model-one"}, BaseAPI: "chat"}
	if err := Save(p); err != nil { t.Fatal("official first-use save failed", err) }
	native, err := MobileEditableProvider(p.ID); if err != nil { t.Fatal(err) }
	stale, err := read(); if err != nil { t.Fatal(err) }
	p.Name = "Web edited"
	p.Headers = map[string]string{"X-Custom": "value"}
	if err := Save(p); err != nil { t.Fatal("official composite save failed", err) }
	current, err := MobileEditableProvider(p.ID)
	if err != nil || current.Name != p.Name || current.Revision == native.Revision || !current.Key.Set { t.Fatal("native did not observe web save", err) }
	name := "stale native"
	if _, err := MobileEditProvider(MobileProviderEdit{ID: p.ID, Revision: native.Revision, Name: &name}); !errors.Is(err, ErrMobileProviderRevision) { t.Fatal("stale native edit accepted", err) }
	if err := store(stale); !errors.Is(err, ErrMobileProviderRevision) { t.Fatal("stale upstream store accepted", err) }
	name = "Native edited"
	if _, err := MobileEditProvider(MobileProviderEdit{ID: p.ID, Revision: current.Revision, Name: &name}); err != nil { t.Fatal(err) }
	_, f, err := mobileCanonical()
	if err != nil || f.Providers[0].Headers["X-Custom"] != "value" || f.Providers[0].Key != p.Key || f.Providers[0].Models[0] != "model-one" { t.Fatal("native edit lost official fields", err) }
	if err := Delete(p.ID); err != nil { t.Fatal("official removal failed", err) }
	_, f, err = mobileCanonical()
	if err != nil || len(f.Providers) != 0 { t.Fatal("official removal did not persist", err) }
}

func TestAndroidHostStoreSupportsCompositeReplacementAndOrdering(t *testing.T) {
	androidHostProviderHome(t)
	f, err := read(); if err != nil { t.Fatal(err) }
	f.Providers = []Provider{{ID: "first", Name: "First", Chat: "https://api.example.com/v1", Key: "synthetic-key"}, {ID: "second", Name: "Second", Chat: "https://api.example.com/v1", Key: "synthetic-key"}}
	if err := store(f); err != nil { t.Fatal(err) }
	f, err = read(); if err != nil { t.Fatal(err) }
	f.Providers[0].Headers = map[string]string{"X-Custom": "old"}
	f.Order = []string{"second", "first"}
	if err := store(f); err != nil { t.Fatal("composite/order save failed", err) }
	f, err = read(); if err != nil { t.Fatal(err) }
	f.Providers[0].Headers = nil
	f.Providers[0], f.Providers[1] = f.Providers[1], f.Providers[0]
	if err := store(f); err != nil { t.Fatal("composite removal/reorder failed", err) }
	raw, out, err := mobileCanonical()
	if err != nil || out.Providers[0].ID != "second" || out.Providers[1].Headers != nil || out.Order[0] != "second" { t.Fatal("official fields were not stored", err) }
	if bytes.Contains(raw, []byte("mobileRevision")) { t.Fatal("private generation was serialized") }
	st, err := os.Stat(Path())
	if err != nil || st.Mode().Perm() != 0o600 { t.Fatal("provider file is not private", err) }
}
