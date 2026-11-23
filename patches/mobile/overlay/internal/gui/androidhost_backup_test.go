//go:build androidhost

package gui

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/yetone/magpie/internal/backup"
	"github.com/yetone/magpie/internal/library"
	"github.com/yetone/magpie/internal/memory"
	"github.com/yetone/magpie/internal/provider"
	"github.com/yetone/magpie/internal/settings"
)

const androidBackupTestManagerKey = "synthetic-backup-manager-key-123456"
const androidBackupTestPass = "synthetic backup passphrase"

type androidBackupWindows struct {
	Windows
	opened []string
}

func (w *androidBackupWindows) OpenFolder(dir string) error {
	w.opened = append(w.opened, dir)
	return nil
}

func androidBackupHandler(w Windows) http.Handler {
	mux := http.NewServeMux()
	backupRoutes(mux, w)
	return webHandler("3430", androidBackupTestManagerKey, 0, nil,
		mobileAPI(androidHostEditorProof(mux)))
}

func androidBackupRequest(method, path, body, managerKey string) *http.Request {
	r := httptest.NewRequest(method, "http://127.0.0.1:3430"+path, strings.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	if managerKey != "" {
		r.Header.Set(androidManagerHeader, managerKey)
	}
	return r
}

func androidBackupPost(t *testing.T, h http.Handler, path string, value any) *httptest.ResponseRecorder {
	t.Helper()
	body, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, androidBackupRequest("POST", path, string(body), androidBackupTestManagerKey))
	return w
}

func androidBackupFiles(t *testing.T, home string) []string {
	t.Helper()
	var found []string
	if err := filepath.WalkDir(home, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), backup.Ext) {
			found = append(found, path)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	return found
}

