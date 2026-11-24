package gui

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/yetone/magpie/internal/mobilepolicy"
)

const androidEditorMintPath = "/api/androidhost/browser-session"
const androidEditorEntryPath = "/androidhost/browser-entry"
const androidPluginGatewayRestorePath = "/api/androidhost/plugin-gateway-access/restore"
const androidEditorProofHeader = "X-Magpie-Android-Editor-Proof"
const androidManagerHeader = "X-Magpie-Android-Manager"

const androidEditorTicketTTL = 30 * time.Second
const androidEditorProofTTL = 30 * time.Minute
const androidEditorTicketLimit = 8
const androidEditorProofLimit = 16

// The proof stays in sessionStorage and is sent only in a request header. The
// bootstrap also wraps detached download anchors (the official page creates and
// clicks some without attaching them to the document) and gives API fetch calls
// redirect:"error". The host independently rejects proof-authenticated API
// redirects so XHR and older fetch implementations cannot forward the header to
// another origin.
const androidBrowserBootstrap = `(function(){
  var proof="";
  try{proof=sessionStorage.getItem("magpie.androidEditorProof")||"";}catch(_){}
  function failed(){var root=document.body||document.documentElement;if(root)root.textContent="请从 Magpie 应用内重新打开官方编辑器。";}
  if(!proof){failed();return;}
  function apiURL(raw){
    try{
      var url=new URL(typeof raw==="string"?raw:(raw&&raw.url)||"",location.href);
      return url.origin===location.origin&&url.pathname.indexOf("/api/")===0?url:null;
    }catch(_){return null;}
  }
  var nativeFetch=window.fetch.bind(window);
  window.fetch=function(input,init){
    var url=apiURL(typeof input==="string"?input:(input&&input.url)||"");
    if(url){
      var headers=new Headers((init&&init.headers)||(input instanceof Request?input.headers:undefined));
      headers.set("X-Magpie-Android-Editor-Proof",proof);
      init=Object.assign({},init||{},{headers:headers,redirect:"error"});
    }
    return nativeFetch(input,init);
  };
  var NativeXHR=window.XMLHttpRequest;
  if(NativeXHR){
    window.XMLHttpRequest=function(){
      var xhr=new NativeXHR(),open=xhr.open,send=xhr.send;
      xhr.open=function(method,url){this.__magpieURL=new URL(url,location.href);return open.apply(this,arguments);};
      xhr.send=function(){
        if(this.__magpieURL&&this.__magpieURL.origin===location.origin&&this.__magpieURL.pathname.indexOf("/api/")===0){
          this.setRequestHeader("X-Magpie-Android-Editor-Proof",proof);
        }
        return send.apply(this,arguments);
      };
      return xhr;
    };
    window.XMLHttpRequest.prototype=NativeXHR.prototype;
  }
  function responseName(response,fallback){
    var value=response.headers.get("Content-Disposition")||"";
    var match=/filename\*?=(?:UTF-8''|")?([^";]+)/i.exec(value);
    if(!match)return fallback;
    var name=match[1].replace(/^["']|["']$/g,"");
    try{return decodeURIComponent(name);}catch(_){return name;}
  }
  function download(url,requested){
    window.fetch(url.href,{method:"GET",credentials:"same-origin",redirect:"error",headers:new Headers()}).then(function(response){
      if(!response.ok)throw new Error("API download refused: "+response.status);
      var fallback=requested||url.pathname.split("/").pop()||"download";
      var name=responseName(response,fallback);
      return response.blob().then(function(blob){return {blob:blob,name:name};});
    }).then(function(file){
      if(!window.URL||!URL.createObjectURL)throw new Error("downloads are unavailable");
      var href=URL.createObjectURL(file.blob);
      var a=document.createElement("a");
      a.href=href;
      a.download=file.name;
      if(document.body)document.body.appendChild(a);
      NativeAnchorClick.call(a);
      a.remove();
      setTimeout(function(){URL.revokeObjectURL(href);},60000);
    }).catch(function(err){
      if(window.console&&console.error)console.error("magpie editor download:",err);
    });
  }
  var NativeAnchorClick=window.HTMLAnchorElement&&HTMLAnchorElement.prototype.click;
  if(NativeAnchorClick){
    HTMLAnchorElement.prototype.click=function(){
      var url=apiURL(this.href);
      if(url){download(url,this.download);return;}
      return NativeAnchorClick.apply(this,arguments);
    };
  }
  var nativeOpen=window.open;
  window.open=function(raw,target,features){
    var url=apiURL(raw);
    if(url){download(url,"");return null;}
    return nativeOpen.apply(window,arguments);
  };
  try{
    var req=new NativeXHR();
    req.open("GET","/boot.js",false);
    req.setRequestHeader("X-Magpie-Android-Editor-Proof",proof);
    req.send();
    if(req.status===200)(0,eval)(req.responseText);
    else failed();
  }catch(_){failed();}
})();`

