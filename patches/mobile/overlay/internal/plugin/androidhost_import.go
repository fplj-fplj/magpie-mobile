package plugin

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"

	"github.com/yetone/magpie/internal/mobilepolicy"
)

// prepareAndroidImport installs a selected folder's dependencies before the
// ordinary Add path registers it. Only the APK's copied import snapshots use
// this preparation; desktop and other local paths keep upstream semantics.
func prepareAndroidImport(ctx context.Context, target string) error {
	if !mobilepolicy.FullAdmin {
		return nil
	}
	root := os.Getenv("MAGPIE_PLUGIN_IMPORTS_DIR")
	if root == "" {
		return nil
	}
	root, err := filepath.EvalSymlinks(root)
	if err != nil {
		return nil
	}
	real, err := filepath.EvalSymlinks(target)
	if err != nil {
		return errors.New("the imported plugin cannot be read")
	}
	relative, err := filepath.Rel(root, real)
	if err != nil || relative == "." || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return nil
	}
	info, err := os.Stat(real)
	if err != nil || !info.IsDir() {
		return nil // a standalone script has no package dependencies
	}
	manifest := filepath.Join(real, "package.json")
	info, err = os.Stat(manifest)
	if err != nil || info.Size() > 1<<20 {
		return errors.New("the imported plugin needs a readable package.json")
	}
	raw, err := os.ReadFile(manifest)
	if err != nil {
		return errors.New("the imported plugin package.json cannot be read")
	}
	var pkg struct {
		Dependencies map[string]string `json:"dependencies"`
		Optional     map[string]string `json:"optionalDependencies"`
		Peer         map[string]string `json:"peerDependencies"`
		Development  map[string]string `json:"devDependencies"`
	}
	if json.Unmarshal(raw, &pkg) != nil {
		return errors.New("the imported plugin package.json is invalid")
	}
	if len(pkg.Dependencies)+len(pkg.Optional)+len(pkg.Peer)+len(pkg.Development) == 0 {
		return nil
	}
	bun, err := Bun(ctx)
	if err != nil {
		return errors.New("Bun is unavailable for the imported plugin")
	}
	if _, err := bunCommand(ctx, bun, real, "install", "--ignore-scripts").CombinedOutput(); err != nil {
		// Installer output may contain private repository URLs or credentials.
		return errors.New("the imported plugin dependencies could not be installed")
	}
	return nil
}
