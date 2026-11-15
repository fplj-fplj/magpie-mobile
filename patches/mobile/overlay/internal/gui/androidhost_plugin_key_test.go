//go:build androidhost

package gui

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/yetone/magpie/internal/access"
)

func resetBridgeCache(t *testing.T) {
	t.Helper()
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	bridgeGood = time.Time{}
}

// freshKeyStore gives a test its own caller-key store: XDG_CONFIG_HOME is
// read again at every store path, so each test issues into an empty one.
func freshKeyStore(t *testing.T) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(t.TempDir(), "config"))
	resetBridgeCache(t)
}

func TestAndroidHostBridgeSecretIssuesReusesAndAuthenticates(t *testing.T) {
	freshKeyStore(t)
	key, err := bridgeSecret()
	if err != nil {
		t.Fatalf("bridge key: %v", err)
	}
	if who, ok := access.Authenticate(key); !ok || who.KeyName != bridgeName {
		t.Fatalf("the issued bridge key doesn't authenticate as %q: %v, %v", bridgeName, who.KeyName, ok)
	}
	again, err := bridgeSecret()
	if err != nil {
		t.Fatalf("bridge key reuse: %v", err)
	}
	if again != key {
		t.Fatal("a live bridge key was reissued instead of reused")
	}
	keys, err := access.List()
	if err != nil {
		t.Fatalf("key list: %v", err)
	}
	mine := 0
	for _, k := range keys {
		if k.Name == bridgeName {
			mine++
			if k.Off {
				t.Fatal("the bridge key was stored off")
			}
		}
	}
	if mine != 1 {
		t.Fatalf("the store keeps %d %q keys, want 1", mine, bridgeName)
	}
}

func TestAndroidHostBridgeRevocationSurvivesHostRestart(t *testing.T) {
	for _, action := range []string{"off-key", "remove-key"} {
		t.Run(action, func(t *testing.T) {
			freshKeyStore(t)
			key, err := bridgeSecret()
			if err != nil {
				t.Fatal(err)
			}
			keys, err := access.Export()
			if err != nil || len(keys) != 1 {
				t.Fatal("expected one dedicated key", err)
			}
			id := keys[0].ID
			if _, err := access.Update(action, access.Change{Key: id}); err != nil {
				t.Fatal(err)
			}
			for _, restart := range []bool{false, true} {
				if restart {
					resetBridgeCache(t)
				}
				if secret, err := bridgeSecret(); secret != "" || !errors.Is(err, errBridgeDisabled) {
					t.Fatal("revoked access was recreated without user action")
				}
			}
			if _, ok := access.Authenticate(key); ok {
				t.Fatal("the revoked key still authenticates")
			}
			if err := RestoreAndroidHostBridge(); err != nil {
				t.Fatal(err)
			}
			fresh, err := bridgeSecret()
			if err != nil || fresh == key {
				t.Fatal("explicit recovery did not create a new key", err)
			}
			if _, ok := access.Authenticate(fresh); !ok {
				t.Fatal("explicitly recovered key does not authenticate")
			}
		})
	}
}

func TestAndroidHostBridgeUnreadableRecordIsNotFirstInstall(t *testing.T) {
	freshKeyStore(t)
	if _, err := bridgeSecret(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(bridgeRecordPath(), []byte("invalid-record"), 0600); err != nil {
		t.Fatal(err)
	}
	if secret, err := bridgeSecret(); secret != "" || err == nil {
		t.Fatal("an invalid record was treated as a fresh install")
	}
	keys, err := access.Export()
	if err != nil || len(keys) != 1 {
		t.Fatal("invalid record silently issued a second key", err)
	}
}

func TestAndroidHostBridgeRenewsActiveExpiringKeyAndPreservesOtherKeys(t *testing.T) {
	freshKeyStore(t)
	other, err := access.IssueKey(access.Issue{Name: bridgeName, TTL: bridgeTTL})
	if err != nil {
		t.Fatal(err)
	}
	key, err := bridgeSecret()
	if err != nil {
		t.Fatal(err)
	}
	keys, err := access.Export()
	if err != nil {
		t.Fatal(err)
	}
	for i := range keys {
		if keys[i].Secret == key {
			keys[i].Expires = time.Now().Add(30 * time.Second)
		}
	}
	if err := access.Restore(keys); err != nil {
		t.Fatal(err)
	}
	fresh, err := bridgeSecret()
	if err != nil || fresh == key {
		t.Fatal("active expiring key was not renewed", err)
	}
	if _, ok := access.Authenticate(key); ok {
		t.Fatal("old key survived renewal")
	}
	if _, ok := access.Authenticate(other.Key); !ok {
		t.Fatal("a user key sharing the name was removed")
	}
}