type androidHostEditorAuthorizedKey struct{}

// androidHostEditorProof is defense in depth around the complete desktop API.
// The Android-host editor wrapper adds this context marker only after it has
// checked the native manager header or a live browser proof. Mobile builds keep
// their existing webGuard cookie flow and pass through unchanged.
func androidHostEditorProof(next http.Handler) http.Handler {
	if !mobilepolicy.FullAdmin {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		required := r.URL.Path == "/boot.js" || r.URL.Path == "/dev.js" ||
			strings.HasPrefix(r.URL.Path, "/api/")
		if required && r.Context().Value(androidHostEditorAuthorizedKey{}) != true {
			http.Error(w, "browser proof or native management authentication required", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func androidHostEditor(port, key, where string, box boxSignIn, page http.Handler) http.Handler {
	return androidHostEditorWithClock(port, key, where, box, page, time.Now)
}

func androidHostEditorWithClock(port, key, where string, box boxSignIn, page http.Handler, clock func() time.Time) http.Handler {
	if !mobilepolicy.FullAdmin {
		return webGuard("magpie_web_"+port, key, where, box, webOwnPage(page))
	}
	var mu sync.Mutex
	tickets := make(map[string]time.Time)
	proofs := make(map[string]time.Time)
	issue := func(target map[string]time.Time, ttl time.Duration, limit int) (string, bool) {
		var random [32]byte
		if _, err := rand.Read(random[:]); err != nil {
			return "", false
		}
		value, now := hex.EncodeToString(random[:]), clock()
		mu.Lock()
		defer mu.Unlock()
		for old, expiry := range target {
			if !expiry.After(now) {
				delete(target, old)
			}
		}
		if len(target) >= limit {
			return "", false
		}
		target[value] = now.Add(ttl)
		return value, true
	}
	consumeTicket := func(value string) bool {
		now := clock()
		mu.Lock()
		defer mu.Unlock()
		expiry, ok := tickets[value]
		delete(tickets, value)
		return ok && expiry.After(now)
	}
	validProof := func(value string) bool {
		if !validAndroidEditorRandom(value) {
			return false
		}
		now := clock()
		mu.Lock()
		defer mu.Unlock()
		expiry, ok := proofs[value]
		if !expiry.After(now) {
			delete(proofs, value)
			return false
		}
		return ok
	}
	managerAuthorized := func(r *http.Request) bool {
		if !nativeAndroidManagerRequest(r) {
			return false
		}
		provided := androidEditorSingleHeader(r, androidManagerHeader)
		return provided != "" && len(provided) == len(key) &&
			subtle.ConstantTimeCompare([]byte(provided), []byte(key)) == 1
	}
	managerDenied := func(w http.ResponseWriter, r *http.Request) {
		if androidEditorSingleHeader(r, androidManagerHeader) != "" && !nativeAndroidManagerRequest(r) {
			http.Error(w, "native management requests must not carry browser metadata", http.StatusForbidden)
			return
		}
		http.Error(w, "native management authentication required", http.StatusUnauthorized)
	}
	protectedPage := webOwnPage(page)
	expectedHost := "127.0.0.1:" + port
	authorized := func(r *http.Request) *http.Request {
		return r.WithContext(context.WithValue(r.Context(), androidHostEditorAuthorizedKey{}, true))
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != expectedHost || r.URL.Host != "" && r.URL.Host != expectedHost {
			http.Error(w, "unexpected editor host", http.StatusMisdirectedRequest)
			return
		}
		switch r.URL.Path {
		case androidEditorMintPath:
			if r.Method != http.MethodPost || r.URL.RawPath != "" || r.URL.RawQuery != "" {
				http.NotFound(w, r)
				return
			}
			if !managerAuthorized(r) {
				managerDenied(w, r)
				return
			}
			ticket, ok := issue(tickets, androidEditorTicketTTL, androidEditorTicketLimit)
			if !ok {
				http.Error(w, "could not open browser editor", http.StatusServiceUnavailable)
				return
			}
			w.Header().Set("Cache-Control", "no-store")
			writeJSON(w, map[string]string{"url": "http://" + expectedHost + androidEditorEntryPath + "?ticket=" + ticket})
			return
		case androidEditorEntryPath:
			query, queryErr := url.ParseQuery(r.URL.RawQuery)
			values := query["ticket"]
			if r.Method != http.MethodGet || r.URL.RawPath != "" || queryErr != nil ||
				len(query) != 1 || len(values) != 1 || !validAndroidEditorRandom(values[0]) ||
				!consumeTicket(values[0]) {
				http.NotFound(w, r)
				return
			}
			proof, ok := issue(proofs, androidEditorProofTTL, androidEditorProofLimit)
			if !ok {
				http.Error(w, "too many browser editor sessions", http.StatusServiceUnavailable)
				return
			}
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'")
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Referrer-Policy", "no-referrer")
			w.Header().Set("X-Content-Type-Options", "nosniff")
			_, _ = fmt.Fprintf(w, `<!doctype html><meta charset="utf-8"><script>sessionStorage.setItem("magpie.androidEditorProof",%q);location.replace("/")</script>`, proof)
			return
		case androidPluginGatewayRestorePath:
			if r.Method != http.MethodPost || r.URL.RawPath != "" || r.URL.RawQuery != "" {
				http.NotFound(w, r)
				return
			}
			if !managerAuthorized(r) {
				managerDenied(w, r)
				return
			}
			w.Header().Set("Cache-Control", "no-store")
			if err := RestoreAndroidHostBridge(); err != nil {
				http.Error(w, "plugin gateway access could not be restored", http.StatusInternalServerError)
				return
			}
			writeJSON(w, map[string]bool{"ok": true})
			return
		}
		if r.URL.Path == "/" && r.Method == http.MethodGet {
			serveAndroidEditorIndex(w, r, page)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/_magpie/plugin/") {
			// Plugin pages carry their own unguessable, run-scoped token in the
			// path. The page handler verifies it after this exact-Host gate; an
			// iframe or its event-stream service cannot attach a custom header.
			protectedPage.ServeHTTP(w, r)
			return
		}
		if r.URL.Path == "/boot.js" || r.URL.Path == "/dev.js" || strings.HasPrefix(r.URL.Path, "/api/") {
			browserProof := androidEditorSingleHeader(r, androidEditorProofHeader)
			proofOK := validProof(browserProof)
			managerOK := managerAuthorized(r)
			// Provider/library icons are file bytes, not configuration. CSS
			// images and image elements cannot attach a custom header; allow
			// only same-origin or non-browser GET/HEAD requests, still through
			// webOwnPage, and never for another API route.
			safeAsset := androidHostEditorSafeAsset(r)
			if !proofOK && !managerOK && !safeAsset {
				if androidEditorSingleHeader(r, androidManagerHeader) != "" {
					managerDenied(w, r)
				} else {
					http.Error(w, "browser proof or native management authentication required", http.StatusUnauthorized)
				}
				return
			}
			r = authorized(r)
			if proofOK {
				protectedPage.ServeHTTP(&androidEditorNoRedirect{ResponseWriter: w}, r)
			} else {
				protectedPage.ServeHTTP(w, r)
			}
			return
		}
		page.ServeHTTP(w, r)
	})
}

func androidEditorSingleHeader(r *http.Request, name string) string {
	values := r.Header.Values(name)
	if len(values) != 1 {
		return ""
	}
	return values[0]
}

func validAndroidEditorRandom(value string) bool {
	if len(value) != 64 {
		return false
	}
	raw, err := hex.DecodeString(value)
	return err == nil && len(raw) == 32 && hex.EncodeToString(raw) == value
}

func nativeAndroidManagerRequest(r *http.Request) bool {
	if len(r.Header.Values("Origin")) != 0 || len(r.Header.Values("Referer")) != 0 {
		return false
	}
	for name := range r.Header {
		if strings.HasPrefix(strings.ToLower(name), "sec-fetch-") {
			return false
		}
	}
	return true
}

func androidHostEditorSafeAsset(r *http.Request) bool {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		return false
	}
	if !strings.HasPrefix(r.URL.Path, "/api/icons/") {
		return false
	}
	switch r.Header.Get("Sec-Fetch-Site") {
	case "", "none", "same-origin":
		return true
	default:
		return false
	}
}

type androidEditorNoRedirect struct {
	http.ResponseWriter
	status      int
	blocked     bool
	wroteNotice bool
}

func (w *androidEditorNoRedirect) Unwrap() http.ResponseWriter {
	return w.ResponseWriter
}

func (w *androidEditorNoRedirect) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	if androidEditorIsRedirect(status) {
		w.blocked = true
		for name := range w.Header() {
			delete(w.Header(), name)
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		status = http.StatusBadGateway
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *androidEditorNoRedirect) Write(body []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	if w.blocked {
		if !w.wroteNotice {
			w.wroteNotice = true
			_, _ = w.ResponseWriter.Write([]byte("editor API redirect blocked\n"))
		}
		return len(body), nil
	}
	return w.ResponseWriter.Write(body)
}

func (w *androidEditorNoRedirect) Flush() {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	if flusher, ok := w.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

func androidEditorIsRedirect(status int) bool {
	switch status {
	case http.StatusMovedPermanently, http.StatusFound, http.StatusSeeOther,
		http.StatusTemporaryRedirect, http.StatusPermanentRedirect:
		return true
	default:
		return false
	}
}

type androidEditorCapture struct {
	header http.Header
	status int
	body   bytes.Buffer
}

func (w *androidEditorCapture) Header() http.Header {
	return w.header
}

func (w *androidEditorCapture) WriteHeader(status int) {
	if w.status == 0 {
		w.status = status
	}
}

func (w *androidEditorCapture) Write(body []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.body.Write(body)
}

func serveAndroidEditorIndex(w http.ResponseWriter, r *http.Request, page http.Handler) {
	index := r.Clone(r.Context())
	index.URL.Path, index.URL.RawPath, index.URL.RawQuery = "/", "", ""
	for _, name := range []string{"If-None-Match", "If-Modified-Since", "If-Range", "Range"} {
		index.Header.Del(name)
	}
	capture := &androidEditorCapture{header: make(http.Header)}
	page.ServeHTTP(capture, index)
	if capture.status != 0 && capture.status != http.StatusOK {
		http.Error(w, "browser editor page could not be prepared", http.StatusInternalServerError)
		return
	}
	body := capture.body.String()
	needle := `<script src="boot.js"></script>`
	bootstrap := `<script>` + androidBrowserBootstrap + `</script>`
	if !strings.Contains(body, needle) {
		http.Error(w, "browser editor page could not be prepared", http.StatusInternalServerError)
		return
	}
	body = strings.Replace(body, needle, bootstrap, 1)
	for name, values := range capture.header {
		for _, value := range values {
			w.Header().Add(name, value)
		}
	}
	w.Header().Del("Set-Cookie")
	for _, name := range []string{"Accept-Ranges", "Content-Encoding", "Content-Length", "Content-Range", "ETag", "Last-Modified", "Transfer-Encoding"} {
		w.Header().Del(name)
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(body))
}
