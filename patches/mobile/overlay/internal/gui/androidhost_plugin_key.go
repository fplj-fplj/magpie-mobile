package gui

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/yetone/magpie/internal/access"
	"github.com/yetone/magpie/internal/edit"
	"github.com/yetone/magpie/internal/mobilepolicy"
	"github.com/yetone/magpie/internal/plugin"
	"github.com/yetone/magpie/internal/settings"
)

// access depends on provider/plugin. Key ownership stays at the management
// layer; the plugin package receives callbacks and never imports access.
func init() {
	if mobilepolicy.FullAdmin {
		plugin.ConfigureAndroidHostBridge(bridgeSecret, androidHostBridgeNeedsRefresh)
	}
}

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
		plugin.AndroidHostBridgeChanged()
	}
	return err
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

