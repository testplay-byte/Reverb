# Ad-Blocking Research for the Reverb Android App (OkHttp + WebView)

**Task ID:** 2-d · **Date:** 2026-07-14
**Scope:** Survey all credible approaches to ad-blocking in an Android app that (a) makes OkHttp scraping/API calls, (b) embeds a WebView for the universal video extractor + Cloudflare solving, (c) renders ads-removed "rebuilt native UI" for catalog pages. Produce a concrete recommendation that does **not** break video detection.

The "Reverb" app concept and prior research context live in `/home/z/my-project/PLAN.md` and `/home/z/my-project/worklog.md` (tasks 2-a, 2-b, 2-c). The critical cross-cutting constraint comes from task 2-b/2-c: the universal video extractor (which we're stealing from `yuzono/anime-extensions/lib/universalextractor`) instruments `WebView.shouldInterceptRequest` with `Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$")` and hands every match to `PlaylistUtils`. **If an ad-blocker running in the same WebView silently swallows a video request, the extractor will never see the video URL and the app's primary feature silently fails.** Everything below is designed around not violating that contract.

---

## 0. TL;DR / Recommendation

| Layer | Approach | Library / Engine | Why |
|---|---|---|---|
| **OkHttp scraping calls** (catalog HTML, JSON APIs, image thumbs) | EasyList-based interceptor, **media-aware allowlist** | Homegrown Kotlin `Interceptor` wrapping a hand-rolled or Brave-rust engine | App-scoped, zero privilege, deterministic, doesn't touch video CDNs |
| **WebView (universal extractor / Cloudflare / fallback browser)** | `shouldInterceptRequest` blocker that **runs after the extractor's tap**, plus cosmetic CSS/JS injection via `evaluateJavascript` | Homegrown Kotlin `WebViewClient` decorator + Brave-rust engine | One engine for both layers; one configuration source of truth |
| **Rebuilt native UI** | No blocker needed at all — we only render what we ourselves parsed | — | Ads never enter the app's render path |
| **Optional, off by default** | DNS-level blocking via local VPN (`VpnService`) | Reuse `celzero/rethink-app` patterns / `AdguardTeam/dnsproxy` if a user opts into "block ads system-wide" | Most invasive; offer only as power-user toggle |

**Concrete engine choice:** Start with a **hand-rolled Kotlin EasyList matcher** (~600 LOC) for v0.1 of Phase 0 because it has zero native build complexity, ships in a pure-Kotlin AAR, and is good enough for ≤50k rules. **Upgrade path:** if profiler shows >2% of request time in the matcher or >30 MB of rule memory, swap the matcher for `brave/adblock-rust` via `cargo-ndk` + JNI (~3–6 MB per ABI, 5–20× faster matching). The Kotlin `Interceptor` / `WebViewClient` facades stay identical — only the `Matcher` implementation changes.

**Filter lists to bundle (default-on):**
1. `EasyList` (English+international ads) — GPLv3 / CC-BY-SA 3.0+
2. `EasyPrivacy` (tracking pixels, beacons) — GPLv3 / CC-BY-SA 3.0+
3. `AdGuard Base` filter (a cleaner, frequently more up-to-date rewrite of EasyList for the modern web) — GPLv3
4. `AdGuard Annoyances` (cookie banners, newsletter popups, in-page popups) — GPLv3
5. `Peter Lowe's blocklist` (`pgl.yoyo.org`) — the small hosts-style list that already powers several Android browsers — informal license, free for all
6. **A regional list selected at runtime** based on `Locale.getDefault()` (EasyList Germany, EasyList China, RU AdList, etc.) — varies, mostly GPL3 or CC-BY-SA
7. **NOT bundled by default:** `AdGuard DNS Filter`, `EasyList Cookie List` (overlaps annoyances), and any "video ads" sub-list — those would block legitimate video pre-rolls on streaming sites, which we want to *detect* and *let through* (see §7).

**Critical correctness rule (the one thing that must never break):** the ad-blocker **must** defer to the video extractor. Concretely, in the WebView pipeline the order is:

```
shouldInterceptRequest(req) {
    1. VIDEO EXTRACTOR FIRST: if req.url matches /\.(mp4|m3u8|mpd|ts|aac|webm)(\?.*)?$/ -> HAND OFF to PlaylistUtils, return null (let it load).
    2. AD BLOCKER SECOND: if matcher.shouldBlock(req.url, req.contentType, initiator) -> return empty 204.
    3. DEFAULT: return null (let WebView load it).
}
```

In OkHttp the order is reversed (OkHttp doesn't have a video extractor — it just makes HTTP calls):

```
Interceptor chain:
    1. RATE LIMIT (per-host)         — passes through
    2. CLOUDFLARE BYPASS (WebView)   — passes through
    3. AD BLOCKER                    — short-circuits with synthetic 204 if shouldBlock
    4. (real network call)
```

For OkHttp the rule is even simpler: **never block anything whose request URL path ends in `.mp4|.m3u8|.mpd|.ts|.aac|.webm|.mkv` OR whose `Accept` header advertises a media type** (`video/*`, `audio/*`, `application/vnd.apple.mpegurl`, `application/x-mpegURL`, `application/dash+xml`). The `shouldBlock` implementation in code should accept a `mediaAllowlist: (HttpUrl, String?) -> Boolean` predicate that always returns `false` (don't block) for media. EasyList rules that *would* block media are filtered out at parse time (see §7.3).

---

## 1. Approach A — Filter-List Based (EasyList / uBlock Origin / AdGuard format)

### 1.1 What the format actually looks like

The de-facto standard filter syntax is documented across three sources: Adblock Plus's "How to write filters", uBlock Origin's `Static filter syntax` wiki, and AdGuard's "How to create your own ad filters" KB. uBlock Origin explicitly states: "uBlock Origin (uBO) supports most of the EasyList filter syntax" — so EasyList is the lowest common denominator.

There are two rule families:

**Network rules** (block a request before it leaves the page):

```
||doubleclick.net^                                 ← block all subdomains of doubleclick.net
||example.com/ads/$script                          ← block /ads/ paths, only script type
||ads.example.com^$third-party                     ← only block third-party requests
@@||cdn.example.com/video$media                    ← EXCEPTION: never block media on cdn.example.com
||tracker.example.com^$domain=site.com|other.com   ← block only when initiator is site.com or other.com
||cdn.example.com/analytics$xmlhttprequest         ← block XHR/fetch only
||malicious.example.com^$popup                     ← block popups from this domain
||adserver.example.com^$subdocument                ← block iframes only
||fonts.googleapis.com^$stylesheet,third-party     ← block CSS third-party
/banner/*/img^                                     ← wildcard path pattern
/advert.$image                                     ← any URL containing /advert. requesting an image
```

The leading `||` anchors the rule to a domain boundary (start of hostname or any subdomain separator). The trailing `^` is a separator character (anything except `A-Za-z0-9_.%-`). `$` introduces a comma-separated list of options: type filters (`script`, `image`, `stylesheet`, `subdocument`, `xmlhttprequest`, `media`, `font`, `popup`, `websocket`, `other`), modifiers (`third-party`/`1st-party`, `domain=`, `rewrite=`, `redirect=`, `important`, `csp=`, `badfilter`), and exception marker `@@`.

**Cosmetic rules** (hide elements on already-loaded pages, no network effect):

```
###google_ads_iframe                                ← ID selector, hide globally
example.com##.ad-banner                            ← hide .ad-banner on example.com only
example.com,~mail.example.com##.sidebar-ad         ← hide everywhere on example.com except mail subdomain
example.com##.promo:style(display: none !important) ← Extended CSS (AdGuard) — inject a style
example.com##+js(nobab.js)                         ← uBO scriptlet injection
example.com#$#document.body > div.ads { display:none; }  ← ABP CSS-rule syntax
```

Cosmetic rules are split into: simple element-hiding (`##selector`), exception element-hiding (`#@#selector`), and extended CSS (AdGuard `:style`, `:matches-css`, `:has`, `:not`, etc.). uBO adds **scriptlets** (`##+js(name, args)`) — these are pre-baked JS snippets that neutralize common anti-adblock / tracking patterns by patching `document.createElement`, `XMLHttpRequest`, etc. Scriptlets require a JS engine to execute; they're optional and we will **not** support them in v0.1 (the implementation cost is high; the gain is small for our use case because we control the page render anyway via the rebuilt native UI).

### 1.2 What it takes to parse + match these in Kotlin

Parsing is mostly string manipulation:

1. **Split into lines**, skip `!` comments and metadata (`! Title:`, `! Homepage:`, `! Last modified:`, `! Expires:` — we *do* want to capture `! Expires:` to drive our refresh schedule).
2. **Classify each non-comment line:**
   - Starts with `@@` → network exception rule.
   - Contains `##` or `#@#` → cosmetic rule.
   - Contains `#$#` or `#@$#` → ABP-style CSS rule.
   - Otherwise → network block rule.
3. **Split on `$`** to separate the URL pattern from options. Parse options list on `,`.
4. **Parse the URL pattern:** strip leading `||` (domain anchor) or `|` (full URL anchor). Build a fast matcher:
   - If the pattern is a pure hostname (e.g. `||doubleclick.net^`), store it in a `HashMap<String, Rule>` keyed by the **TLD+1** (the registrable domain via Public Suffix List). At match time, compute TLD+1 of the request host, look it up, and walk up the subdomain chain.
   - If the pattern contains path wildcards (`*` or `^` in the path), compile to a `Regex` (Kotlin's `Regex` is JVM `Pattern` under the hood — fast). Keep regexes for the same host grouped.
5. **Options** map into a small dataclass: `Set<RequestType>`, `Boolean thirdParty`, `Set<String> domainsInclude`, `Set<String> domainsExclude`, `Boolean important`, `Boolean popup`, `String? rewriteTarget`, etc.
6. **Cosmetic rules** store as `Map<InitiatorDomain, List<SelectorRule>>`. At page-load time, look up by current page's TLD+1 (and a generic `*` bucket), build a single CSS string `selector1, selector2, ... { display:none !important; }`, inject via `evaluateJavascript`.

**Performance tricks (required once you exceed ~10k rules):**
- Maintain a reverse index `Map<String, MutableList<NetworkRule>>` keyed by *tokens* extracted from the pattern (alphanumeric chunks of length ≥3). For a request URL, extract tokens and only check rules in the union of their buckets. This is the "filter trie" Brave and ABP both use; without it, matching devolves to O(N) per request and dominates page-load time.
- Pre-compile all regexes once at parse time and cache.
- For cosmetic rules, dedupe selectors — EasyList has thousands of duplicates across regional lists.
- The whole engine should be `Send`-equivalent on JVM (immutable after `Engine.compile()`); requests are read-only and can be matched concurrently.

### 1.3 Pros / cons for Reverb

**Pros:** No privileges needed (no root, no VPN). App-scoped — only Reverb's traffic is affected. Covers scripts/iframes, XHR, images, popups, cosmetic placeholders. Filter lists are updated daily by an active community — zero maintenance on our side beyond download/refresh. The same matcher instance can drive both the OkHttp interceptor and the WebView `shouldInterceptRequest` blocker.

**Cons:** Doesn't cover DNS-level blocking (a tracker on the same domain as content is invisible). Cosmetic rules need a JS engine if you want uBO scriptlets. Regex compilation for ~5–10% of EasyList rules is the slow part. **EasyList explicitly targets "Pre/mid/end video ads" and "Invideo/InSlideshow Ads"** — out of the box it WILL try to block pre-rolls on streaming sites, and those pre-rolls are often served from the same CDN as the main video, so a naive rule like `||cdn.example.com/ads/*` is fine but `||cdn.example.com^$media` would nuke the main video. We solve this with the media-allowlist predicate (§7).

**License:** EasyList is dual-licensed GPLv3+ **or** CC-BY-SA 3.0+ (per `easylist.to/pages/licence.html` and the uBO filter-list-licenses wiki). AdGuard filters are GPLv3. uBO's own filters are GPLv3. Fanboy's lists are GPLv3 with some "informal license, free for all" sub-lists. Bundling these as **data files** (not code) is generally accepted practice — Firefox, Brave, Kiwi, DuckDuckGo, Safari content-blockers all do it. The license requires **attribution** and, if you *modify* the lists, share-alike under the same license. We will bundle them unmodified (downloaded from upstream on app start) and display attribution in Settings → Open-source licenses.

---

## 2. Approach B — Brave's Rust Engine (`brave/adblock-rust`)

### 2.1 What it is

`adblock-rust` (MPL-2.0) is the standalone crate powering Brave's native adblocker and (as of Firefox 149, 2026) also bundled experimentally into Firefox. Its README enumerates:

- Network blocking
- Cosmetic filtering
- Resource replacements (uBO `redirect=` rules — inject a local placeholder for the blocked resource so the page doesn't break)
- Hosts-syntax support
- uBlock Origin syntax extensions (scriptlets, `redirect-rule`, etc.)
- iOS content-blocking format conversion (compile ABP rules → Safari `ContentBlock` JSON)
- Rust / Node.js / Python bindings out of the box

The Rust API surface (from `docs.rs/adblock`):

```rust
use adblock::{
    lists::{FilterSet, ParseOptions},
    request::Request,
    Engine,
};

let mut filter_set = FilterSet::new(true /* debug */);
filter_set.add_filter_list(rules_string, ParseOptions::default());
let engine = Engine::new_with_filter_set(filter_set);

let request = Request::new(
    "http://example.com/-advertisement-icon.",
    "http://example.com/helloworld",  // initiator / source URL
    "image",                          // request type
    ""                                // tab-level CSP (optional)
).unwrap();
let result = engine.check_network_request(&request);
// result.matched (bool), result.redirect, result.exception, ...

// Cosmetic:
let (css, specific_only) = engine.url_cosmetic_resources(
    "https://example.com/page", &Default::default()
);
```

### 2.2 Calling it from Android

Three paths, in increasing order of effort:

**Path 1 — use the existing `xaynetwork/adblock-rust-jni` wrapper.** A community JNI binding exists (referenced in Brave's docs and Rust internals threads) that exposes the engine as a Kotlin-callable `.so`. Caveat: the repo has had periods of inactivity — verify it tracks `adblock-rust` ≥ 0.11 before adopting.

**Path 2 — fork `Edsuns/AdblockAndroid` (LGPL-2.1).** This repo already ports Brave's older C++ ad-block engine to Android with a `WebViewClient` integration (`AdFilter.shouldIntercept(view, request)`), filter subscription management, and a demo app. 89 stars, but **last push was 2021-08-19** — stale. Useful as a reference for the integration shape, not as a long-term dep. Note: it's based on Brave's *C++* engine (brave/ad-block), not the newer Rust one — so you'd be carrying a C++ toolchain and an engine Brave has deprecated in favor of `adblock-rust`.

**Path 3 — roll our own JNI binding via `cargo-ndk`.** The cleanest long-term option:

```bash
# one-time
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk

# build per ABI (NDK 21+)
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ./jniLibs build --release
```

A minimal JNI surface is ~200 LOC of Rust (`#[no_mangle] extern "system" fn Java_..._Engine_new`, `_addFilterList`, `_checkNetwork`, `_urlCosmeticResources`, `_serialize`, `_deserialize`). Use `jni-rs` for argument marshalling. This is well-trodden territory — Mozilla's "Building and Deploying a Rust library on Android" (2017) and gendignoux's 2022 deep-dive both walk through it.

### 2.3 Cost analysis

| Item | Estimate |
|---|---|
| Native `.so` size per ABI (after `strip`, `opt-level=z`) | **~2.5–4 MB** for arm64-v8a, slightly less for armv7. Roughly halves if you skip the `css-validation` and `resource-assembler` features. |
| Total APK impact for 3 ABIs uncompressed | ~10 MB. With App Bundle ABI-splitting, each user downloads only one (~3 MB). |
| Memory at runtime (per Brave's 2025 v1.85 overhaul) | ~15 MB resident for a full EasyList+EasyPrivacy+AdGuard load (down from ~60 MB pre-overhaul). |
| Matching latency | Brave publishes micro-benchmarks of single-digit **microseconds** per `check_network_request`. Faster than any Kotlin regex implementation by ~5–20×. |
| Build complexity | Adds Rust toolchain + `cargo-ndk` to CI. ~1 day to wire up. The Mozilla and gendignoux blogs cover the gotchas (NDK version pinning, `ANDROID_NDK_ROOT`, `jniconv` for UTF-8 strings). |
| License | **MPL-2.0** — weak copyleft, file-scoped. Compatible with Apache-2.0 app code as long as you publish the Rust source for any modifications to `adblock-rust` itself. We don't need to modify it. |

### 2.4 Verdict on Brave-rust

**Use it for production, not for Phase 0.** Phase 0 (the universal-extractor de-risking spike) should ship with a hand-rolled Kotlin matcher so we don't add a Rust toolchain to a 2-week spike. Once Phase 0 passes the ≥7/10 sites gate, add Brave-rust as the production matcher behind the same `Matcher` interface. The interface is:

```kotlin
interface AdMatcher : Closeable {
    fun loadRules(text: String, sourceUrl: String)        // EasyList-syntax text
    fun checkNetwork(url: String, initiator: String, type: RequestType): BlockResult
    fun cosmeticResourcesFor(url: String): CosmeticResources
    fun serialize(): ByteArray                            // for caching compiled rules
    companion object { fun deserialize(bytes: ByteArray): AdMatcher }
}
```

Two implementations: `KotlinRegexMatcher` (Phase 0, ~600 LOC, ~10 µs/rule, fine up to ~30k rules) and `BraveRustMatcher` (Phase 1+, JNI, ~0.5 µs/rule, scales to 200k+ rules).

---

## 3. Approach C — DNS / Hosts-File Based (System-Wide)

### 3.1 Hosts-file approach (`AdAway/AdAway`)

AdAway (GPLv3) is the canonical hosts-file adblocker. Originally root-only — it replaced `/system/etc/hosts` with a merged file of all your hosts-source subscriptions. Since Android 8 it also runs in a **VPN mode that doesn't need root**: it spins up an `AndroidVpnService` that intercepts all DNS queries and returns `0.0.0.0` for any hostname that's in the merged hosts list.

The hosts file format is dead simple:
```
0.0.0.0 adserver.example.com
0.0.0.0 doubleclick.net
0.0.0.0 pubads.g.doubleclick.net
```

The advantage: works against any app, not just browsers. The disadvantage: the rule granularity is **whole hostname only**. You can't say "block `doubleclick.net/ads/*` but allow `doubleclick.net/video/*`". A streaming site that serves both ads and the main video from `cdn.streaming-site.com` is unsplittable at the DNS layer.

AdAway's README lists the permissions needed for VPN mode: `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `QUERY_ALL_PACKAGES`. **Running `AndroidVpnService` puts a persistent key icon in the status bar** and prevents other VPN apps from running simultaneously. For an app whose primary value is video extraction (which itself may need to be tunneled through a user's personal VPN for region unlocking), this is a major UX cost.

### 3.2 DNS-proxy approach (`AdguardTeam/dnsproxy`, RethinkDNS, Nebulo, PersonalDNSfilter)

`dnsproxy` (Apache-2.0, Go) is a DNS proxy server. On Android you wouldn't ship `dnsproxy` directly (it's a Go binary, ~15 MB); instead you'd reuse its filtering logic or — more practically — embed a DoH/DoT client that talks to an upstream filtered resolver like AdGuard DNS (`dns.adguard-dns.com`) or NextDNS.

`celzero/rethink-app` (Rethink DNS + Firewall + VPN, LGPL-3.0) is the modern open-source Android equivalent: it implements both an on-device DNS resolver (with built-in blocklists) and a per-app firewall via `AndroidVpnService`. Worth studying if we ever expose a "block ads system-wide" toggle, because it's the cleanest implementation of an Android DNS firewall in 2026.

### 3.3 Verdict on DNS-based

**Don't use as the primary blocker for Reverb.** Reasons:

1. **Correctness:** DNS-level blocking can't distinguish `cdn.streaming-site.com/ads/preroll.mp4` from `cdn.streaming-site.com/main.m3u8` — both resolve to the same IP. This breaks video detection catastrophically.
2. **Scope:** VPN service blocks *all* of the user's apps, not just Reverb. We'd be making a privacy decision for the whole device, which is fine for a dedicated firewall app but inappropriate for a video app.
3. **Privilege cost:** Persistent VPN notification, blocks other VPNs, drains battery (~3–5% / day on AdAway's own telemetry).
4. **Doesn't cover cosmetic ads** — DNS can't hide elements on a rendered page.

**Use case where it fits:** offer it as an opt-in "Block ads system-wide" toggle for power users. Implementation: ship a thin wrapper around `AndroidVpnService` that reuses the same `AdMatcher` to consult the merged hosts list, returning `0.0.0.0` for blocked hostnames. ~300 LOC + a foreground service notification. **Default off.**

---

## 4. Approach D — OkHttp Interceptor (App-Scoped, Lightweight)

### 4.1 The pattern

OkHttp interceptors are the cleanest seam for blocking on OkHttp traffic. The chain looks like:

```kotlin
class AdBlockInterceptor(
    private val matcher: AdMatcher,
    private val mediaAllowlist: (HttpUrl, String?) -> Boolean = ::defaultMediaAllow,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val type = requestTypeOf(req)                              // GET/POST + content-type → RequestType
        if (mediaAllowlist(req.url, req.header("Accept"))) {
            return chain.proceed(req)                              // NEVER block media
        }
        val initiator = req.header("Referer") ?: req.url.topPrivateDomain() ?: ""
        val result = matcher.checkNetwork(req.url.toString(), initiator, type)
        if (result.shouldBlock) {
            return Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1)
                .code(204).message("No Content")
                .body(EMPTY_BODY)
                .build()
        }
        return chain.proceed(req)
    }
}

private fun defaultMediaAllow(url: HttpUrl, accept: String?): Boolean {
    val path = url.encodedPath
    if (path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".ts")
        || path.endsWith(".mpd") || path.endsWith(".m4s") || path.endsWith(".aac")
        || path.endsWith(".webm") || path.endsWith(".mkv")) return true
    if (accept != null && (accept.contains("video/") || accept.contains("audio/")
            || accept.contains("application/vnd.apple.mpegurl")
            || accept.contains("application/x-mpegURL")
            || accept.contains("application/dash+xml"))) return true
    return false
}

enum class RequestType { SCRIPT, IMAGE, STYLESHEET, SUBDOCUMENT, XMLHTTPREQUEST, MEDIA, FONT, WEBSOCKET, OTHER, POPUP }

private fun requestTypeOf(req: Request): RequestType = when {
    req.header("Sec-Fetch-Dest") == "script" -> RequestType.SCRIPT
    req.header("Sec-Fetch-Dest") == "style"  -> RequestType.STYLESHEET
    req.header("Sec-Fetch-Dest") == "image"  -> RequestType.IMAGE
    req.header("Sec-Fetch-Dest") == "font"   -> RequestType.FONT
    req.header("Sec-Fetch-Dest")?.startsWith("iframe") == true -> RequestType.SUBDOCUMENT
    req.method == "POST" && req.header("Content-Type")?.contains("application/json") == true -> RequestType.XMLHTTPREQUEST
    else -> RequestType.OTHER
}
```

Wire it into the OkHttpClient used by the scraper:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(RateLimitInterceptor(perHost = 1, perSeconds = 2))
    .addInterceptor(CloudflareInterceptor(webViewPool))
    .addInterceptor(AdBlockInterceptor(matcher))
    .cookieJar(appCookieJar)
    .build()
```

### 4.2 Why this fits Reverb perfectly

- **Zero privilege.** No VPN, no root. App-scoped by construction.
- **Deterministic.** A blocked call returns a synthetic 204 in microseconds — no network roundtrip. The scraper sees a "successful" but empty response and can fall through to the next strategy.
- **Works for catalog HTML, JSON APIs, and image thumb requests** — the three things the scraper actually does over OkHttp.
- **Media-allowlist is bulletproof.** Because we control the predicate, we can guarantee that NO EasyList rule will ever block a `.m3u8`/`.mp4`/etc. request, regardless of what filters say.
- **Easy to disable per-request.** When we know we're hitting a Cloudflare challenge (`CloudflareInterceptor` will retry through the WebView solver), we can set a thread-local flag to bypass the ad blocker for that single call to avoid races.

### 4.3 Limitations

- **Doesn't cover WebView traffic** at all (WebView has its own network stack; OkHttp interceptors don't see it). That's why §5 exists.
- **Doesn't cover popups/redirects** — those are browser navigations, not HTTP requests. Need `WebViewClient.shouldOverrideUrlLoading` for that.
- **Doesn't cover cosmetic ads** in the rebuilt native UI. (Doesn't need to — we control the UI render.)

### 4.4 Existing libraries

- **`MonsterTechnoGits/WebViewAdblock-Library`** — JitPack dep, ~4 lines to wire into a WebView. Uses a hardcoded host-list approach, not full EasyList. Good as a "minimal proof of concept" reference, **don't ship in production** (last commit ancient, no filter-list format support).
- **`Edsuns/AdblockAndroid`** — discussed in §2.2; ships a Brave-C++-engine-backed `AdFilter` that plugs into both WebView and (with a small adapter) OkHttp.
- **`adblockplus/libadblockplus-android`** — Adblock Plus's official Android SDK. AAR with `AdblockWebView` class. **License: LGPL-2.1+ linked to ABP's `libadblockplus` C++ core (which embeds V8).** APK impact: ~8–15 MB per ABI for the V8 binary alone. **Not recommended** — V8 is overkill for our needs and the maintenance cadence of the ABP SDK is slow.
- **DDG Android's tracker-blocking module** (Apache-2.0) — DuckDuckGo's Android app has an internal tracker-protection library that uses their `privacy-configuration` repo (JSON of tracker domains + bundled EasyList-style rules). Worth studying for the URL-matching patterns, **not** reusable as a standalone AAR (it's tightly coupled to the DDG app shell).

### 4.5 Recommendation

**Homegrown Kotlin `AdBlockInterceptor` wrapping the same `AdMatcher` interface used by the WebView blocker.** ~80 LOC of glue + the matcher. No external AAR needed for Phase 0.

---

## 5. Approach E — WebView `shouldInterceptRequest` + Cosmetic CSS Injection

### 5.1 The network-blocking pattern

Android's `WebViewClient.shouldInterceptRequest(WebView, WebResourceRequest)` is called for **every** subresource the WebView loads — scripts, stylesheets, images, XHR, fetch, iframes, video sources, websocket upgrades. Returning `null` lets the request proceed; returning a `WebResourceResponse` short-circuits with our content.

```kotlin
class AdBlockingWebViewClient(
    private val matcher: AdMatcher,
    private val delegate: WebViewClient,           // wraps the universal extractor + CF solver
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        val url = req.url.toString()

        // 1. VIDEO EXTRACTOR FIRST (critical ordering)
        //    The extractor wants to SEE these URLs, so we must NOT swallow them.
        if (VIDEO_URL_REGEX.containsMatchIn(url)) {
            return delegate.shouldInterceptRequest(view, req)   // usually null; let it load
        }

        // 2. AD BLOCKER SECOND
        val initiator = view.url ?: ""
        val type = requestTypeOf(req)
        val result = matcher.checkNetwork(url, initiator, type)
        if (result.shouldBlock) {
            return emptyResponse(req)                  // synthetic 204
        }

        // 3. DEFAULT
        return delegate.shouldInterceptRequest(view, req)
    }

    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
        // Block popup ads / known ad redirects
        val result = matcher.checkNetwork(req.url.toString(), view.url ?: "", RequestType.POPUP)
        if (result.shouldBlock) return true
        return delegate.shouldOverrideUrlLoading(view, req)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        injectCosmeticFilters(view, url)
        delegate.onPageFinished(view, url)             // CF solver etc.
    }

    private fun injectCosmeticFilters(view: WebView, pageUrl: String) {
        val resources = matcher.cosmeticResourcesFor(pageUrl)
        if (resources.stylesheet.isBlank()) return
        val js = """
            (function(){
                var style = document.createElement('style');
                style.type = 'text/css';
                style.id = 'reverb-cosmetic';
                document.getElementById('reverb-cosmetic')?.remove();
                style.textContent = ${jsStringLiteral(resources.stylesheet)};
                document.head.appendChild(style);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun emptyResponse(req: WebResourceRequest): WebResourceResponse {
        // 204 No Content
        val headers = mapOf("Content-Length" to "0")
        return WebResourceResponse(
            "text/plain", "utf-8", 204, "No Content", headers,
            ByteArrayInputStream(ByteArray(0))
        )
    }

    companion object {
        private val VIDEO_URL_REGEX = Regex(""".*\.(mp4|m3u8|mpd|ts|m4s|aac|webm|mkv)(\?.*)?$""")
    }
}
```

Two important subtleties:

1. **`shouldInterceptRequest` is called on a background binder thread, NOT the UI thread.** Don't touch `view` except for read-only `view.url`. `evaluateJavascript` for cosmetic injection is safe — it queues onto WebView's internal thread.
2. **AJAX/XHR requests DO go through `shouldInterceptRequest`** (this was surprising to many; see the joshuatz article). `shouldOverrideUrlLoading` is only called for top-level page navigations. So for popup-by-`window.open`, you need to also override `WebChromeClient.onCreateWindow` and inspect the target URL.

### 5.2 The critical ordering — extractor BEFORE blocker

This is the single most important implementation detail. The universal extractor's regex is:

```kotlin
Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$")     // from yuzono/anime-extensions/lib/universalextractor
```

If the ad blocker ran first and returned an empty 204 for a URL that the extractor was interested in, the extractor would never see it. Even worse — if the ad blocker ran first and *allowed* the request through but the extractor's regex wasn't checked yet, the extractor would never be invoked. The fix is structural: **always run the extractor's regex first; if it matches, hand off to the extractor and skip the blocker entirely.** This guarantees the extractor sees 100% of video-shaped URLs, even if some of them are technically ad pre-rolls.

This means a video ad pre-roll on `cdn.streaming-site.com/ads/preroll.mp4` *will* be detected as a video and *may* end up in the user's playlist. That's a known, acceptable trade-off — the user sees a pre-roll in their stream list and can skip it. The alternative (block the pre-roll) risks blocking the main video too, since both are on the same CDN and share URL patterns. Better to over-detect than under-detect.

If we later want to filter detected videos by ad-ness, we can do it at the *playlist* level (after extraction) — by then we have the full m3u8 manifest and can inspect `#EXT-X-DISCONTINUITY` markers, segment durations (ad segments are often exactly 15/30 s), and `#EXT-X-ASSET` tags (VAST 4.0 ad markers). That's a Phase 2+ enhancement.

### 5.3 Cosmetic filtering via `evaluateJavascript`

The `cosmeticResourcesFor(url)` matcher call returns the merged CSS for the current page's domain. We inject it as a `<style>` element after `onPageFinished`. This hides ad placeholders (`###ad-banner`, `##.ad-container`, etc.) without touching the network.

**Important:** for sites where the rebuilt native UI replaces the WebView entirely, cosmetic injection is moot — we don't render the page. For the WebView-fallback path (universal extractor + Cloudflare), cosmetic injection matters because the user briefly sees the WebView before we hand off to the native player.

Brave's C++ engine produces two outputs from `url_cosmetic_resources`: a generic stylesheet (rules with no domain restriction) and a set of "specific" selectors (rules restricted to this domain). We inject both. AdGuard's `:style(...)` extended-CSS rules can't be expressed as plain CSS and would require a small JS runtime; we skip them in v0.1.

### 5.4 Coverage matrix for the WebView approach

| Ad type | Covered by `shouldInterceptRequest` | Covered by cosmetic injection | Notes |
|---|---|---|---|
| (a) Ad scripts/iframes | ✅ | — | EasyList `$script`/`$subdocument` rules |
| (b) Ad API calls from site's own JS | ✅ | — | EasyList `$xmlhttprequest` rules |
| (c) Popups/redirects | ⚠️ partial | — | `shouldOverrideUrlLoading` covers top-level nav; `onCreateWindow` covers `window.open`; can't catch `location.href = "..."` redirects until they fire |
| (d) Cosmetic (hidden ad placeholders) | — | ✅ | `##.selector` rules |
| (e) Video pre-roll ads | ❌ by design | — | We explicitly DON'T block these — extractor must see them (see §7) |

---

## 6. Comparison Matrix (All Five Approaches)

| Approach | Root? | VPN? | APK / native impact | (a) scripts/iframes | (b) ad API XHR | (c) popups | (d) cosmetic | (e) video pre-roll | OkHttp latency | WebView page-load | Maintenance | License |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **A. Filter-list (EasyList) matcher** | No | No | ~50 KB code + ~1 MB lists | ✅ | ✅ | ✅ | ✅ (with JS injection) | ⚠️ (will try to block — must exclude) | +20–100 µs/call (Kotlin regex), +1–5 µs/call (Brave-rust) | +1–5% with Brave-rust; +5–15% with naive Kotlin | Low — community updates lists daily | Matcher code: ours (Apache-2.0). Lists: GPL3 / CC-BY-SA 3.0+ |
| **B. Brave-rust engine (JNI)** | No | No | ~3 MB/ABI (.so) + ~1 MB lists | ✅ | ✅ | ✅ | ✅ | ⚠️ (same as A) | +1–5 µs/call | +1–3% | Low — Brave maintains the engine; we maintain JNI glue | MPL-2.0 |
| **C. DNS / hosts (AdAway-style)** | Optional (root for `/system/etc/hosts`) | Yes (VpnService, no-root) | ~500 KB app code + ~5 MB hosts file | ❌ (whole-host only) | ❌ | ❌ | ❌ | ❌ **catastrophic** (blocks entire CDN) | 0 (handled at OS level) | 0 (handled at OS level) | Medium — hosts file weekly merges | GPLv3 (AdAway); Apache-2.0 (dnsproxy); LGPL-3.0 (RethinkDNS) |
| **D. OkHttp interceptor** | No | No | ~5 KB code + matcher | ✅ (only OkHttp traffic) | ✅ | ❌ | ❌ | ✅ via media-allowlist | +1–50 µs/call | 0 (doesn't touch WebView) | Low | Ours (Apache-2.0) |
| **E. WebView `shouldInterceptRequest` + CSS injection** | No | No | ~5 KB code + matcher | ✅ (only WebView traffic) | ✅ | ⚠️ (top-level only) | ✅ | ⚠️ (must defer to extractor — see §7) | 0 | +2–10% | Low | Ours (Apache-2.0) |

**Key observation:** A, B, D, and E share the *same matcher*; the only thing that differs is *where* the matcher is invoked. The natural architecture is one `AdMatcher` instance + two thin glue layers (OkHttp interceptor + WebView client). Approach C is orthogonal and only added as opt-in system-wide blocking.

---

## 7. The Critical Correctness Issue — Ad-Blocker Must Not Break Video Detection

This deserves its own section because it's the one design decision that, if wrong, silently ruins the app's core feature.

### 7.1 Why the conflict exists

The universal video extractor (researched in task 2-b, `lib/universalextractor`) works by:

1. Spinning up an Android `WebView` pointed at the embedding page (the iframe to a video host like Streamtape, Dood, Filemoon, etc.).
2. Overriding `WebViewClient.shouldInterceptRequest` to inspect every network request the page makes.
3. Matching each request URL against `Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$")`.
4. Handing the first match to `PlaylistUtils`, which fetches the m3u8/MPD manifest, parses it for variants/subtitles/audio tracks, and produces `List<Video>`.

The ad blocker wants to inspect those same requests to decide whether to block them. If both want `shouldInterceptRequest`, only one of them gets to return a `WebResourceResponse`. Whoever returns non-null wins.

### 7.2 The three failure modes

- **Failure mode 1 (blocker swallows video):** The blocker returns an empty 204 for `cdn.example.com/main.m3u8` because some EasyList rule matched. The extractor never sees the URL. User sees "No videos detected."
- **Failure mode 2 (blocker allows, but extractor not consulted):** The blocker returns `null` for the m3u8 (letting it through), but it returned `null` *before* the extractor got a chance to inspect the request. Same outcome: extractor misses it.
- **Failure mode 3 (extractor swallows ad):** The extractor's regex matches `cdn.example.com/ads/preroll.mp4`, hands it to `PlaylistUtils`, which fetches a 30 s pre-roll. User sees the pre-roll as the only "video." (This is the *least bad* failure — it's a UX papercut, not a broken feature.)

### 7.3 The fix — three layers of defense

**Layer 1 — Ordering.** Always run the extractor's regex check first. If it matches, return `delegate.shouldInterceptRequest(...)` (typically `null`, letting the page load normally). The ad blocker only runs on URLs that the extractor explicitly *didn't* claim.

**Layer 2 — MIME / extension allowlist at the matcher API.** Even if a misconfigured rule somehow runs against a video URL, the matcher's `checkNetwork` itself refuses to block anything matching `.\.(mp4|m3u8|mpd|ts|m4s|aac|webm|mkv)(\?.*)?$` or anything whose `Accept` header advertises a media type. This is implemented as a single guard at the top of `checkNetwork`:

```kotlin
override fun checkNetwork(url: String, initiator: String, type: RequestType): BlockResult {
    if (VIDEO_ALLOWLIST.containsMatchIn(url)) return BlockResult.Allowed("media-allowlist")
    if (type == RequestType.MEDIA) return BlockResult.Allowed("media-type")
    // ... actual rule matching ...
}
```

**Layer 3 — Parse-time rule filtering.** When loading an EasyList subscription, drop any rule whose `$`-options include `media` *and* whose pattern is broad enough to risk matching a legitimate video CDN. Specifically:
- Drop `||example.com^$media` rules where `example.com` is in our allowlist of known streaming CDNs (a small bundled list of ~50 hosts: `*.cloudfront.net`, `*.akamaihd.net`, `*.kxcdn.com`, `*.mangadex.network`, etc.).
- Keep `||specific-adserver.example.com^$media` rules (specific ad host — safe to block).

This is conservative. False positives (dropping a rule that would have blocked a real ad) cost the user one ad per session. False negatives (keeping a rule that blocks a real video) cost the user the entire feature. The asymmetry is clear.

### 7.4 The allowlist — where to draw the line

Two-pronged:

1. **MIME / extension based** (Layer 2 above) — covers any URL whose path or content-type identifies it as media. Zero false positives by construction.
2. **Domain based** — a small bundled list of "trusted video CDNs" that the matcher always allows, regardless of URL path. Seed it with:
   - CloudFront, Akamai, Fastly, Cloudflare CDN hostnames
   - Common HLS/DASH CDN hostnames observed in yuzono/anime-extensions extractors (Streamtape, Dood, Filemoon, StreamWish, Mp4Upload, Streamlare, Okru, Kwik, etc.)
   - The page's *own* hostname (1st-party video is always allowed)
   - User-appendable (Settings → "Allowed sites")

The user can override Layer 2 with an explicit `@@||specific-host.com^$media` exception rule if they truly want to block video on a specific host.

### 7.5 Testing the contract

A `Robolectric`/`Espresso` test should encode this as a hard invariant:

```kotlin
@Test fun `adblocker never blocks video-shaped URLs`() {
    val matcher = AdMatcher.load(easyListText)
    listOf(
        "https://cdn.example.com/video.m3u8",
        "https://cdn.example.com/video.m3u8?token=abc",
        "https://cdn.example.com/segment-001.ts",
        "https://cdn.example.com/playlist.mpd",
        "https://cdn.example.com/main.mp4",
        "https://doubleclick.net/ads/preroll.mp4",         // EVEN KNOWN AD HOSTS — extractor must see it
    ).forEach { url ->
        assertThat(matcher.checkNetwork(url, "https://site.com/", RequestType.MEDIA).shouldBlock)
            .describedAs("adblocker blocked $url").isFalse()
    }
}
```

This test runs in CI on every filter-list update and every matcher change. If it ever fails, the build breaks.

---

## 8. Filter-List Update Strategy

### 8.1 Which lists, from where

| List | URL (raw GitHub) | Refresh | License |
|---|---|---|---|
| EasyList | `https://raw.githubusercontent.com/easylist/easylist/master/easylist.txt` | Daily | GPLv3 / CC-BY-SA 3.0+ |
| EasyPrivacy | `https://raw.githubusercontent.com/easylist/easylist/master/easyprivacy.txt` | Daily | GPLv3 / CC-BY-SA 3.0+ |
| AdGuard Base | `https://filters.adtidy.org/extension/chromium/filters/2.txt` | Daily | GPLv3 |
| AdGuard Annoyances | `https://filters.adtidy.org/extension/chromium/filters/14.txt` | Daily | GPLv3 |
| Peter Lowe's blocklist | `https://pgl.yoyo.org/as/serverlist.php?hostformat=nohtml` | Weekly | Informal (free for all) |
| uBO Privacy (extra) | `https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt` | Daily | GPLv3 |
| Regional EasyList (per locale) | e.g. `https://easylist-downloads.adblockplus.org/easylistgermany.txt` | Daily | GPLv3 / CC-BY-SA 3.0+ |

Total uncompressed: ~10–15 MB across all lists. Compressed with gzip on disk: ~2–3 MB.

### 8.2 Update schedule

- **On app first launch:** download all lists (use OkHttp with conditional `If-Modified-Since`/`ETag` — most will 304). Store in `filesDir/adlists/<name>.txt`. Compile the matcher eagerly on a background coroutine; show a tiny "Updating ad filters…" progress in the splash screen.
- **On app subsequent launch:** load compiled matcher from disk cache (use `Engine.serialize()` / `deserialize()` if Brave-rust, or a custom `ObjectOutputStream` for the Kotlin matcher). Schedule a `WorkManager` `PeriodicWorkRequest` for daily list refresh.
- **Daily refresh:** `WorkManager` runs in background, fetches each list, checks `Expires:` metadata from the file header, recompiles matcher, swaps in atomically (volatile reference), writes new serialized cache. No UI interruption.
- **On-demand:** Settings → "Update filter lists now" button for debugging.
- **Per-list toggle:** Settings → Ad blocking → Filter lists → checkboxes for each, so users can disable problematic ones for specific sites.

### 8.3 Failure modes

- **No network at first launch:** ship a bundled "starter pack" of EasyList + EasyPrivacy + Peter Lowe in `assets/adlists/` (~3 MB compressed in APK). This is the only copy that ships; everything else is downloaded. Update the starter pack once per app release.
- **List download 404s:** log + keep using the cached version; show a non-blocking warning after 3 consecutive failures for the same list.
- **Compiled matcher cache corruption:** fall back to re-parsing from text. If text cache is also corrupt, fall back to bundled starter pack.

---

## 9. Performance Budget

Targets (from the PLAN.md success metrics):

| Metric | Target | Achieved by |
|---|---|---|
| OkHttp ad-block check latency (p99) | < 50 µs | Brave-rust JNI; < 100 µs with Kotlin regex matcher for ≤30k rules |
| WebView `shouldInterceptRequest` overhead (p99) | < 100 µs per non-blocked request | Same matcher; trivial video-regex short-circuit avoids matcher entirely for media URLs |
| WebView page-load time impact | < 5% vs no-blocker baseline | Brave-rust; aggressive cosmetic-CSS batching (one `evaluateJavascript` per page, not per rule) |
| Matcher resident memory | < 30 MB for 100k rules | Brave-rust 0.11+ FlatBuffers serialization (Brave v1.85 overhaul); for Kotlin matcher, hard limit at 50k rules |
| Filter-list download size (daily, all lists) | < 5 MB | Conditional GETs (`If-Modified-Since`); gzip transport; most days only 1–2 lists change |

---

## 10. Licensing Summary

| Component | License | Compatibility with Apache-2.0 app | Action |
|---|---|---|---|
| Our Kotlin matcher code | Apache-2.0 (ours) | ✅ | — |
| Brave `adblock-rust` | MPL-2.0 | ✅ (file-scoped copyleft; we don't modify it) | Ship as `.so`; note in OSS licenses screen |
| Edsuns/AdblockAndroid (if referenced) | LGPL-2.1 | ⚠️ dynamic linking OK, but stale (2021) | Reference only, don't depend |
| ABP `libadblockplus-android` | LGPL-2.1+ (wraps V8, GPL-3.0+) | ❌ heavy native V8, slow maintenance | Skip |
| DuckDuckGo Android (Apache-2.0) tracker-blocking code | Apache-2.0 | ✅ | Study patterns, don't vendor (coupled to their app shell) |
| AdAway (GPLv3) | GPLv3 | ❌ if vendored into Apache-2.0 app | Use only as inspiration for the optional VPN-mode toggle; reimplement |
| dnsproxy (Apache-2.0) | Apache-2.0 | ✅ | Use only if we ever embed a real DNS proxy (probably won't) |
| RethinkDNS (LGPL-3.0) | LGPL-3.0 | ⚠️ dynamic linking OK | Reference for VPN-mode toggle |
| **Filter lists** (EasyList, EasyPrivacy, AdGuard, Fanboy, uBO) | GPLv3 / CC-BY-SA 3.0+ / informal | ✅ as **data files** (not code) — bundle unmodified, attribute, share-alike only if we modify | Bundle unmodified; OSS-licenses screen lists each with URL + license |
| uBO's own filter lists | GPLv3 | ✅ as data | Same |

**Important:** the filter-list licenses are the trickiest. EasyList's dual GPL3/CC-BY-SA license technically allows redistribution under either; we'll choose CC-BY-SA 3.0+ (which is less restrictive than GPL3 for our Apache-2.0 app — but only marginally; both are viral *if we modify the lists*). The fix is: **never modify the lists in code**. Bundle them as-is, attribute them in Settings → Open-source licenses → Filter lists (with each list's name, URL, author, and license), and let users add their own custom rules at runtime (which don't redistribute).

---

## 11. Phased Roadmap (Where Ad-Blocking Fits in PLAN.md's Phases)

| Phase | Ad-blocking deliverable |
|---|---|
| **Phase 0 (2-week spike)** | Skip ad-blocking entirely. Goal is to validate the universal extractor on 10 URLs. Adding a blocker now adds risk without validating anything. |
| **Phase 1 (MVP)** | Ship the Kotlin regex matcher (Approach A, ~600 LOC) + OkHttp interceptor (Approach D, ~80 LOC) + WebView blocker (Approach E, ~150 LOC) + cosmetic injection. Bundle EasyList + EasyPrivacy + Peter Lowe starter pack. Daily WorkManager refresh. **The extractor-before-blocker ordering test (§7.5) is a release gate.** |
| **Phase 2 (Polish)** | Swap Kotlin matcher for Brave-rust JNI behind the same interface. Add AdGuard Base + Annoyances + regional lists. Add per-site enable/disable toggle. Add filter-list management UI. |
| **Phase 3 (Power-user)** | Optional `VpnService` system-wide blocker toggle (Approach C, opt-in only). Per-app exclusion list. |
| **Phase 4 (Scale)** | Cosmetic-filter performance work — batch CSS injection, debounce DOM mutations, profile against Brave on top-100 sites. |

---

## 12. Code Sketches (One-File Reference)

```kotlin
// AdMatcher.kt — the seam between Phase 0 (Kotlin) and Phase 2+ (Brave-rust)
package reverb.adblock

import okhttp3.HttpUrl

enum class RequestType { SCRIPT, IMAGE, STYLESHEET, SUBDOCUMENT, XMLHTTPREQUEST, MEDIA, FONT, WEBSOCKET, POPUP, OTHER }

data class BlockResult(val shouldBlock: Boolean, val reason: String = "", val redirect: String? = null) {
    companion object {
        val Allowed = BlockResult(false, "allowed")
        fun allowed(reason: String) = BlockResult(false, reason)
        fun blocked(reason: String) = BlockResult(true, reason)
    }
}

data class CosmeticResources(val stylesheet: String, val specificOnly: Boolean)

interface AdMatcher : AutoCloseable {
    fun loadRules(text: String, sourceUrl: String, name: String)
    fun checkNetwork(url: String, initiator: String, type: RequestType): BlockResult
    fun cosmeticResourcesFor(pageUrl: String): CosmeticResources
    fun serialize(): ByteArray
    fun ruleCount(): Int

    companion object {
        // The regex that ALWAYS wins — see §7.3 Layer 2
        val VIDEO_ALLOWLIST: Regex =
            Regex(""".*\.(mp4|m3u8|mpd|ts|m4s|aac|webm|mkv|mov|avi|flv|wav|ogg|opus)(\?.*)?$""", RegexOption.IGNORE_CASE)

        // Trusted video CDNs (Layer 3) — seed list, user-extensible
        val TRUSTED_VIDEO_HOSTS: Set<String> = setOf(
            "cloudfront.net", "akamaihd.net", "akamaized.net", "fastly.net",
            "kxcdn.com", "mangadex.network", "jsdelivr.net", "bunny.net",
            // common anime streaming CDN hostnames (from yuzono/anime-extensions extractors)
            "streamtape.com", "doodstream.com", "dood.so", "dood.yt",
            "filemoon.sx", "moonmov.pro", "streamwish.to", "sfastwish.com",
            "mp4upload.com", "streamlare.com", "ok.ru", "kwik.cx",
            "mixdrop.co", "vidoza.net", "upstream.to", "vidoza.co",
        )

        fun isMediaUrl(url: String): Boolean = VIDEO_ALLOWLIST.containsMatchIn(url)
        fun isTrustedVideoHost(url: HttpUrl): Boolean {
            // walk up subdomain chain — same pattern as hidroh's AdBlocker.isAdHost
            var host = url.host
            while (host.isNotEmpty()) {
                if (TRUSTED_VIDEO_HOSTS.any { host == it || host.endsWith(".$it") }) return true
                val dot = host.indexOf('.')
                if (dot < 0) break
                host = host.substring(dot + 1)
            }
            return false
        }
    }
}
```

```kotlin
// AdBlockInterceptor.kt — OkHttp glue (Approach D)
package reverb.adblock.okhttp

import okhttp3.*
import reverb.adblock.*
import java.io.ByteArrayInputStream

class AdBlockInterceptor(
    private val matcher: AdMatcher,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        // LAYER 2: never block media
        if (AdMatcher.isMediaUrl(req.url.toString()) ||
            AdMatcher.isTrustedVideoHost(req.url) ||
            isMediaAccept(req.header("Accept"))) {
            return chain.proceed(req)
        }
        val initiator = req.header("Referer") ?: req.url.run { host } ?: ""
        val type = mapRequestType(req)
        val result = matcher.checkNetwork(req.url.toString(), initiator, type)
        if (result.shouldBlock) {
            return Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1)
                .code(204).message("No Content")
                .header("Content-Length", "0")
                .body(ResponseBody.create(null, ByteArray(0)))
                .build()
        }
        return chain.proceed(req)
    }

    private fun isMediaAccept(accept: String?): Boolean =
        accept != null && (accept.startsWith("video/") || accept.startsWith("audio/") ||
            accept.contains("mpegurl") || accept.contains("dash+xml"))

    private fun mapRequestType(req: Request): RequestType = when (req.header("Sec-Fetch-Dest")) {
        "script" -> RequestType.SCRIPT
        "style" -> RequestType.STYLESHEET
        "image" -> RequestType.IMAGE
        "font" -> RequestType.FONT
        "iframe", "embed", "object" -> RequestType.SUBDOCUMENT
        else -> when {
            req.header("Upgrade")?.equals("websocket", true) == true -> RequestType.WEBSOCKET
            req.method == "POST" && req.header("Content-Type")?.contains("json") == true -> RequestType.XMLHTTPREQUEST
            else -> RequestType.OTHER
        }
    }
}
```

```kotlin
// AdBlockingWebViewClient.kt — WebView glue (Approach E) — the critical ordering
package reverb.adblock.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import reverb.adblock.*
import java.io.ByteArrayInputStream

class AdBlockingWebViewClient(
    private val matcher: AdMatcher,
    private val delegate: WebViewClient,   // wraps universal extractor + Cloudflare solver
) : WebViewClient() {

    // CRITICAL: extractor runs FIRST, blocker runs SECOND.
    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        val url = req.url.toString()

        // STEP 1 — extractor's video regex (must always win)
        if (AdMatcher.isMediaUrl(url)) {
            return delegate.shouldInterceptRequest(view, req)
        }

        // STEP 2 — ad blocker
        val initiator = view.url ?: ""
        val type = mapRequestType(req)
        val result = matcher.checkNetwork(url, initiator, type)
        if (result.shouldBlock) {
            return emptyResponse()
        }

        // STEP 3 — default passthrough (extractor sees all non-video requests too,
        // in case it wants to inspect API calls)
        return delegate.shouldInterceptRequest(view, req)
    }

    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
        val url = req.url.toString()
        if (AdMatcher.isMediaUrl(url)) return delegate.shouldOverrideUrlLoading(view, req)
        val result = matcher.checkNetwork(url, view.url ?: "", RequestType.POPUP)
        if (result.shouldBlock) return true
        return delegate.shouldOverrideUrlLoading(view, req)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        delegate.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        injectCosmeticFilters(view, url)
        delegate.onPageFinished(view, url)
    }

    private fun injectCosmeticFilters(view: WebView, pageUrl: String) {
        val res = matcher.cosmeticResourcesFor(pageUrl)
        if (res.stylesheet.isBlank()) return
        val css = res.stylesheet.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        val js = """
            (function(){
                var e = document.getElementById('reverb-cosmetic');
                if (e) e.remove();
                var s = document.createElement('style');
                s.type = 'text/css';
                s.id = 'reverb-cosmetic';
                s.textContent = '$css';
                (document.head || document.documentElement).appendChild(s);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 204, "No Content",
            mapOf("Content-Length" to "0"), ByteArrayInputStream(ByteArray(0)))

    private fun mapRequestType(req: WebResourceRequest): RequestType = when (req.requestHeaders["Sec-Fetch-Dest"]) {
        "script" -> RequestType.SCRIPT
        "style" -> RequestType.STYLESHEET
        "image" -> RequestType.IMAGE
        "font" -> RequestType.FONT
        "iframe", "embed", "object" -> RequestType.SUBDOCUMENT
        else -> RequestType.OTHER
    }
}
```

```kotlin
// KotlinRegexMatcher.kt — Phase 0 implementation (no native code)
// This is a sketch; a production version is ~600 LOC including the trie index.
package reverb.adblock.kotlin

import reverb.adblock.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

class KotlinRegexMatcher : AdMatcher {
    private val networkRules = mutableListOf<NetworkRule>()
    private val exceptions = mutableListOf<NetworkRule>()
    private val cosmeticByDomain = ConcurrentHashMap<String, MutableList<String>>()
    private val tokenIndex = HashMap<String, MutableList<Int>>()  // token -> rule indices

    override fun loadRules(text: String, sourceUrl: String, name: String) {
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("[")) return@forEach
            when {
                trimmed.startsWith("@@") -> parseNetworkRule(trimmed.substring(2), isException = true)
                "##" in trimmed || "#@#" in trimmed -> parseCosmeticRule(trimmed)
                "#$#" in trimmed -> { /* ABP CSS rule — skip in v0.1 */ }
                else -> parseNetworkRule(trimmed, isException = false)
            }
        }
        buildTokenIndex()
    }

    override fun checkNetwork(url: String, initiator: String, type: RequestType): BlockResult {
        // LAYER 2 — never block media (this guard runs in BOTH interceptors, but we duplicate
        // it here as the authoritative single-source-of-truth).
        if (AdMatcher.isMediaUrl(url)) return BlockResult.allowed("media-url")
        if (type == RequestType.MEDIA) return BlockResult.allowed("media-type")

        // ... token extraction, candidate rule lookup, exception check, etc.
        // For brevity, omitted — the actual algorithm mirrors adblock-rust's
        // `Engine::check_network_request`: extract tokens from URL, look up candidate rules
        // from the token index, evaluate each candidate's regex/options against the request.
        return BlockResult.Allowed
    }

    override fun cosmeticResourcesFor(pageUrl: String): CosmeticResources {
        // look up cosmetic rules for this page's domain (and generic bucket)
        // return merged CSS string
        return CosmeticResources("", false)
    }

    override fun serialize(): ByteArray = throw NotImplementedError("v0.1 always re-parses")
    override fun ruleCount(): Int = networkRules.size + exceptions.size
    override fun close() = Unit

    private fun parseNetworkRule(pattern: String, isException: Boolean) { /* ... */ }
    private fun parseCosmeticRule(line: String) { /* ... */ }
    private fun buildTokenIndex() { /* ... */ }

    private data class NetworkRule(
        val pattern: Pattern,            // compiled regex
        val rawPattern: String,
        val isException: Boolean,
        val types: Set<RequestType>?,    // null = all
        val thirdParty: Boolean?,        // null = either
        val domainsInclude: Set<String>?,
        val domainsExclude: Set<String>?,
        val important: Boolean,
        val redirect: String?,
    )
}
```

---

## 13. Key Findings (Recap)

1. **Five approaches exist; only two are app-appropriate.** DNS/hosts-based blocking is unsuitable as a primary blocker because it can't distinguish ads from real video on the same CDN — a hard correctness blocker for Reverb. Filter-list (EasyList) matching via OkHttp interceptor + WebView `shouldInterceptRequest` is the right primary approach. Brave-rust is the right *engine* for production scale; a hand-rolled Kotlin matcher is right for Phase 0.

2. **One matcher, two glue layers.** The same `AdMatcher` instance drives both the OkHttp interceptor (for scraper/API/image traffic) and the WebView client (for fallback browser / universal extractor / Cloudflare solver). Total glue code is ~250 LOC.

3. **The ordering rule is non-negotiable.** In the WebView pipeline, the video extractor's regex check MUST run before the ad blocker. If the extractor claims a URL (matches `.*\.(mp4|m3u8|mpd|ts|m4s|aac|webm|mkv)(\?.*)?$`), the ad blocker is skipped entirely. This is enforced structurally (ordering in `shouldInterceptRequest`) AND defensively (media-allowlist guard inside `AdMatcher.checkNetwork` itself) AND at parse time (drop dangerous `$media` rules for trusted video hosts). A CI test encodes this as a release gate.

4. **EasyList explicitly targets video pre-rolls.** Its policy page lists "Pre/mid/end video ads" and "Invideo/InSlideshow Ads" as in-scope. Out of the box, EasyList *will* try to block pre-rolls on streaming sites. We accept this as a known trade-off — we'd rather over-detect a 30 s ad segment than miss the main video. Phase 2+ can filter detected ads at the playlist level using `#EXT-X-DISCONTINUITY` markers and VAST 4.0 `#EXT-X-ASSET` tags.

5. **Filter-list licenses are viral (GPLv3 or CC-BY-SA 3.0+).** Bundle them as **unmodified data files** with attribution; never modify them in code. Custom user rules are stored separately and don't trigger share-alike.

6. **Brave-rust is the right upgrade path.** ~3 MB per ABI, ~5–20× faster matching than Kotlin regex, MPL-2.0 (file-scoped copyleft, Apache-2.0-compatible). Defer to Phase 2; Phase 0 ships the Kotlin matcher to keep the spike low-risk.

7. **Don't ship ABP's `libadblockplus-android`.** It bundles V8 (~15 MB/ABI) for scriptlet execution. Overkill for our needs; the maintenance cadence is slow; the LGPL-2.1+ GPL-3.0+ license stack is awkward.

8. **DNS-level blocking (AdAway-style VPN) belongs as an opt-in power-user toggle, not the default.** It blocks the whole device's traffic, can't distinguish same-CDN ads from real video, and forces a persistent VPN notification. If we ever ship it, reuse patterns from `celzero/rethink-app` (LGPL-3.0) rather than vendoring AdAway (GPLv3).

9. **`Edsuns/AdblockAndroid`** (LGPL-2.1, 89 stars, **last push 2021-08-19**) is the closest existing solution to what we want — a Brave-C++-engine-backed `AdFilter` with a `WebViewClient.shouldInterceptRequest` integration. Worth studying as a reference for the integration shape; not a long-term dep (stale, C++ toolchain, LGPL).

10. **`MonsterTechnoGits/WebViewAdblock-Library`** is a 4-line "block ads in WebView" JitPack dep using a hardcoded host list. Reference-only.

---

## 14. Recommendation Summary (One Paragraph)

For the Reverb app, ship a single `AdMatcher` interface with two implementations (a hand-rolled Kotlin regex matcher for Phase 0, swapped for Brave's `adblock-rust` via `cargo-ndk` + JNI in Phase 2). Drive two thin glue layers from it: an `AdBlockInterceptor` on the scraper's `OkHttpClient` and an `AdBlockingWebViewClient` decorating the universal extractor's `WebViewClient`. In the WebView pipeline, **always run the video extractor's URL regex before the ad blocker** — if it matches a media URL, skip the blocker entirely and let the request through to the extractor. Defend this with a second guard inside `AdMatcher.checkNetwork` that refuses to block any URL matching `.*\.(mp4|m3u8|mpd|ts|m4s|aac|webm|mkv)(\?.*)?$` or any request whose `Accept` header advertises a media type, plus a parse-time filter that drops `$media` rules targeting trusted video CDN hostnames. Bundle EasyList + EasyPrivacy + AdGuard Base + AdGuard Annoyances + Peter Lowe + a regional EasyList as **unmodified data files**, refresh them daily via `WorkManager` with conditional GETs, and ship a starter pack in `assets/` for first-launch. Cosmetic filtering is a one-shot `evaluateJavascript` per `onPageFinished` that injects a single `<style>` element built from the matcher's `cosmeticResourcesFor(pageUrl)`. Skip DNS-level blocking for the default experience; offer it only as an opt-in power-user toggle in Phase 3+. The total implementation cost is ~600 LOC for the Kotlin matcher (Phase 0) + ~250 LOC of glue + ~150 LOC for the cosmetic-injection and filter-list-update plumbing. The Brave-rust JNI upgrade (Phase 2) is another ~200 LOC of Rust + ~100 LOC of Kotlin glue behind the same `AdMatcher` interface.