// The browser receives the official encrypted bundle, including plugin metadata;
// a metadata-only native backup or a JSON {path: ...} does not pass this test.
func TestAndroidHostBackupAppSinkRoundTripWithoutPrivateArchive(t *testing.T) {
	for _, keys := range []bool{false, true} {
		name := "without keys"
		if keys {
			name = "with keys"
		}
		t.Run(name, func(t *testing.T) {
			home := sandboxHome(t)
			if err := os.MkdirAll(filepath.Join(home, "Downloads"), 0o700); err != nil {
				t.Fatal(err)
			}
			original := provider.Provider{
				ID: "android-backup", Name: "Android backup", Chat: "https://backup.example.invalid/v1",
				Key: "synthetic-provider-backup-key", Models: []string{"model-one"},
			}
			if err := provider.Save(original); err != nil {
				t.Fatal(err)
			}
			instructions := "Instructions carried by the official backup."
			if _, err := library.SaveInstructions(library.InstructionsChange{Shared: &instructions}); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(settings.Dir(), "plugins.json"), []byte(`{"plugins":[{"spec":"@magpie-community/backup-fixture","off":true}]}`), 0o600); err != nil {
				t.Fatal(err)
			}
			windows := &androidBackupWindows{}
			h := androidBackupHandler(windows)
			w := androidBackupPost(t, h, "/api/backup/export", map[string]any{
				"sink": "app", "pass": androidBackupTestPass, "keys": keys, "library": true, "memory": false,
			})
			if w.Code != http.StatusOK || w.Header().Get("Cache-Control") != "no-store" ||
				w.Header().Get("Content-Type") != "application/octet-stream" ||
				w.Header().Get("Content-Disposition") != `attachment; filename="magpie.magpie-backup"` ||
				w.Header().Get("X-Content-Type-Options") != "nosniff" {
				t.Fatalf("not a private, downloadable backup: status=%d headers=%v", w.Code, w.Header())
			}
			for _, secret := range []string{original.Key, original.Chat, instructions, androidBackupTestPass, androidBackupTestManagerKey} {
				if bytes.Contains(w.Body.Bytes(), []byte(secret)) {
					t.Fatal("export exposed plaintext rather than the sealed bundle")
				}
			}
			bundle, err := backup.Open(w.Body.Bytes(), androidBackupTestPass)
			if err != nil {
				t.Fatal("official backup.Open rejected the download", err)
			}
			if bundle.Keys != keys || len(bundle.Providers) != 1 || bundle.Providers[0].ID != original.ID ||
				len(bundle.Providers[0].Models) != 1 || bundle.Providers[0].Models[0] != "model-one" || bundle.Library == nil ||
				bundle.Memory != nil || bundle.SignIns != nil || bundle.Plugins == nil || len(*bundle.Plugins) != 1 ||
				(*bundle.Plugins)[0].Spec != "@magpie-community/backup-fixture" || !(*bundle.Plugins)[0].Off {
				t.Fatal("the download lost the requested official configuration")
			}
			wantKey := ""
			if keys {
				wantKey = original.Key
			}
			if bundle.Providers[0].Key != wantKey {
				t.Fatal("the official credential option was not honored")
			}
			if _, err := backup.Open(w.Body.Bytes(), "wrong passphrase"); !errors.Is(err, backup.ErrPassphrase) {
				t.Fatal("the downloaded backup was not sealed with the selected passphrase")
			}
			if files := androidBackupFiles(t, home); len(files) != 0 || len(windows.opened) != 0 {
				t.Fatalf("app sink still wrote or revealed a private archive: files=%d opened=%d", len(files), len(windows.opened))
			}

			// A second download omits the npm fixture so this isolated backup
			// regression does not install packages. Send those exact HTTP bytes
			// to the official import route, without reconstructing the format.
			if err := os.WriteFile(filepath.Join(settings.Dir(), "plugins.json"), []byte(`{"plugins":[]}`), 0o600); err != nil {
				t.Fatal(err)
			}
			withoutPlugin := androidBackupPost(t, h, "/api/backup/export", map[string]any{
				"sink": "app", "pass": androidBackupTestPass, "keys": keys, "library": true, "memory": false,
			})
			if withoutPlugin.Code != http.StatusOK || withoutPlugin.Header().Get("Content-Type") != "application/octet-stream" {
				t.Fatal("the import fixture was not downloaded through the official export route")
			}
			importBytes := append([]byte(nil), withoutPlugin.Body.Bytes()...)
			sandboxHome(t)
			result := androidBackupPost(t, h, "/api/backup/import", map[string]any{
				"data": base64.StdEncoding.EncodeToString(importBytes), "pass": androidBackupTestPass,
				"agents": false, "library": true, "memory": false, "signIns": false,
			})
			if result.Code != http.StatusOK {
				t.Fatalf("official import rejected the backup: HTTP %d", result.Code)
			}
			restored, err := provider.Find(original.ID)
			if err != nil || restored.Chat != original.Chat || restored.Key != wantKey {
				t.Fatal("official import did not restore the downloaded provider", err)
			}
			if view, err := library.ReadInstructions(); err != nil || view.Shared != instructions {
				t.Fatal("official import did not restore the downloaded library", err)
			}
		})
	}
}

