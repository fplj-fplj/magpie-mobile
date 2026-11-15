//go:build androidhost

package plugin

import (
	"bytes"
	"encoding/base64"
	"testing"

	"github.com/yetone/magpie/internal/mobilepolicy"
)

func TestAndroidHostBridgePreludeCarriesTheKeyAsItsBase64Alone(t *testing.T) {
	const secret = "bridge-key-value-that-must-not-appear-raw"
	prelude := string(bridgePrelude(secret))
	if !bytes.Contains([]byte(prelude), []byte(base64.StdEncoding.EncodeToString([]byte(secret)))) {
		t.Fatal("the prelude doesn't carry the key as its base64")
	}
	if bytes.Contains([]byte(prelude), []byte(secret)) {
		t.Fatal("the prelude carries the key raw")
	}
}

func TestAndroidHostHostJSScriptCarriesTheBridge(t *testing.T) {
	if !mobilepolicy.FullAdmin {
		t.Fatal("the bridge must only run under the Android host policy")
	}
	const key = "synthetic-plugin-gateway-key"
	ConfigureAndroidHostBridge(func() (string, error) { return key, nil }, func() bool { return false })
	t.Cleanup(func() { ConfigureAndroidHostBridge(nil, nil) })
	js := mobileHostJS()
	if !bytes.HasPrefix(js, []byte("// magpie's Android host bridge")) {
		t.Fatal("the bridge prelude is not before host.js")
	}
	if !bytes.HasSuffix(js, hostJS) {
		t.Fatal("host.js itself was changed")
	}
	if bytes.Contains(js, []byte(key)) {
		t.Fatal("the host script carries the key raw")
	}
	for _, want := range []string{"MAGPIE_ADDR", "Bearer "} {
		if !bytes.Contains(js, []byte(want)) {
			t.Fatalf("the bridge prelude lacks %q", want)
		}
	}
	if bytes.Contains(js, []byte("__MAGPIE_BRIDGE_KEY__")) {
		t.Fatal("the bridge left an unresolved credential placeholder")
	}
}
