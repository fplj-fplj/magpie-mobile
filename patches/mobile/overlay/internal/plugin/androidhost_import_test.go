//go:build androidhost

package plugin

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAndroidHostImportedDependenciesUsePinnedBunWithoutScripts(t *testing.T) {
	root := t.TempDir()
	imports := filepath.Join(root, "imports")
	target := filepath.Join(imports, "snapshot", "plugin")
	if err := os.MkdirAll(target, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "package.json"), []byte(`{"dependencies":{"test-package":"1.0.0"}}`), 0600); err != nil {
		t.Fatal(err)
	}
	record := filepath.Join(root, "arguments")
	bun := filepath.Join(root, "bun")
	if err := os.WriteFile(bun, []byte("#!/bin/sh\nprintf '%s\\n' \"$PWD\" \"$@\" > \"$MAGPIE_IMPORT_TEST_RECORD\"\n"), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("MAGPIE_PLUGIN_IMPORTS_DIR", imports)
	t.Setenv("MAGPIE_BUN", bun)
	t.Setenv("MAGPIE_IMPORT_TEST_RECORD", record)
	if err := prepareAndroidImport(context.Background(), target); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(record)
	if err != nil || string(got) != target+"\ninstall\n--ignore-scripts\n" {
		t.Fatal("import preparation did not use the selected directory and disabled scripts")
	}
}

func TestAndroidHostOtherLocalPathsAndStandaloneScriptsKeepOriginalSemantics(t *testing.T) {
	root := t.TempDir()
	imports := filepath.Join(root, "imports")
	if err := os.MkdirAll(imports, 0700); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(root, "outside")
	if err := os.MkdirAll(outside, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(outside, "package.json"), []byte(`{"dependencies":{"test-package":"1.0.0"}}`), 0600); err != nil {
		t.Fatal(err)
	}
	script := filepath.Join(imports, "fixture.hooks.ts")
	if err := os.WriteFile(script, []byte("export const onRequest = () => {}"), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("MAGPIE_PLUGIN_IMPORTS_DIR", imports)
	t.Setenv("MAGPIE_BUN", filepath.Join(root, "missing-bun"))
	for _, target := range []string{outside, script} {
		if err := prepareAndroidImport(context.Background(), target); err != nil {
			t.Fatal("ordinary local path unexpectedly prepared", err)
		}
	}
}

func TestAndroidHostImportFailureDoesNotExposeInstallerOutput(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "snapshot", "plugin")
	if err := os.MkdirAll(target, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "package.json"), []byte(`{"dependencies":{"test-package":"1.0.0"}}`), 0600); err != nil {
		t.Fatal(err)
	}
	bun := filepath.Join(root, "bun")
	const secret = "private-installer-token"
	if err := os.WriteFile(bun, []byte("#!/bin/sh\necho "+secret+"\nexit 1\n"), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("MAGPIE_PLUGIN_IMPORTS_DIR", root)
	t.Setenv("MAGPIE_BUN", bun)
	err := prepareAndroidImport(context.Background(), target)
	if err == nil || strings.Contains(err.Error(), secret) {
		t.Fatal("failure was not sanitized")
	}
}