func TestAndroidHostBackupAppSinkHonorsLibraryMemoryAndSignInsOptions(t *testing.T) {
	home := sandboxHome(t)
	t.Setenv("MAGPIE_NO_ACCOUNTS", "")
	instructions := "Synthetic instructions for encrypted backup options."
	if _, err := library.SaveInstructions(library.InstructionsChange{Shared: &instructions}); err != nil {
		t.Fatal(err)
	}
	text, kind, scope := "Synthetic memory for encrypted backup options.", memory.Preference, memory.Global
	if _, _, err := memory.Save(memory.Input{Text: &text, Kind: &kind, Scope: &scope}, memory.FromUser, nil, nil); err != nil {
		t.Fatal(err)
	}
	const signInKey = "synthetic-plugin-signin-backup-key"
	if err := os.WriteFile(filepath.Join(settings.Dir(), "plugin-auth.json"), []byte(`{"backup-signin-fixture":{"type":"api","key":"`+signInKey+`"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	windows := &androidBackupWindows{}
	h := androidBackupHandler(windows)
	for _, tc := range []struct {
		name                             string
		defaults, library, memory, signIns bool
	}{
		{"defaults omit sign-ins", true, true, true, false},
		{"all selected", false, true, true, true},
		{"all excluded", false, false, false, false},
		{"memory and sign-ins without library", false, false, true, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			body := map[string]any{"sink": "app", "pass": androidBackupTestPass}
			if !tc.defaults {
				body["library"], body["memory"], body["signIns"] = tc.library, tc.memory, tc.signIns
			}
			w := androidBackupPost(t, h, "/api/backup/export", body)
			if w.Code != http.StatusOK || w.Header().Get("Content-Type") != "application/octet-stream" {
				t.Fatalf("selected options did not produce a sealed download: HTTP %d", w.Code)
			}
			b, err := backup.Open(w.Body.Bytes(), androidBackupTestPass)
			if err != nil {
				t.Fatal("official backup.Open rejected selected options", err)
			}
			if (b.Library != nil) != tc.library || (b.Memory != nil) != tc.memory || (b.SignIns != nil) != tc.signIns {
				t.Fatal("the encrypted bundle did not honor the selected parts")
			}
			if tc.library && strings.TrimSpace(b.Library.Texts["default"]) != instructions {
				t.Fatal("the selected library lost its instructions")
			}
			if tc.memory && (len(b.Memory.Memories) != 1 || b.Memory.Memories[0].Text != text) {
				t.Fatal("the selected memory did not survive the encrypted download")
			}
			if tc.signIns && !bytes.Contains(b.SignIns.Plugins["backup-signin-fixture"], []byte(signInKey)) {
				t.Fatal("the selected plugin sign-in did not survive the encrypted download")
			}
			plain, err := json.Marshal(b)
			if err != nil || (!tc.signIns && bytes.Contains(plain, []byte(signInKey))) {
				t.Fatal("an excluded plugin sign-in reached the bundle", err)
			}
			for _, secret := range []string{instructions, text, signInKey, androidBackupTestPass} {
				if bytes.Contains(w.Body.Bytes(), []byte(secret)) {
					t.Fatal("a selected backup part escaped the official encryption")
				}
			}
			if files := androidBackupFiles(t, home); len(files) != 0 || len(windows.opened) != 0 {
				t.Fatal("an app-sink options export wrote a private archive")
			}
		})
	}
}

func TestAndroidHostBackupDefaultSinkKeepsDesktopDownloadsAndHomeFallback(t *testing.T) {
	for _, downloads := range []bool{false, true} {
		name := "home fallback"
		if downloads {
			name = "Downloads"
		}
		t.Run(name, func(t *testing.T) {
			home := sandboxHome(t)
			wantDir := home
			if downloads {
				wantDir = filepath.Join(home, "Downloads")
				if err := os.MkdirAll(wantDir, 0o700); err != nil {
					t.Fatal(err)
				}
			}
			windows := &androidBackupWindows{}
			h := androidBackupHandler(windows)
			for index, sink := range []string{"", "downloads"} {
				body := map[string]any{"pass": androidBackupTestPass, "library": false, "memory": false}
				if sink != "" {
					body["sink"] = sink
				}
				w := androidBackupPost(t, h, "/api/backup/export", body)
				if w.Code != http.StatusOK || w.Header().Get("Content-Disposition") != "" || w.Header().Get("Content-Type") != "application/json" {
					t.Fatalf("default desktop export was replaced by a download: HTTP %d", w.Code)
				}
				var out struct{ Path string }
				if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil || !strings.HasPrefix(out.Path, "~/") {
					t.Fatal("desktop export no longer returns its saved path", err)
				}
				path := filepath.Join(home, strings.TrimPrefix(out.Path, "~/"))
				data, err := os.ReadFile(path)
				if err != nil || filepath.Dir(path) != wantDir {
					t.Fatal("desktop export did not keep its original destination", err)
				}
				if _, err := backup.Open(data, androidBackupTestPass); err != nil {
					t.Fatal("desktop export changed the encrypted format", err)
				}
				if files := androidBackupFiles(t, home); len(files) != index+1 || len(windows.opened) != index+1 || windows.opened[index] != wantDir {
					t.Fatal("desktop export overwrote an earlier backup or skipped revealing its folder")
				}
			}
		})
	}
}

func TestAndroidHostBackupAppSinkKeepsManagerHeaderOriginAndPathGuards(t *testing.T) {
	home := sandboxHome(t)
	windows := &androidBackupWindows{}
	h := androidBackupHandler(windows)
	for _, tc := range []struct {
		name, method, path, managerKey, origin, site, referer, fetchMode string
		want                                                             int
	}{
		{"no manager", "POST", "/api/backup/export", "", "", "", "", "", http.StatusUnauthorized},
		{"wrong manager", "POST", "/api/backup/export", "wrong", "", "", "", "", http.StatusUnauthorized},
		{"foreign Origin", "POST", "/api/backup/export", androidBackupTestManagerKey, "https://foreign.example", "", "", "", http.StatusForbidden},
		{"foreign site", "POST", "/api/backup/export", androidBackupTestManagerKey, "", "cross-site", "", "", http.StatusForbidden},
		{"browser Referer", "POST", "/api/backup/export", androidBackupTestManagerKey, "", "", "http://127.0.0.1:3430/", "", http.StatusForbidden},
		{"browser fetch mode", "POST", "/api/backup/export", androidBackupTestManagerKey, "", "", "", "cors", http.StatusForbidden},
		{"wrong method", "GET", "/api/backup/export", androidBackupTestManagerKey, "", "", "", "", http.StatusMethodNotAllowed},
		{"encoded path", "POST", "/api/backup%2fexport", androidBackupTestManagerKey, "", "", "", "", http.StatusNotFound},
		{"unclean path", "POST", "/api/other/../backup/export", androidBackupTestManagerKey, "", "", "", "", http.StatusNotFound},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := androidBackupRequest(tc.method, tc.path, `{"sink":"app","pass":"`+androidBackupTestPass+`"}`, tc.managerKey)
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
			if w.Code != tc.want || w.Header().Get("Content-Disposition") != "" {
				t.Fatalf("unauthorized backup download: status=%d want=%d", w.Code, tc.want)
			}
		})
	}
	r := androidBackupRequest("POST", "/api/backup/export", `{"sink":"app","pass":"`+androidBackupTestPass+`"}`, "")
	r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: androidBackupTestManagerKey})
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized || w.Header().Get("Set-Cookie") != "" {
		t.Fatal("manager cookie authenticated Android-host backup")
	}
	if files := androidBackupFiles(t, home); len(files) != 0 || len(windows.opened) != 0 {
		t.Fatal("a rejected request exported a file")
	}
	r = androidBackupRequest("POST", "/api/backup/export", `{"sink":"app","pass":"`+androidBackupTestPass+`","library":false,"memory":false}`, androidBackupTestManagerKey)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("native manager-authenticated download was refused: HTTP %d", w.Code)
	}
	if _, err := backup.Open(w.Body.Bytes(), androidBackupTestPass); err != nil {
		t.Fatal("authenticated download did not return the official format", err)
	}
}

func TestAndroidHostBackupFailuresAreNotDownloadAttachments(t *testing.T) {
	home := sandboxHome(t)
	windows := &androidBackupWindows{}
	h := androidBackupHandler(windows)
	for _, body := range []string{
		`{"sink":"app"`,
		`{"sink":"app","pass":""}`,
		`{"sink":"../../outside","pass":"` + androidBackupTestPass + `"}`,
	} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, androidBackupRequest("POST", "/api/backup/export", body, androidBackupTestManagerKey))
		if w.Code != http.StatusBadRequest || w.Header().Get("Content-Disposition") != "" ||
			w.Header().Get("Content-Type") != "application/json" || w.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("backup error became a downloadable file: HTTP %d", w.Code)
		}
	}
	if err := os.MkdirAll(filepath.Dir(provider.Path()), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(provider.Path(), []byte(`{"providers":`), 0o600); err != nil {
		t.Fatal(err)
	}
	w := androidBackupPost(t, h, "/api/backup/export", map[string]any{"sink": "app", "pass": androidBackupTestPass})
	if w.Code != http.StatusBadRequest || w.Header().Get("Content-Disposition") != "" || w.Header().Get("Content-Type") != "application/json" {
		t.Fatal("an unreadable configuration became a backup attachment")
	}
	if files := androidBackupFiles(t, home); len(files) != 0 || len(windows.opened) != 0 {
		t.Fatal("an invalid export request created an archive")
	}
}

func TestAndroidHostBackupAppSinkDoesNotReadCallerFilePaths(t *testing.T) {
	home := sandboxHome(t)
	private := filepath.Join(home, "unrelated-private-file")
	const unrelated = "synthetic private file that does not belong in a backup"
	if err := os.WriteFile(private, []byte(unrelated), 0o600); err != nil {
		t.Fatal(err)
	}
	h := androidBackupHandler(&androidBackupWindows{})
	w := androidBackupPost(t, h, "/api/backup/export", map[string]any{
		"sink": "app", "pass": androidBackupTestPass, "path": private,
		"library": false, "memory": false,
	})
	if w.Code != http.StatusOK {
		t.Fatalf("the app sink did not return a configuration backup: HTTP %d", w.Code)
	}
	bundle, err := backup.Open(w.Body.Bytes(), androidBackupTestPass)
	if err != nil {
		t.Fatal("a caller path replaced the official encrypted format", err)
	}
	plain, err := json.Marshal(bundle)
	if err != nil || bytes.Contains(plain, []byte(unrelated)) {
		t.Fatal("the app sink included an unrelated requested file", err)
	}
	if after, err := os.ReadFile(private); err != nil || string(after) != unrelated {
		t.Fatal("the app sink modified the requested file", err)
	}
	w = androidBackupPost(t, h, "/api/backup/export", map[string]any{"sink": private, "pass": androidBackupTestPass})
	if w.Code != http.StatusBadRequest || w.Header().Get("Content-Disposition") != "" {
		t.Fatal("an absolute caller path was accepted as a backup destination")
	}
	if files := androidBackupFiles(t, home); len(files) != 0 {
		t.Fatal("a caller-controlled path caused a private archive to be written")
	}
}

func TestAndroidHostBackupBootFlagIsRestrictedToAndroidHostBrowser(t *testing.T) {
	sandboxHome(t)
	for _, tc := range []struct {
		name string
		win  Windows
		want bool
	}{
		{"browser", webHost{}, true},
		{"native desktop window", &androidBackupWindows{}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			page := Handler(tc.win, nil)
			h := androidHostEditor("3430", androidEditorTestKey, 0, nil, page)
			link, _ := mintAndroidEditorTicket(t, h)
			proof := exchangeAndroidEditorTicket(t, h, link)
			w := httptest.NewRecorder()
			boot := androidEditorRequest(http.MethodGet, "/boot.js", "")
			boot.Header.Set(androidEditorProofHeader, proof)
			h.ServeHTTP(w, boot)
			if w.Code != http.StatusOK {
				t.Fatalf("boot failed: HTTP %d", w.Code)
			}
			body := strings.TrimSuffix(strings.TrimPrefix(w.Body.String(), "window.bootPrefs = "), ";\n")
			var boot map[string]any
			if err := json.Unmarshal([]byte(body), &boot); err != nil {
				t.Fatal(err)
			}
			if got, _ := boot["androidHost"].(bool); got != tc.want {
				t.Fatal("the page did not use the compile-time Android host flag")
			}
		})
	}
}

// Node executes the actual embedded page module with a small DOM/HTTP fixture.
// This checks download behavior, not merely the presence of source substrings.
func TestAndroidHostBackupBrowserDownloadAndFailureHandling(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Fatal("Node is required for the official-page download regression", err)
	}
	source, err := fs.ReadFile(staticFS(), "syncfiles.js")
	if err != nil {
		t.Fatal(err)
	}
	app, err := fs.ReadFile(staticFS(), "app.js")
	if err != nil {
		t.Fatal(err)
	}
	// Exercise the real syncBar too: its error callback queries the current
	// editor-error node, so a fixture that invents a new node would hide bugs.
	start := bytes.Index(app, []byte("function syncBar(ed, err, ...tools) {"))
	if start < 0 {
		t.Fatal("the official page's syncBar was not found")
	}
	end := bytes.Index(app[start:], []byte("\n}\n"))
	if end < 0 {
		t.Fatal("the official page's syncBar terminator was not found")
	}
	source = append(append([]byte(nil), app[start:start+end+3]...), source...)
	command := exec.Command(node, "-e", androidBackupBrowserHarness)
	command.Stdin = bytes.NewReader(source)
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("official-page download regression failed: %v\n%s", err, output)
	}
}

const androidBackupBrowserHarness = `
const assert = require("node:assert/strict");
const vm = require("node:vm");
const source = require("node:fs").readFileSync(0, "utf8");

function fixture(androidHost, response = {}, faults = {}) {
  const nodes = [], inputs = [], requests = [], downloads = [], timers = [], apiCalls = [];
  const blobs = new Map(), revoked = [];
  let blobReads = 0, nextURL = 0;
  let document;
  class Element {
    constructor(tag, cls = "", text = "") {
      this.tag = tag;
      this.className = cls;
      this._text = text;
      this.childNodes = [];
      this.parentElement = null;
      this.hidden = this.disabled = false;
      this.attributes = new Map();
      this.classList = {
        contains: (name) => this.className.split(/\s+/).includes(name),
        add: (name) => { if (!this.classList.contains(name)) this.className += " " + name; },
        remove: (name) => { this.className = this.className.split(/\s+/).filter((v) => v !== name).join(" "); },
      };
      nodes.push(this);
    }
    get children() { return this.childNodes.filter((node) => node.tag !== null); }
    get textContent() { return this._text + this.childNodes.map((node) => node.textContent).join(""); }
    set textContent(text) {
      for (const node of this.childNodes) node.parentElement = null;
      this.childNodes = [];
      this._text = String(text);
    }
    get isConnected() {
      for (let node = this; node; node = node.parentElement) {
        if (node === document.body) return true;
      }
      return false;
    }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    getAttribute(name) { return this.attributes.get(name) ?? null; }
    append(...items) {
      if (this === document.body && items.some((node) => node?.tag === "a") && faults.append) {
        throw new Error("synthetic append failure");
      }
      for (const item of items) {
        const node = item instanceof Element ? item : new Element(null, "", String(item));
        node.remove();
        node.parentElement = this;
        this.childNodes.push(node);
      }
    }
    replaceChildren(...items) {
      for (const node of this.childNodes) node.parentElement = null;
      this.childNodes = [];
      this._text = "";
      this.append(...items);
    }
    remove() {
      if (this.parentElement) {
        this.parentElement.childNodes = this.parentElement.childNodes.filter((node) => node !== this);
        this.parentElement = null;
      }
    }
    matches(selector) {
      if (selector.startsWith(".")) return this.tag !== null && this.classList.contains(selector.slice(1));
      return this.tag === selector;
    }
    querySelector(selector) {
      for (const child of this.children) {
        if (child.matches(selector)) return child;
        const nested = child.querySelector(selector);
        if (nested) return nested;
      }
      return null;
    }
    click() {
      if (this.tag === "a") {
        if (faults.click) throw new Error("synthetic click failure");
        downloads.push({ href: this.href, name: this.download, blob: blobs.get(this.href) });
      } else if (!this.disabled) return this.onclick?.();
    }
  }
  const el = (tag, cls, text) => new Element(tag, cls, text);
  document = { body: el("body"), visibilityState: "visible", createElement: (tag) => el(tag) };
  const sealed = new Blob(['{"format":"magpie-backup","data":"synthetic-ciphertext"}'], { type: "application/octet-stream" });
  const ctx = {
    window: { bootPrefs: { androidHost } }, document,
    el, t: (text, params) => params?.path ? text.replace("{path}", params.path) : text,
    input: (value) => { const n = el("input"); n.value = value; inputs.push(n); return n; },
    tick: (title, checked) => {
      const label = el("label", "tick"), input = el("input");
      input.checked = checked;
      label.append(input, el("span", "", title));
      return [label, input];
    },
    field: (title, control, hint) => {
      const label = el("label", "", title), value = el("div");
      value.append(control);
      if (hint !== undefined) value.append(el("div", "hint", hint));
      return [label, value];
    },
    renderSync() {},
    api: async (...args) => { apiCalls.push(args); return { path: "~/desktop.magpie-backup" }; },
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (response.reject) throw new Error("synthetic offline");
      return {
        ok: response.ok ?? true,
        headers: { get: (key) => ({
          "Content-Type": response.type ?? "application/octet-stream",
          "Content-Disposition": response.disposition ?? 'attachment; filename="magpie.magpie-backup"'
        }[key] || "") },
        blob: async () => { blobReads++; if (response.bodyReject) throw new Error("synthetic body failure"); return response.empty ? new Blob([]) : sealed; }
      };
    },
    URL: { createObjectURL: (blob) => {
        if (faults.createObjectURL) throw new Error("synthetic URL failure");
        const url = "blob:fixture-" + nextURL++;
        blobs.set(url, blob);
        return url;
      },
      revokeObjectURL: (url) => { revoked.push(url); blobs.delete(url); } },
    setTimeout: (callback, delay) => timers.push({ callback, delay }),
  };
  vm.createContext(ctx);
  vm.runInContext(source, ctx);
  const ed = ctx.window.exportForm();
  const view = el("section"), settingsPage = el("section");
  document.body.append(view);
  view.append(settingsPage);
  settingsPage.append(ed);
  inputs[0].value = inputs[1].value = "synthetic browser passphrase";
  const go = nodes.find((node) => node.tag === "button" && node.textContent === "Export");
  const error = ed.querySelector(".editor-error");
  return { nodes, inputs, requests, downloads, timers, apiCalls, revoked, blobs, sealed, ed, go, error,
    view, settingsPage, document, faults, blobReads: () => blobReads,
    download: () => ed.querySelector("button"),
    flushTimers: () => { while (timers.length) timers.shift().callback(); },
  };
}

(async () => {
  const page = fixture(true);
  const pending = page.go.onclick();
  await page.go.onclick(); // even a directly repeated callback cannot issue two POSTs
  await pending;
  assert.equal(page.requests.length, 1);
  const request = page.requests[0];
  assert.equal(request.url, "/api/backup/export");
  assert.equal(request.options.method, "POST");
  assert.equal(request.options.credentials, "same-origin");
  assert.equal(request.options.mode, "same-origin");
  assert.equal(request.options.redirect, "error");
  assert.equal(request.options.cache, "no-store");
  assert.equal(request.options.headers.Accept, "application/octet-stream");
  assert.deepEqual(JSON.parse(request.options.body), { sink: "app", pass: "synthetic browser passphrase", keys: true, library: true, memory: true, signIns: false });
  assert.equal(page.apiCalls.length, 0);
  assert.equal(page.downloads.length, 1);
  assert.equal(page.downloads[0].name, "magpie.magpie-backup");
  assert.equal(page.downloads[0].blob, page.sealed);
  assert.equal(page.inputs[0].value, "");
  assert.equal(page.inputs[1].value, "");
  assert.equal(page.ed.querySelector(".editor-error"), page.error);
  assert.equal(page.error.getAttribute("role"), "alert");
  assert.equal(page.ed.querySelector(".missing"), null);
  assert.equal(page.blobs.size, 1); // do not revoke before the browser consumes it
  assert.equal(page.timers[0].delay, 60000);
  page.flushTimers();
  assert.equal(page.blobs.size, 0);
  assert.equal(page.revoked.length, 1);
  assert.ok(page.download().textContent.startsWith("Download"));
  page.download().click(); // retry the same ciphertext, never repeat a POST
  assert.equal(page.downloads.length, 2);
  assert.equal(page.requests.length, 1);
  assert.equal(page.downloads[1].blob, page.sealed);
  assert.equal(page.error.textContent, "");
  assert.equal(page.document.body.querySelector("a"), null);
  page.flushTimers();
  assert.equal(page.blobs.size, 0);

  for (const response of [{ ok: false }, { type: "application/json" }, { disposition: 'attachment; filename="../../unsafe"' }, { empty: true }, { reject: true }, { bodyReject: true }]) {
    const failed = fixture(true, response);
    await failed.go.onclick();
    assert.equal(failed.downloads.length, 0);
    assert.equal(failed.blobs.size, 0);
    assert.notEqual(failed.error.textContent, "");
    assert.equal(failed.ed.querySelector(".editor-error"), failed.error);
    assert.equal(failed.go.disabled, false);
    if (!response.empty && !response.reject && !response.bodyReject) assert.equal(failed.blobReads(), 0);
    await failed.go.onclick();
    assert.equal(failed.requests.length, 2); // explicit retry is possible after a known failure
  }
  for (const fault of ["createObjectURL", "append", "click"]) {
    const broken = fixture(true, {}, { [fault]: true });
    await broken.go.onclick(); // a synchronous automatic-save failure stays in the real alert slot
    assert.equal(broken.requests.length, 1);
    assert.equal(broken.downloads.length, 0);
    assert.equal(broken.ed.querySelector(".editor-error"), broken.error);
    assert.match(broken.error.textContent, /synthetic .* failure/);
    assert.ok(broken.download().textContent.startsWith("Download"));
    assert.equal(broken.document.body.querySelector("a"), null);
    broken.flushTimers();
    assert.equal(broken.blobs.size, 0);
    broken.download().click(); // a manual failure uses the same error handler, without a second export
    assert.match(broken.error.textContent, /synthetic .* failure/);
    assert.equal(broken.requests.length, 1);
    assert.equal(broken.downloads.length, 0);
    broken.flushTimers();
    assert.equal(broken.blobs.size, 0);
    broken.faults[fault] = false;
    broken.download().click();
    assert.equal(broken.error.textContent, "");
    assert.equal(broken.downloads.length, 1);
    assert.equal(broken.downloads[0].blob, broken.sealed);
    assert.equal(broken.requests.length, 1);
    broken.flushTimers();
    assert.equal(broken.blobs.size, 0);
  }
  for (const hidden of ["form", "settings page", "view", "document", "prerender", "unmounted"]) {
    const left = fixture(true);
    const late = left.go.onclick();
    if (hidden === "form") left.ed.hidden = true;
    if (hidden === "settings page") left.settingsPage.hidden = true;
    if (hidden === "view") left.view.hidden = true;
    if (hidden === "document") left.document.visibilityState = "hidden";
    if (hidden === "prerender") left.document.visibilityState = "prerender";
    if (hidden === "unmounted") left.ed.remove();
    await late;
    assert.equal(left.requests.length, 1);
    assert.equal(left.blobReads(), 1);
    assert.equal(left.downloads.length, 0, hidden);
    assert.equal(left.blobs.size, 0); // inactive pages keep the Blob, without an active object URL
    assert.equal(left.ed.querySelector(".editor-error"), left.error);
    assert.ok(left.download().textContent.startsWith("Download"));
    left.ed.hidden = left.settingsPage.hidden = left.view.hidden = false;
    left.document.visibilityState = "visible";
    if (hidden === "unmounted") left.settingsPage.append(left.ed);
    assert.equal(left.ed.isConnected, true);
    left.download().click(); // returning to a page requires a deliberate retry of the same sealed bytes
    assert.equal(left.downloads.length, 1);
    assert.equal(left.downloads[0].blob, left.sealed);
    assert.equal(left.requests.length, 1);
    assert.equal(left.error.textContent, "");
    left.flushTimers();
  }
  for (const flag of [false, undefined]) {
    const desktop = fixture(flag);
    await desktop.go.onclick();
    assert.equal(desktop.requests.length, 0);
    assert.equal(desktop.downloads.length, 0);
    assert.equal(desktop.apiCalls.length, 1);
    assert.equal(desktop.apiCalls[0][0], "backup/export");
    assert.equal(Object.hasOwn(desktop.apiCalls[0][1], "sink"), false);
    assert.equal(desktop.ed.children[0].textContent, "Saved to ~/desktop.magpie-backup");
    assert.equal(desktop.ed.querySelector(".editor-error"), null); // querySelector must not invent an error node
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
`
