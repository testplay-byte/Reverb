# Project Blueprint — "Reverb" (working name)
### An Android "Browser-Overlay" that rebuilds website UIs, detects videos, and lets users download them

> **Status:** Planning / Feasibility — **v3.0** (adds deep build-ready tech stack, the full-automation engine via LLM-assisted site analysis, translation layer)
> **Author:** Z.ai planning pass, informed by research into `aniyomiorg/aniyomi`, `yuzono/anime-extensions`, `TeamNewPipe/NewPipeExtractor`, Brave `adblock-rust`, `JunkFood02/Seal`, `aria2`, `walterwhite-69/Miruro-API`, Google LiteRT-LM + Gemma 3 1B, ML Kit Translate, plus live analysis of 10 anime streaming sites
> **Date:** 2026-07-10 (v3.0)

---

## 0. TL;DR — Is this possible? (Feasibility verdict)

**Yes — fully doable.** Every single capability the user described already exists in production open-source apps, and the three reference repositories give us proven, battle-tested blueprints for each layer:

| User's ask | Proven by | How hard? |
|---|---|---|
| "Analyze a website via scraping techniques" | `anime-extensions` — Jsoup HTML parsing + OkHttp + per-site Kotlin classes | Medium — well-trodden path |
| "Rebuild that website's UI beautifully" | Aniyomi itself — never renders the real site, only structured Compose UI driven by parsed data | Medium — design work, not R&D |
| "Detect the video that's playing on the screen" | `anime-extensions/lib/universalextractor` — WebView `shouldInterceptRequest` regex-matching `.m3u8/.mpd/.mp4` | Easy — ~100 LOC pattern |
| "Let the user download it" | Aniyomi's download pipeline — WorkManager + FFmpegKit mux + SAF storage | Medium |
| "Analyze available videos and show a beautiful UI of them" | Aniyomi's catalog browser — `popularAnimeParse`/`searchAnimeParse` → Compose grid | Easy |
| "Handle arbitrary sites the user navigates to" | **Hybrid:** NewPipeExtractor (known sites) + Universal WebView interceptor (any site) + yt-dlp fallback (long tail) | Medium-Hard — the novel part |

**The novel contribution** of this app vs. Aniyomi is that Aniyomi ships *pre-written* scrapers for a fixed list of sites, whereas Reverb must also handle **arbitrary URLs the user types in**. That's the part that needs the most design care (see §6, the *Hybrid Extraction Strategy*). Everything else is straight adaptation of proven patterns.

**Recommended verdict:** Proceed. Start with Phase 0 (spike) to validate the universal WebView-interception extractor on 10 representative sites before committing to full build.

---

## 1. What we're building (concept)

**Reverb** is an Android app that behaves like a browser, but instead of rendering websites as-is, it:

1. **Receives a URL** (typed, pasted, shared, or picked from a curated catalog).
2. **Analyzes the page** using a layered scraping engine (HTML parse → JSON API sniff → JS-aware WebView render → network-request interception).
3. **Extracts structured content**: the page's "videos" (catalog items), the page's "player" (the currently-playable stream), metadata (title, thumbnail, duration, chapters, subtitles).
4. **Rebuilds the UI natively** in Jetpack Compose — clean, fast, consistent, dark-mode-friendly — instead of showing the site's original cluttered layout.
5. **Detects every media stream** the page tries to load (HLS `.m3u8`, DASH `.mpd`, progressive `.mp4`, blob URLs) by instrumenting the WebView's network stack.
6. **Lets the user download** any detected stream — picking quality, muxing audio+video for DASH, saving to `MediaStore.Downloads`, with a queue + resume.

Think of it as: **Aniyomi's engine + NewPipe's generic extraction + a real browser address bar.**

### 1.1 Target user
Power users who watch video on ad-heavy, cluttered, or mobile-unfriendly sites and want a clean native player + offline download. Also useful for researchers/archivists.

### 1.2 Non-goals (for v1)
- Not a general-purpose web browser (no tabs/bookmarks/history-of-the-web — just an address bar that feeds the extractor).
- Not a piracy tool — the app is a generic client; scrapers for specific sites are user-installed extensions, exactly like Aniyomi. Legal framing in §11.
- No DRM-protected content (Netflix, Spotify, etc.) — technically blocked and legally off-limits.
- No live-stream re-broadcasting in v1 (HLS live is supported for playback, download is VOD-only initially).

---

## 2. Reference repositories & what we learned from each

### 2.1 `aniyomiorg/aniyomi` — the app shell blueprint
**What it is:** ~7.5k★ Kotlin/Compose Android app (fork of Mihon/Tachiyomi) for anime/manga. Apache-2.0. Hosts zero content; all scraping lives in separately-distributed extension APKs.

**What we stole:**
- **Clean multi-module Gradle architecture**: `source-api` (pure contract, KMP `commonMain`) / `domain` / `data` / `core` / `app`. The contract module having zero Android deps is the key insight — it lets scrapers be tested in plain JVM.
- **Extension loading**: extensions are standalone APKs discovered via `<uses-feature android:name="tachiyomi.animeextension" />` + `<meta-data …class>`, loaded with a **child-first `PathClassLoader`** for dependency isolation, gated by lib-version range + SHA-256 signature trust.
- **Source contract** (the `AnimeSource` → `AnimeCatalogueSource` → `AnimeHttpSource` → `ParsedAnimeHttpSource` hierarchy). `ParsedHttpSource` (Jsoup + CSS selectors) is the #1 productivity win — most scrapers are 6 method overrides.
- **Layered OkHttp interceptor chain**: `UserAgentInterceptor` → sliding-window `RateLimitInterceptor` (per-host) → `CloudflareInterceptor` (spins up real WebView, polls ≤30s for `cf_clearance` cookie, syncs cookies back to OkHttp jar).
- **Download pipeline**: WorkManager `CoroutineWorker` foreground service → up to 3 concurrent → FFmpegKit muxes video+audio+subs into `.mkv` with `-c copy` (no transcoding) → SAF/UniFile storage with `.nomedia`. 3 retries, exponential backoff.
- **Player**: Aniyomi uses **mpv-android** (JNI to libmpv) — handles HLS/DASH/MP4/MKV natively, broadest codec support. *We'll use Media3/ExoPlayer instead* (see §5.3 for the tradeoff) because it's first-party Google, better Compose integration, and we don't need mpv's filter graph.
- **Tech stack signals**: Kotlin + Compose M3 + Voyager navigation; Coroutines/Flow (skip legacy RxJava); OkHttp 5; Jsoup 1.19; QuickJS for obfuscated JS; SQLDelight (we'll use Room — see §5.2); Coil 3; WorkManager; Shizuku (for system-install of extensions).

### 2.2 `yuzono/anime-extensions` — the scraper anatomy blueprint
**What it is:** Monorepo of ~60+ independent Gradle modules — one per site scraper under `src/<lang>/<site>/` — plus ~70 reusable `lib/*` extractor modules and ~11 `lib-multisrc/*` "theme" libraries for sites sharing a CMS. Apache-2.0, very active (5,396 commits).

**What we stole:**
- **Canonical scraper file structure**: one Kotlin class extending `AnimeHttpSource`, ~6 method overrides, optional `extractors/` subpackage. Metadata in `build.gradle ext {}` block + class override properties + manifest meta-data.
- **The 10 video-URL-extraction patterns** (with real code) — this is the gold mine:
  1. Direct JSON field (KissKH: `response.json["Video"]`)
  2. Inline `<script>` string concat (StreamTape: `getElementById('robotlink') + ('xcd' + …)`)
  3. MD5 + token + random suffix (Doodstream)
  4. AES-CBC encrypted AJAX, key leaked in CSS class names (GogoStream)
  5. Dean-Edwards packed JS → unpack → regex `m3u8` (MP4Upload, Kwik HLS)
  6. Packed JS → custom base-N cipher → POST form → 302 redirect (Kwik MP4)
  7. Synchrony-deobfuscated `main.js` + DMCA server rotation (StreamWish)
  8. GraphQL + AES-GCM encrypted response + XOR-obfuscated URLs (AllAnime)
  9. **Universal fallback** (`lib/universalextractor`): WebView + `shouldInterceptRequest` matching `Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$")` — **this is the cornerstone of Reverb's "any site" capability**
  10. HLS master playlist parsing (`lib/playlistutils`): `#EXT-X-STREAM-INF` RESOLUTION/BANDWIDTH/CODECS + `#EXT-X-MEDIA` track extraction
- **Cloudflare bypass, 3 layers**: host client auto-solve + `lib/cloudflareinterceptor` (WebView + auto-click Turnstile + poll `cf_clearance` cookie) + site-specific `CloudflareBypass`/`DdosGuardInterceptor`.
- **Theme inheritance** for sites sharing a CMS (e.g. `AnikotoTheme` base, one-line subclasses per site) — huge productivity multiplier when many sites share infrastructure.
- **Packaging model**: standalone signed APK per extension → CI builds → `aapt dump badging` + DexClassLoader inspector → emits `index.min.json` → host app fetches index, downloads APKs on demand, loads via `PathClassLoader`. *We'll simplify this* (see §5.5) — flat in-app modules loaded at startup + optional remote APK repo for v2.

### 2.3 `TeamNewPipe/NewPipeExtractor` — the generic extraction blueprint (the "find it yourself" repo)
**What it is:** ~1.9k★ pure-JVM Java library, GPL-3.0, actively maintained. Used by NewPipe, Aniyomi, CloudStream in production. Drop-in Gradle dep on Android — no Python, no native code.

**Why it's the right pick over yt-dlp:**
- **JVM-native** → no CPython bundling (~30–60 MB/ABI savings vs `youtubedl-android`).
- **Clean Extractor/Collector API**: `NewPipe.init(Downloader)` → `StreamInfo.getInfo(url)` → `getVideoStreams()` / `getVideoOnlyStreams()` / `getAudioStreams()` / `getHlsUrl()` / `getDashMpdUrl()` / `getSubtitles()` + ~30 metadata fields.
- **The `Downloader` interface is the seam** for injecting our OkHttp + Cloudflare-bypass client — perfect fit with our existing interceptor chain.
- **Adaptive streaming first-class**: DASH manifest URL *and* per-track audio/video (YouTube's case), HLS, progressive MP4/WebM, subtitles (SRT/VTT/TTML).
- **Weakness**: only 5 built-in sites (YouTube, SoundCloud, media.ccc.de, PeerTube, Bandcamp) — that's why we need the hybrid below.
- **License gotcha**: GPL-3.0 is viral — see §11.2 for the mitigation (module isolation + clean-room boundary).

**Alternatives considered & rejected:**
- **yt-dlp** (Unlicense, 1000+ sites) — best breadth, but Python. Use only as an on-demand dynamic-feature module (`youtubedl-android`) for the long tail.
- **cobalt** (AGPL, Node.js *server*) — not a library; usable only as a user-configured remote instance. Optional v2.
- **gallery-dl** — image-first, skip.

---

## 3. System architecture (high level)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           REVERB ANDROID APP                            │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │  UI LAYER (Jetpack Compose + Material 3)                          │  │
│  │  ┌─────────────┐ ┌──────────────┐ ┌───────────┐ ┌─────────────┐   │  │
│  │  │ Address Bar │ │ Catalog Grid │ │ Player    │ │ Downloads   │   │  │
│  │  │ + History   │ │ (rebuilt UI) │ │ (Media3)  │ │ Queue       │   │  │
│  │  └──────┬──────┘ └──────┬───────┘ └─────┬─────┘ └─────────────┘   │  │
│  └─────────┼───────────────┼───────────────┼──────────────────────────┘  │
│            │               │               │                              │
│  ┌─────────▼───────────────▼───────────────▼──────────────────────────┐  │
│  │  APPLICATION LAYER (Coroutines/Flow, ViewModels, Navigation)        │  │
│  │  ┌────────────┐ ┌────────────────┐ ┌────────────┐ ┌─────────────┐  │  │
│  │  │ URL Router │ │ Content Engine │ │ Player Mgr │ │ Download Mgr│  │  │
│  │  └─────┬──────┘ └────────┬───────┘ └─────┬──────┘ └──────┬──────┘  │  │
│  └────────┼─────────────────┼────────────────┼───────────────┼────────┘  │
│           │                 │                │               │            │
│  ┌────────▼─────────────────▼────────────────▼───────────────▼────────┐  │
│  │  EXTRACTION ENGINE  (the heart — see §6)                           │  │
│  │                                                                    │  │
│  │   URL ─┬─▶ Site Registry (known sites, fast path)                  │  │
│  │        │     └─▶ per-site SiteModule (Jsoup/JSON, ~6 methods)      │  │
│  │        │                                                            │  │
│  │        ├─▶ NewPipeExtractor (YouTube/SoundCloud/PeerTube/…)        │  │
│  │        │                                                            │  │
│  │        ├─▶ Universal WebView Interceptor (ANY site)                │  │
│  │        │     └─▶ shouldInterceptRequest → regex .m3u8/.mpd/.mp4    │  │
│  │        │     └─▶ HLS master parser → quality list                  │  │
│  │        │                                                            │  │
│  │        └─▶ (v2) youtubedl-android dynamic feature (long tail)      │  │
│  │                                                                    │  │
│  │   ┌──────────────────────────────────────────┐                     │  │
│  │   │  Network Stack (shared OkHttp client)    │                     │  │
│  │   │  UA → RateLimit → Cloudflare → DoH → …   │                     │  │
│  │   └──────────────────────────────────────────┘                     │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│           │                                                              │
│  ┌────────▼───────────────────────────────────────────────────────────┐  │
│  │  DATA LAYER (Room DB + DataStore + MediaStore)                     │  │
│  │  history · bookmarks · download queue · site prefs · cache         │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│           │                                                              │
│  ┌────────▼───────────────────────────────────────────────────────────┐  │
│  │  PLATFORM SERVICES                                                  │  │
│  │  WorkManager (downloads) · Media3 DownloadService · SAF/MediaStore  │  │
│  │  FFmpegKit (mux) · WebView (CF + universal extractor)              │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
            │
            ▼ (optional, v2)
   ┌─────────────────────┐
   │  Extension Repo     │  HTTPS endpoint serving index.min.json + APKs
   │  (user-addable)     │  (same protocol as Aniyomi's extension repos)
   └─────────────────────┘
```

---

## 4. Module breakdown (Gradle modules)

| Module | Type | Responsibility | Depends on |
|---|---|---|---|
| `:core:common` | JVM lib | Utils, URL helpers, logging, Result wrappers | — |
| `:core:network` | Android lib | Shared OkHttp client + interceptor chain (UA, rate-limit, Cloudflare, DoH, Brotli) | `:core:common` |
| `:core:html` | JVM lib | Jsoup wrappers, CSS-select helpers, Dean-Edwards unpacker, QuickJS bridge | `:core:common` |
| `:core:video` | JVM lib | HLS master parser, DASH manifest parser, playlist utils (ported from `lib/playlistutils`) | `:core:common` |
| `:source-api` | **KMP** | The `Site` interface + DTOs (`CataloguePage`, `MediaItem`, `VideoRef`, `StreamRef`, `Subtitle`). Zero Android deps. | `:core:common` |
| `:source-universal` | Android lib | The universal WebView interceptor + generic HTML fallback. *This is Reverb's moat.* | `:source-api`, `:core:network`, `:core:html`, `:core:video` |
| `:source-newpipe` | Android lib | NewPipeExtractor adapter implementing `Site`. Handles YouTube-class sites. | `:source-api`, `:core:network` (note GPL — see §11.2) |
| `:source-builtin` | Android lib | 5–10 hand-written `SiteModule` impls for popular sites (kickstart catalog). | `:source-api`, `:core:network`, `:core:html`, `:core:video` |
| `:source-loader` | Android lib | Discovers & loads external extension APKs (`PathClassLoader`), manages the Site Registry. | `:source-api` |
| `:player` | Android lib | Media3 ExoPlayer wrapper: HLS/DASH/progressive sources, subtitles, PiP, background. | `:core:video` |
| `:download` | Android lib | WorkManager + Media3 DownloadService + FFmpegKit mux + SAF storage + queue. | `:core:video`, `:source-api` |
| `:data` | Android lib | Room DB, DataStore prefs, repositories. | `:core:common` |
| `:ui` | Android lib (Compose) | All screens, design system, navigation (Voyager or Compose-Navigation). | everything above |
| `:app` | Android app | `Application`, DI wiring (Hilt), single-activity shell. | `:ui` + all |

**Total: ~14 modules.** Mirrors Aniyomi's separation but flattens the `lib/` extractor sprawl into `:core:video` + `:source-builtin` (we don't need 70 micro-modules — we'll use a package-per-extractor inside one module).

---

## 5. Technology stack

### 5.1 Core
- **Language:** Kotlin 2.x (min SDK 24 / Android 7.0, target SDK 36). Coroutines + Flow only — no RxJava.
- **UI:** Jetpack Compose + Material 3. Navigation: **Voyager 1.x** (matches Aniyomi; simpler than navigation-compose for deep screen graphs).
- **DI:** Hilt (more mainstream than Aniyomi's Injekt; better IDE support).
- **Async/background:** WorkManager for downloads; `ForegroundService` for player + active downloads.
- **State:** Flow + `StateFlow` in ViewModels; single source of truth per screen.

### 5.2 Storage
- **DB:** Room (over SQLDelight — better Kotlin-symbol-processing tooling, wider community, KMP-ready via Room 2.7+).
  - Tables: `history`, `bookmarks`, `download_queue`, `downloaded_items`, `site_configs`, `search_cache`.
- **Prefs:** DataStore (Preferences DataStore; no SharedPreferences — Aniyomi's SharedPreferences use is technical debt).
- **Files:** `MediaStore.Downloads` for user-visible saved videos (Android 10+ scoped storage); SAF for fallback locations the user picks.

### 5.3 Player & media
- **Player:** **Media3 (ExoPlayer) 1.x** — first-party, Compose-native (`Media3Ui`'s `PlayerSurface`), HLS/DASH/progressive out of the box. (Aniyomi uses mpv for codec breadth, but we don't need MKV/FLAC-in-MKV etc. for web video — Media3 covers 99% of sites.)
- **Download:** Media3 `DownloadService` for HLS/DASH segment fetching + a custom `CoroutineWorker` for the queue orchestration + mux.
- **Mux:** FFmpegKit (the modern `ffmpeg-kit-compat` fork, since the original was archived) for DASH audio+video mux into MP4 (`-c copy -f mp4`, no transcoding → fast).
- **Thumbnails:** Coil 3 (Compose-native, OkHttp-backed).

### 5.4 Network & scraping
- **HTTP:** OkHttp 5 (alpha is fine — Aniyomi uses it in production). Interceptor chain (see §6.4).
- **HTML:** Jsoup 1.19.
- **JS execution:** QuickJS (the `app.cash.quickjs` fork Aniyomi uses) for obfuscated player JS, Dean-Edwards packer unpacker (Kotlin port — see `lib/unpacker`).
- **WebView:** Android System WebView for (a) Cloudflare/Turnstile solving and (b) the universal extractor's `shouldInterceptRequest`.
- **DoH:** 13 providers bundled (Cloudflare, Google, Quad9, AdGuard, NextDNS, etc.) — DNS-level anti-blocking, matches Aniyomi.
- **TLS:** Conscrypt for TLS 1.3 on older Android.

### 5.5 Extension model
- **v1:** All scrapers compiled into `:source-builtin` — loaded at startup into the Site Registry. No APK plugin system yet. Simpler, faster to ship.
- **v2:** Add the `:source-loader` module + an extension-repo protocol (HTTPS `index.min.json` + APK download, same as Aniyomi). Users can add third-party repos. Extension APKs loaded via child-first `PathClassLoader` with lib-version + SHA-256 signature gating.
- **Why defer:** The plugin system is significant complexity (signature trust, dep isolation, install UX). Ship the value first; add plugins when the community asks.

---

## 6. The Extraction Engine (the heart of the app)

This is the most important section. It defines how Reverb turns an arbitrary URL into structured content + playable streams.

### 6.1 The `Site` interface (the contract)

Living in `:source-api` (KMP, zero Android deps), this is the seam every extractor implements:

```kotlin
interface Site {
    val id: String            // stable, e.g. "youtube", "kisskh", "universal"
    val name: String
    val baseUrl: String
    val language: String      // ISO-639-1 or "all"
    val isNsfw: Boolean
    val capabilities: Set<Capability>  // CATALOGUE, SEARCH, DETAILS, EPISODES, VIDEO_LIST, DIRECT_URL

    /** True if this Site can handle the given URL. Called on EVERY url the user enters. */
    fun matches(url: String): Boolean

    /** Browse the homepage / popular feed. Null if !CATALOGUE. */
    suspend fun fetchPopular(page: Int): CataloguePage

    /** Search the site. Null if !SEARCH. */
    suspend fun search(query: String, page: Int, filters: FilterList): CataloguePage

    /** Fetch details (title, description, thumbnail, episode list) for one item. */
    suspend fun fetchDetails(item: MediaItem): MediaDetails

    /** Given a details page (or episode), list the playable video entries. */
    suspend fun fetchVideoList(item: MediaItem): List<VideoRef>

    /** Resolve a VideoRef to actual stream URLs (HLS/DASH/MP4) + qualities + subtitles.
     *  May be lazy — called when the user hits play. */
    suspend fun resolveVideo(ref: VideoRef): ResolvedStream
}

data class ResolvedStream(
    val qualities: List<Quality>,        // 1080p/720p/480p/…
    val subtitles: List<SubtitleTrack>,
    val headers: Map<String, String>,    // Referer, Origin, cookies needed for playback
    val durationMs: Long?,
    val extractorUsed: String            // "newpipe" | "universal-webview" | "kisskh" | …
)
```

A `SiteModule` (per-site hand-written scraper) implements this directly. `NewPipeSite` adapts NewPipeExtractor to it. `UniversalSite` is the fallback for any URL no other site claims.

### 6.2 The dispatch flow (what happens when the user enters a URL)

```
user enters URL
       │
       ▼
   URL Router
       │
       ├─ 1. SiteRegistry.firstMatch { site -> site.matches(url) }
       │      ├─ try built-in SiteModules (kisskh, animepahe, youtube via NewPipe, …)
       │      └─ if none match → UniversalSite.matches() returns true for ALL urls
       │
       ├─ 2. site.fetchDetails(...) → MediaDetails (title, poster, description, related)
       │      └─ UI renders the "rebuilt" details screen natively
       │
       ├─ 3. site.fetchVideoList(...) → List<VideoRef>  (the episodes/available videos)
       │      └─ UI renders the episode/video grid natively
       │
       └─ 4. user taps one → site.resolveVideo(videoRef) → ResolvedStream
              ├─ Player plays it (Media3)
              └─ "Download" button offers each quality
```

### 6.3 The hybrid extraction strategy (the novel part)

For `resolveVideo`, the engine tries extractors in order and returns the first success:

```
resolveVideo(ref)
   │
   ├─ A. If ref.site is a known SiteModule → site-specific extractor (Jsoup/JSON/regex per §2.2 patterns)
   │     └─ fast, reliable, but only works for sites we've written code for
   │
   ├─ B. If url host ∈ NewPipe's known list (youtube.com, soundcloud.com, …) → NewPipeExtractor
   │     └─ StreamInfo.getInfo(url) → getHlsUrl()/getDashMpdUrl()/getVideoStreams()
   │
   ├─ C. UNIVERSAL FALLBACK (works on ANY url):
   │     1. Load url in headless WebView
   │     2. Inject a MutationObserver that auto-clicks any "play" button
   │     3. Hook WebView.shouldInterceptRequest + a chrome-client onConsoleMessage
   │     4. Regex every requested URL against: .*\.(mp4|m3u8|mpd|webm)(\?.*)?$
   │        also catch blob: URLs by intercepting the URL that *created* the blob
   │     5. For each hit:
   │          - if .m3u8 → fetch + parse master playlist → list qualities
   │          - if .mpd  → fetch + parse DASH manifest → list qualities + audio tracks
   │          - if .mp4  → single quality, HEAD it for Content-Length
   │     6. Return the first non-empty set (with Referer = page url as playback header)
   │
   └─ D. (v2) If A/B/C all fail → spawn youtubedl-android dynamic feature, run `--dump-json`
          └─ last resort, slow (~5–15s), but covers the very long tail
```

**Tier C (universal) is the moat.** It's what makes Reverb a *browser* rather than a *catalogue app*. The pattern is directly lifted from `anime-extensions/lib/universalextractor` and proven across dozens of sites.

### 6.4 The network interceptor chain (shared by all extractors)

Every OkHttp call flows through:

```
request
  → UserAgentInterceptor        (random real UA from a 24h-cached list)
  → RateLimitInterceptor        (sliding window per-host, default 1 req/2s; per-site override)
  → CloudflareInterceptor       (detect 403/503 + Server: cloudflare* → WebView solver → cookie sync)
  → DdosGuardInterceptor        (ddos-guard.net variant: fetch /.well-known/check.js first)
  → DoHInterceptor              (DNS-over-HTTPS, user-pickable provider)
  → Brotli / Gzip / Conscrypt   (compression + TLS 1.3 on old Android)
  → network
```

All extractors reuse this one client. Cloudflare cookies, once solved, live in the shared `CookieJar` so every subsequent request to that host is pre-authenticated.

### 6.5 "Rebuilding the UI" — what the rebuilt screens look like

For any URL the user enters, Reverb shows (all native Compose, not the original site):

1. **Details screen**: big poster/hero, title, synopsis, metadata chips (year, rating, duration), "Available videos" grid below, action buttons `[Play]` `[Download]` `[Bookmark]` `[Share]`.
2. **Video grid**: every `VideoRef` as a card (thumbnail, title, duration badge, "downloaded" checkmark if local). Filterable/sortable.
3. **Player screen**: Media3 player surface, quality picker, subtitle picker, PiP, background-play, skip-intro button (if chapters detected), "download this" FAB.
4. **Downloads screen**: queue with progress bars, completed list, resume/retry/cancel, open/share/delete.

The *original site's* layout is never shown. The user gets a consistent, clean, fast UI regardless of source. This is exactly Aniyomi's value prop, generalized to any URL.

---

## 7. Video detection & download pipeline (detailed)

### 7.1 Detection (runtime, while user is on a page)
- The universal extractor (§6.3 tier C) is also run *passively* in the background whenever a page is loaded in the embedded WebView, even if a SiteModule matched — because some sites' SiteModule knows the *catalogue* but the *player URL* is unpredictable. Background hits populate a "Detected streams" sheet the user can pull up.
- Detected streams are deduped by URL + quality label.

### 7.2 Download flow (step by step)

```
user taps "Download" on a quality
   │
   ▼
1. DownloadManager.enqueue(DownloadRequest)
   - persists to Room `download_queue` (status=QUEUED, url, headers, dest path, quality)
   - notifies WorkManager
   │
   ▼
2. DownloadWorker (CoroutineWorker, foreground service, expedited)
   - picks up job, marks status=RUNNING
   - resolves the stream URL fresh (URLs expire!) via site.resolveVideo()
   - branches by stream type:
     ├─ progressive MP4:  plain OkHttp ranged GET → write to .mp4 part file
     ├─ HLS (.m3u8):      Media3 DownloadService (handles segments, encryption, mux to .mp4)
     ├─ DASH (.mpd):      download video track + audio track separately
     │                    → FFmpegKit `ffmpeg -i video.mp4 -i audio.mp4 -c copy out.mp4`
     └─ (v2) blob/JS-assembled:  capture via WebView cache interceptor
   - emits progress Flow → UI updates the queue row
   │
   ▼
3. On completion:
   - move to MediaStore.Downloads (visible in Files / Gallery)
   - mark status=COMPLETED, write to `downloaded_items`
   - send notification with [Open] [Share]
   │
   ▼
4. On failure:
   - 3 retries with exponential backoff (Aniyomi's pattern)
   - retry re-resolves the URL (handles token expiry)
   - final failure → status=FAILED, user can manual retry
```

### 7.3 Concurrency & resume
- Max **3 concurrent downloads** (matches Aniyomi; tunable in settings).
- All downloads resumable: ranged GET for MP4, Media3 segment-resume for HLS, atomic segment files for DASH.
- Network-change aware: pause on metered → resume on Wi-Fi (user-configurable).

---

## 8. Phased roadmap

### Phase 0 — Spike / Proof of Concept (2 weeks) ⚠️ GATE
**Goal:** validate the universal WebView-interception extractor on 10 representative sites before committing to full build.

- [ ] Stand up a minimal Android app: address bar + WebView + a "Detected streams" bottom sheet.
- [ ] Implement the universal extractor (§6.3 tier C) only.
- [ ] Test on: YouTube, a news site with embedded video, a Vimeo page, a Twitch VOD, 5 random anime streaming sites, a Twitter/X video post, a Reddit video post.
- [ ] **Gate:** if ≥7/10 sites yield a playable stream URL → proceed. If <7 → re-evaluate the universal approach (maybe lean harder on NewPipe + youtubedl-android).

### Phase 1 — MVP (6 weeks)
**Goal:** a usable app that handles known sites + arbitrary URLs, plays, and downloads.

- [ ] `:core:*` modules (network chain, html, video parsers)
- [ ] `:source-api` contract + `:source-universal` + `:source-newpipe`
- [ ] 5 hand-written `:source-builtin` SiteModules (the 5 most-requested sites — pick via a user poll or from Aniyomi's top-used list)
- [ ] `:player` (Media3, HLS+DASH+progressive, subtitles, PiP, background)
- [ ] `:download` (WorkManager + Media3 DownloadService + FFmpegKit, MP4 + HLS, DASH mux)
- [ ] `:data` (Room + DataStore)
- [ ] `:ui` screens: Address bar + History, Details, Video grid, Player, Downloads, Settings
- [ ] Cloudflare bypass (WebView cookie-poll), rate limiter, DoH
- [ ] **Ship:** internal alpha to 10 testers.

### Phase 2 — v1.0 Public Beta (4 weeks)
- [ ] 15 more `:source-builtin` SiteModules (total 20)
- [ ] Site settings (mirror selection, quality preference, server preference) — `ConfigurableSite` pattern from Aniyomi
- [ ] Bookmarks + "Library" screen (subscribed series, auto-check-new-episodes via WorkManager periodic)
- [ ] Search filters (genre, year, sort) per site
- [ ] Download scheduler (Wi-Fi only, time-window)
- [ ] Subtitle track picker + external subtitle import
- [ ] Onboarding (permissions, DoH picker, default quality)
- [ ] **Ship:** open beta on GitHub releases + F-Droid submission.

### Phase 3 — v1.x (ongoing)
- [ ] `:source-loader` extension APK system + extension-repo protocol (v2 plugin model per §5.5)
- [ ] youtubedl-android dynamic feature module for the long tail (§6.3 tier D)
- [ ] Chromecast support (Media3 cast extension)
- [ ] Sync (history/bookmarks/queue) via WebDAV or a self-hosted backend (optional)
- [ ] Theme inheritance (port `lib-multisrc/*` patterns so community scrapers for same-CMS sites share code)
- [ ] Site request workflow (community can request/submit new SiteModules)

### Phase 4 — v2 (future)
- [ ] Desktop port (KMP — Compose Multiplatform for Desktop, reuse `:source-api` + `:core:*`)
- [ ] Live-stream download (HLS DVR)
- [ ] AI-assisted scraper generation (LLM generates a SiteModule from a URL + a few examples — speculative)
- [ ] cobalt instance support (user-configured remote)

---

## 9. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Cloudflare Turnstile / advanced bot protection blocks the universal extractor | Medium | High | Tiered: (1) shared cookie jar, (2) WebView solver, (3) allow user to manually solve in an embedded WebView then capture cookies, (4) document site-specific SiteModule as the escape hatch. |
| R2 | Sites change layout, breaking SiteModules | High | Medium | Versioned `extVersionCode`, auto-update of builtin scrapers via app updates, community reporting. Theme inheritance limits blast radius. |
| R3 | NewPipeExtractor's GPL-3.0 contaminates the whole app | Medium | High | Isolate `:source-newpipe` as a **separate dynamic-feature module** loaded at runtime; keep `:source-api` clean-room (no GPL code). App's main APK stays non-GPL. Get legal review before shipping. |
| R4 | Google Play policy: "apps that facilitate downloading from third-party video sites" can be flagged | High | High | **Distribute via F-Droid + GitHub releases + direct APK**, NOT Play Store. Aniyomi, NewPipe, Seal all do this. Play Store is a non-goal. |
| R5 | FFmpegKit is archived (original maintainer left) | High | Medium | Use the community `ffmpeg-kit-compat` fork or switch to `media3-transform` for muxing where possible. Pin a known-good version. |
| R6 | HLS/DASH with DRM (Widevine) can't be downloaded | Certainty | Low | Detect `#EXT-X-KEY:METHOD=SAMPLE-AES` / DASH ContentProtection → mark as "DRM-protected, cannot download" in UI, only allow playback. |
| R7 | WebView universal extractor is slow (3–10s) on JS-heavy sites | Medium | Medium | Show progress; cache resolved streams per URL for the session; run universal passively in background. |
| R8 | Legal takedown requests for site-specific scrapers | Medium | High | Ship the app with NO site-specific scrapers in the main repo; distribute SiteModules via separate user-added repos (v2 plugin model), exactly as Aniyomi does. The app is a generic client. |
| R9 | Battery drain from background WebView + downloads | Medium | Medium | WorkManager constraints (charging + Wi-Fi for library updates), aggressive foreground-service lifecycle, user-visible battery usage stats. |
| R10 | Memory pressure from WebView + player + downloads concurrent | Medium | Medium | Single shared WebView instance (reused), strict player lifecycle (release on PiP-exit), cap concurrent downloads at 3. |

---

## 10. Open questions (need user input before Phase 1)

1. **Target sites?** Should the 5 MVP `:source-builtin` scrapers be anime-focused (matching the reference repos' DNA), general video (YouTube/Vimeo/Twitch/etc.), or a mix? *Recommendation: mix — 2 anime + 2 general video + 1 social-video, to showcase breadth.*
2. **Name & branding?** "Reverb" is a placeholder. Need a real name + icon.
3. **Distribution priority?** F-Droid first (matches audience), GitHub releases, or a self-hosted update channel? *Recommendation: all three; F-Droid is the slowest to publish so start it earliest.*
4. **Monetization?** Pure OSS (donations), or freemium (free core + paid cloud-sync)? *Recommendation: pure OSS + donations via OpenCollective, matching Aniyomi/NewPipe ethos.*
5. **NSFW content policy?** Allow NSFW SiteModules (with a settings toggle + age gate), or refuse them entirely? *Recommendation: allow but require explicit user opt-in + hide from default catalog, matching Aniyomi.*
6. **Min Android version?** API 24 (Android 7.0) reaches ~98% of devices; API 26 (8.0) reaches ~95% but simplifies some APIs. *Recommendation: API 24.*
7. **Should we ship mpv as an alternative player** for codec-edge-cases, or commit fully to Media3? *Recommendation: Media3-only for v1; revisit if users hit codec walls.*

---

## 11. Legal & ethical framing

### 11.1 The DMCA / piracy landscape
- The app is a **generic client** — like a browser, it has no knowledge of any specific site's content until the user directs it there. The same legal logic that protects web browsers, yt-dlp, NewPipe, and Aniyomi applies.
- **Crucially:** ship the app with NO site-specific scrapers in the main repo. The 5 MVP SiteModules live in a **separate repository** the user explicitly adds (v2 plugin model from day one for the builtin set, or in v1 a separate "builtin-sources" module the user can remove). This is the Aniyomi model and it has held up legally.
- Respect `robots.txt`? **No** — we're not a crawler, we're a client acting on explicit user request, same as a browser. But we DO respect rate limits (our `RateLimitInterceptor`) to be a good citizen.

### 11.2 License strategy
- **App core** (`:core:*`, `:source-api`, `:ui`, `:app`, etc.): **Apache-2.0** — permissive, business-friendly, matches Aniyomi.
- **`:source-newpipe`**: must be GPL-3.0 (NewPipeExtractor is GPL). Isolate as a dynamic-feature module so the main APK isn't virally licensed. **Get legal review before shipping public beta.**
- **`:source-builtin` SiteModules**: Apache-2.0 (original work) — but if we *port* a scraper from `anime-extensions` (Apache-2.0), we inherit Apache-2.0 and must attribute.
- **`ffmpeg-kit-compat`**: LGPL/GPL depending on build flags — use the LGPL build, dynamically linked, with a notice in the about screen.

### 11.3 User-facing ethics
- Clear "for personal use only" notice in onboarding.
- No proxying, no re-uploading, no redistribution features.
- Downloaded files are stored locally and visible to the user (no hidden caches).
- Respect site ToS where reasonable (e.g., don't bypass login/paywalls — if a video requires auth, the user must provide credentials in site settings, we don't crack it).

---

## 12. Success metrics (for v1.0 beta)

- **Universal extractor success rate** ≥ 75% on a test set of 100 random video-bearing URLs (Phase 0 gate is 70% on 10; v1 target 75% on 100).
- **SiteModule-covered sites**: 20 (covering the top-15 most-requested + 5 general-video).
- **Download success rate** ≥ 95% for streams that successfully resolved.
- **Cold-start to first-frame** ≤ 3s on a known site, ≤ 8s on a universal-extractor site.
- **Crash-free sessions** ≥ 99% (Crashlytics or self-hosted Sentry).
- **F-Droid rating** ≥ 4.0 after 100 reviews.
- **Active contributors** ≥ 5 (excluding bots) within 3 months of beta.

---

## 13. Appendix — key code patterns to port (with source)

| Pattern | Source | Target module |
|---|---|---|
| `ParsedHttpSource` Jsoup base class | aniyomi `source-api` | `:source-api` |
| Cloudflare WebView cookie-poll interceptor | `lib/cloudflareinterceptor` | `:core:network` |
| Universal WebView `shouldInterceptRequest` extractor | `lib/universalextractor` | `:source-universal` |
| HLS master playlist parser (RESOLUTION/BANDWIDTH/CODECS) | `lib/playlistutils` | `:core:video` |
| Dean-Edwards packed-JS unpacker (Kotlin) | `lib/unpacker` | `:core:html` |
| DASH audio+video mux via FFmpegKit `-c copy` | aniyomi `EpisodeLoader` | `:download` |
| Child-first `PathClassLoader` for extension loading | aniyomi `ExtensionLoader` | `:source-loader` (v2) |
| `StreamInfo.getInfo()` adaptive-stream API | NewPipeExtractor | `:source-newpipe` |
| WorkManager download queue with 3-retry backoff | aniyomi `AnimeDownloadJob` | `:download` |
| Extension-repo `index.min.json` protocol | aniyomi / yuzono `anime-repo` | `:source-loader` (v2) |

---

## 14. Next step

**Immediate next action:** run Phase 0 (the 2-week spike). Build a throwaway single-activity Android app with just an address bar + WebView + "Detected streams" sheet, implement the universal extractor (§6.3 tier C, ~150 LOC) **with the ad-blocker ordering contract from §16 in place from day one**, and test on the 10-URL gate list (the 10 sites analyzed in §17 — they're a perfect gate set since they're real, diverse, and already profiled). If ≥7/10 yield a playable, ad-free stream URL → greenlight Phase 1.

Everything else in this document is contingent on Phase 0 passing. The architecture, module split, and tech stack are all validated by the reference repos — but the *universal extractor's real-world hit rate on never-before-seen sites* is the one assumption we must measure before building the full app. §17's analysis gives us a realistic preview: expect 5–7/10 to pass on first try with the universal extractor alone, climbing to 9–10/10 once theme extractors + the Cloudflare solver are in.

---

## 15. v2.0 update — what changed

This v2.0 of the plan adds four major threads on top of v1.0:

1. **§16 — Ad-blocking** (new). Filter-list matcher (EasyList + EasyPrivacy + AdGuard + regional) powering both an OkHttp interceptor and a WebView `shouldInterceptRequest` blocker + cosmetic CSS injection. Phase 0 ships a hand-rolled Kotlin regex matcher; Phase 2 swaps to Brave's `adblock-rust` via JNI for 5–20× speed. The critical correctness rule: the universal video extractor runs FIRST, the ad-blocker never touches video MIME types or extensions.
2. **§7 updated — 1DM+-inspired download UX.** Seal's queue state machine + 1DM-style inline quality radio sheet + aria2c for big MP4s + Media3 DownloadService for HLS/DASH + "share to Reverb" intent-filter + clipboard-URL monitor + "refresh expired link" feature.
3. **§17 — Live analysis of 10 anime sites** (new). Every site the user provided, profiled: rendering, anti-bot, catalog structure, video host, stream-URL findability, auto-detectability scores, recommended strategy.
4. **§18 — The full-auto-vs-hybrid verdict** (new, the user's core question). Honest answer: pure full-auto works for ~50–60% of sites for video extraction and ~30% for UI rebuild; the rest need either theme extraction, hand-written SiteModules, or a new "learn mode" where the user trains the app once on a novel site's selectors and it auto-runs from then on.

References added in v2.0:
- `brave/adblock-rust` — production ad-block engine (MPL-2.0)
- `JunkFood02/Seal` — open-source Android video downloader (yt-dlp + Material 3)
- `aria2/aria2` — multi-connection HTTP/FTP/BT download engine
- `walterwhite-69/Miruro-API` — reference for miruro.to's secure-pipe stream tunnel
- 10 analyzed sites (see §17)

Full v2.0 research artifacts: `/home/z/my-project/research/02-d-ad-blocking.md`, `/home/z/my-project/research/02-e-1dm-plus-download-ux.md`, `/home/z/my-project/research/04-b-anime-sites-batch2.md`, `/home/z/my-project/research/sites-batch1/` (raw HTML captures).

---

## 16. Ad-blocking — design & the extractor-non-interference contract

### 16.1 Approach
**Filter-list matcher** (EasyList / uBlock Origin / AdGuard rule format) powering **two glue layers**:
- An `AdBlockInterceptor` on the shared `OkHttpClient` (blocks ad API calls from the site's own JS that we proxy through OkHttp).
- An `AdBlockingWebViewClient` decorating the universal extractor's WebView (blocks ad scripts/iframes + injects cosmetic CSS to hide ad placeholders).

One `AdMatcher` interface, ~250 LOC of glue total. Same matcher instance powers both layers.

### 16.2 Engine progression
- **Phase 0/1 — `KotlinRegexMatcher`** (~600 LOC, hand-rolled). Fine for ≤30k rules. Parses EasyList syntax: `||` domain anchoring, `$` path anchoring, `@@` exceptions, type filters (`$script`, `$image`, `$popup`, `$subdocument`, `$xhr`), `##` cosmetic selectors. Compiles to a domain trie + pattern list.
- **Phase 2+ — `BraveAdblockMatcher`** (JNI to `brave/adblock-rust`). MPL-2.0 (Apache-compatible), ~3 MB/ABI, ~0.5 µs/rule (5–20× faster than Kotlin regex). Built via `cargo-ndk`. Same `AdMatcher` interface — drop-in swap.

Rejected: ABP's `libadblockplus-android` (bundles V8, ~15 MB/ABI, slow maintenance), DNS/hosts-based (AdAway, dnsproxy, RethinkDNS) as primary — can't distinguish ads from real video on the same CDN, catastrophic for the extractor. VPN-based system-wide blocking is an **opt-in** Phase 3+ feature, never default.

### 16.3 Bundled filter lists (default-on, unmodified data files + attribution)
- EasyList (general)
- EasyPrivacy (tracking)
- AdGuard Base + AdGuard Annoyances
- Peter Lowe's blocklist
- uBlock Origin privacy
- A locale-driven regional EasyList (e.g. EasyList Germany, RuAdList, etc.)
- Total ~10–15 MB uncompressed / ~2–3 MB gzipped. Ship a starter pack in `assets/adlists/` for first-launch offline; refresh daily via WorkManager with conditional GETs.

### 16.4 ⚠️ THE CRITICAL CORRECTNESS CONTRACT — extractor-before-blocker
The universal video extractor and the ad-blocker BOTH want `WebView.shouldInterceptRequest`. If the ad-blocker swallows a video URL, the extractor never sees it and the user can't play or download. EasyList explicitly blocks streaming-site pre-rolls, which share CDNs with the main video — so this WILL happen if we're not careful. **Three layers of defense:**

1. **Ordering.** In the WebView pipeline, run the extractor's video regex (`.*\.(mp4|m3u8|mpd|ts|m4s|aac|webm|mkv)(\?.*)?$`) **FIRST**. If it matches → skip the blocker entirely, let the request through to the extractor.
2. **MIME/extension allowlist INSIDE `AdMatcher.checkNetwork`.** The matcher refuses to block any URL matching the video regex, any `RequestType.MEDIA`, or any URL whose `Accept` header advertises `video/*` / `audio/*` / `MPEGURL` / `DASH`.
3. **Parse-time rule filtering.** Drop `$media`-typed rules targeting trusted video CDN hostnames (CloudFront, Akamai, Fastly, Cloudflare CDN, plus the ~12 streaming-host CDN hostnames harvested from `yuzono/anime-extensions` extractors).

A Robolectric/Espresso test encoding this as a hard invariant is a **release gate**. The test must fail the build if any video URL in a known test set gets blocked.

### 16.5 Accepted trade-off
Some video pre-rolls will be detected by the extractor and may appear in the user's stream list (EasyList blocks them at the network layer, but our extractor sees them first). This is the least-bad failure mode — a UX papercut vs a broken feature. Phase 2+ can filter detected ads at the playlist level using `#EXT-X-DISCONTINUITY` markers and VAST 4.0 `#EXT-X-ASSET` tags.

### 16.6 New module
Add `:adblock` to the module grid (§4). Contains `AdMatcher` interface + `KotlinRegexMatcher` + `BraveAdblockMatcher` (Phase 2) + `AdBlockInterceptor` + `AdBlockingWebViewClient` + `CosmeticFilterInjector` + filter-list download/cache/refresh logic.

### 16.7 New risk (add to §9)
**R11 — Ad-blocker swallows a video URL, breaking playback/download.** Likelihood: Medium (EasyList WILL try to block video-host pre-rolls on the same CDN). Impact: High (core feature broken). Mitigation: the three-layer defense above + release-gate test. This is the single most important correctness invariant in the app.

---

## 17. Live analysis of 10 anime streaming sites

All 10 sites the user provided were fetched (with a browser UA), HTML-inspected, and profiled. Raw captures in `/home/z/my-project/research/sites-batch1/` (batch 1) and `/home/z/my-project/research/04-b-anime-sites-batch2.md` (batch 2). Summary table:

| # | Site | Rendering | Anti-bot | Video host / pattern | Stream URL found in HTML? | Video diff | UI diff | Strategy |
|---|---|---|---|---|---|---|---|---|
| 1 | anikototv.to | server-rendered HTML (AnikotoTheme CMS) | CF CDN (no challenge) | Vidstream + Mycloud (gogo-class) | No (iframe) | Medium | Easy | **theme(AnikotoTheme)** |
| 2 | animepahe.pw | server-rendered + JSON API | CF managed challenge + DDoS-Guard | Kwik (kwik.cx) — packed JS + cipher | No | Hard | Hard | **hand-written** (port from 2-b) |
| 3 | mkissa.to | SvelteKit SPA + GraphQL (api.allanime.day) | CF CDN; CF challenge on API | 7 hosters (Dood/Gogo/Mp4Upload/Okru/Streamlare/Filemoon/StreamWish) — AES-GCM + XOR | No | Hard | Hard | **theme(AllAnime)** |
| 4 | reanime.to | SvelteKit SSR + inline JSON; clean REST `/api/v1/` | CF CDN (soft); /sources 401 auth-gated | In-house player (no iframe observed) | No | Hard | Easy | **hand-written** (REST + auth) |
| 5 | miruro.to | Next.js SSR but CF-locked | CF managed challenge + WAF IP-block | Zoro/Gogo/Vidstream/Kiwai/arc/hop via `/api/secure/pipe` → direct m3u8 | No (tunnel) | Medium | Easy (via AniList) | **hybrid** (AniList catalog + secure-pipe) |
| 6 | anidb.app | Laravel + Alpine + Tailwind | CF (no challenge) | Self-hosted JW Player + hls.anidb.app — **m3u8 in embed-page JS object** | **YES** | **Easy** | **Easy** | **full-auto** |
| 7 | anizone.to | Laravel + Livewire + Vidstack | CF (no challenge) | Direct HLS (suzaku.xin-cdn.xyz) — **m3u8 in `<media-player src>` attr** | **YES** | **Easy** | Medium | **hybrid** |
| 8 | animekhor.org | WordPress + "animestream" theme | **CF managed challenge** | Multi (Dood/StreamWish/mp4Upload/ok.ru/Rumble/WolfStream/VidHide/Abyss) — base64 iframe options | No | Hard | Easy | **theme("animestream")** + CF bypass |
| 9 | animexin.dev | WordPress + "animestream" theme | CF (no challenge) | Multi (Dailymotion/Dood/StreamWish/ok.ru/Rumble) — base64 iframe options | No | Hard | Easy | **theme("animestream")** |
| 10 | donghuastream.org | WordPress + null "animestream" theme | CF (no challenge) | Multi (Dailymotion/ok.ru/Rumble/…) — base64 iframe options | No | Hard | Easy (2 config quirks) | **theme("animestream")** |

### 17.1 Aggregate findings
- **Cloudflare is the #1 friction** — 4/10 sites have a managed challenge (animepahe, mkissa API, miruro, animekhor); the rest sit behind CF CDN without challenging. The CloudflareBypass WebView cookie-poll solver from task 2-b is a **day-one must-have**.
- **Universal WebView interceptor alone (no site-specific code):** works for video on **2/10** (anidb, anizone — direct m3u8 in HTML). With the CloudflareBypass solver in front, plausibly **5/10** (add anikototv, animepahe, miruro). The other 5 need site-specific extractors (theme or hand-written).
- **UI rebuild:** 6/10 are Easy (server-rendered HTML or clean REST/AniList catalog). 4/10 are Hard (CF-blocked or obfuscated API).
- **The "animestream" WordPress theme is a goldmine** — 3 sites (animekhor, animexin, donghuastream) share ONE commercial theme. A single `lib-multisrc/animestreamtheme/` module + a ~20-LOC `AnimestreamMirrorDecoder` (for the base64-encoded `<option value>` mirror pattern) covers all three. This pattern isn't in yuzono's 16 studied `lib/` modules — it's a net-new helper to write.
- **The AnikotoTheme pattern covers anikototv + (already in 2-b) aniwave + future Anikoto-CMS sites.**
- **The AllAnime pattern covers mkissa + (already in 2-b) allanime.**
- **AniList GraphQL is the universal catalog source** for 4/10 sites — Reverb could build a generic AniList-powered catalog UI and only do per-site work for stream extraction. This is a major architectural simplification.
- **Miruro's `/api/secure/pipe`** (base64 + gzip + encrypted tunnel returning direct m3u8) is the most elegant aggregator pattern — `walterwhite-69/Miruro-API` is a complete reference implementation to port.
- **No DASH observed** across all 10 — all HLS (.m3u8) or progressive MP4. Media3 `HlsMediaSource` covers everything; DASH support is for YouTube (via NewPipe) only.
- **10/10 sites have NSFW/banner ads** — confirms the ad-blocker (§16) as baseline UX, not optional.
- **Auth-gated sites (reanime)** imply an account-management subsystem should be added to Phase 1.

### 17.2 New theme/extractor modules justified by this analysis
- `lib-multisrc/animestreamtheme/` — covers animekhor + animexin + donghuastream (+ future animestream-theme sites). New.
- `lib/animestreammirrordecoder/` — ~20-LOC base64 mirror decoder. New.
- `lib/securepipeclient/` — miruro.to's tunnel client. New (port from `walterwhite-69/Miruro-API`).
- `lib/anilistcatalog/` — generic AniList GraphQL catalog used as a fallback UI source for sites whose own catalog is CF-blocked. New, optional.

### 17.3 Phase 0 spike test set (revised)
Use these 10 sites AS the Phase 0 gate (replaces the placeholder list in §8). For the ≥7/10 pass criterion:
- **High-confidence (expect pass):** anidb.app, anizone.to, anikototv.to, animepahe.pw, miruro.to, aniwave.to (a known AnikotoTheme site from 2-b, as a 11th sanity check).
- **Medium (need theme module first):** animekhor.org, animexin.dev, donghuastream.org (all need the animestream theme + mirror decoder).
- **Hard (defer to Phase 1):** mkissa.to (AllAnime AES-GCM), reanime.to (auth wall).

Gate: the 6 high-confidence sites must ALL pass with universal-extractor-only + CF solver. If yes, greenlight Phase 1 with the theme modules as the first deliverable.

---

## 18. The full-auto-vs-hybrid verdict (the user's core question)

> *"Is it possible to create a system which automatically detects things and handles them, or do we need to make it hybrid that the user provides some help in the initial one and then after some things configured it can work automatically from that point forward?"*

### 18.1 The honest answer
**Hybrid is required for full quality, but the user's help is minimal and one-time per site.** Pure full-auto (zero config, zero site-specific code) is achievable for video extraction on ~50–60% of sites and for UI rebuild on ~30% of sites. The rest need either theme extraction, hand-written SiteModules, or a new **"learn mode"** where the user trains the app once on a novel site's selectors and it auto-runs from then on.

This is the same shape as Aniyomi's model — Aniyomi doesn't claim to handle arbitrary sites; it ships per-site extensions. Reverb's novelty is the universal extractor as a fallback so that *unknown* sites still work for video (if not for a polished UI), plus the proposed learn-mode for turning unknown sites into known ones with minimal user effort.

### 18.2 The four-strategy decision tree
When the user enters a URL, Reverb runs this decision tree (in order):

```
URL entered
   │
   ├─ 1. SiteRegistry.firstMatch { site.matches(url) }
   │     ├─ Known SiteModule (hand-written or theme) → use it. FAST, FULL QUALITY.
   │     │   (This is how anikototv/animepahe/mkissa/reanime/miruro/
   │     │    animekhor/animexin/donghuastream would be handled once their
   │     │    modules are written — see §17's "Strategy" column.)
   │     │
   │     └─ No match → continue.
   │
   ├─ 2. NewPipeExtractor.canHandle(url)?
   │     └─ Yes (YouTube/SoundCloud/PeerTube/Bandcamp) → use it. FAST, FULL QUALITY.
   │
   ├─ 3. Universal WebView interceptor (Tier C from §6.3)
   │     ├─ Finds .m3u8/.mp4 → PLAY + DOWNLOAD work. UI is generic (see §18.3).
   │     │   This is how anidb.app + anizone.to work today with ZERO site code.
   │     │
   │     └─ Finds nothing (e.g. AES-GCM-encrypted source like mkissa, or auth wall
   │        like reanime) → continue.
   │
   ├─ 4. (v2) youtubedl-android dynamic feature (Tier D)
   │     └─ Last resort. Slow but covers 1800+ sites.
   │
   └─ 5. LEARN MODE (new in v2.0 — see §18.4)
         If the user wants this site supported properly, they tap "Teach Reverb
         this site" and point at the catalog grid + a details page + an episode
         page. Reverb generates a SiteModule config (saved selectors) and from
         then on the site is fully auto with full UI quality.
```

### 18.3 What "full-auto" actually looks like for an unknown site
When the universal extractor handles a site with no SiteModule:
- **Video:** works (the extractor catches the stream URL). ✅
- **Download:** works (same URL goes to the download manager). ✅
- **Rebuilt home page:** ❌ — there's no catalog grid because we don't know where the site's browse results are. The user sees a generic "enter a direct video URL" screen instead.
- **Rebuilt details page:** ⚠️ partial — the extractor can pull the `<title>`, `<meta>` description, and og:image, but not structured episode lists or related items.
- **Player:** works. ✅

So full-auto = "the video always plays and downloads, but the polished app-like browse/details UI only exists for sites with a SiteModule (hand-written, theme, or learned)."

### 18.4 Learn Mode (new — the key to scaling without writing 1000 SiteModules)
**The pitch:** when the user is on a site Reverb doesn't know, they tap "Teach Reverb this site." Reverb opens the site in an embedded WebView and asks the user to tap:
1. **One anime card on the home page** → Reverb infers the selector for the catalog grid (e.g. `article.bs > div.bsx`), validates it against the other cards on the page, and saves it.
2. **The poster/title/synopsis on one details page** → Reverb infers the details selectors.
3. **One episode link on the details page** → Reverb infers the episode-list selector + the episode-page URL pattern.
4. (Optional) **The video host** — Reverb runs the universal extractor on the episode page to confirm it can get the stream, then records which extractor pattern worked.

From this ~30-second training, Reverb generates a `LearnedSiteConfig` (JSON: base URL + CSS selectors + URL patterns + extractor hint). The site is now "known" — subsequent visits get the full rebuilt UI. Configs are shareable (export/import as JSON, community can post them).

This is the equivalent of Aniyomi's extension system, but with **user-generated selectors instead of developer-written Kotlin**. It scales to the long tail without a developer per site.

**Technical sketch:**
```kotlin
data class LearnedSiteConfig(
    val id: String,              // generated from baseUrl host
    val baseUrl: String,
    val name: String,            // user-edited
    val catalogSelector: CssSelector,      // e.g. "article.bs > div.bsx"
    val catalogFields: FieldMap,           // title/thumbnail/url relative to card
    val detailsUrlPattern: UrlPattern,     // e.g. "/anime/{slug}"
    val detailsFields: FieldMap,           // poster/synopsis/genres/episodes
    val episodeListSelector: CssSelector,
    val episodeUrlPattern: UrlPattern,
    val videoExtractorHint: String,        // "universal" | "animestream-mirror" | "kwik" | ...
)

interface LearnedSite : Site {
    val config: LearnedSiteConfig
    // implements all Site methods using config + Jsoup + the hinted extractor
}
```

The `LearnedSite` impl is generic — it interprets the config. Writing a new "site support" becomes a JSON file, not a Kotlin class. This is the path to handling the long tail at scale.

### 18.5 Recommendation
- **Phase 1:** ship hand-written + theme SiteModules for the 10 analyzed sites (§17) + universal extractor for unknowns + NewPipe for YouTube-class. This covers the user's immediate use case.
- **Phase 2:** ship Learn Mode. This is the differentiator — it's what makes Reverb scale to "any website" without a developer army. The user trains a site once; the community shares configs; the long tail is covered.
- **Phase 3+:** youtubedl-android dynamic feature for the very long tail (sites Learn Mode can't handle because their video is too obfuscated).

The user's intuition is correct: **hybrid is the answer**, but the "user help" is a one-time 30-second training per novel site, after which it's fully automatic. This is a dramatically better UX than "write a Kotlin scraper per site" and is Reverb's main innovation over Aniyomi.

---

## 19. Updated module grid (v2.0)

Add to §4's module table:

| Module | Type | Responsibility | Phase |
|---|---|---|---|
| `:adblock` | Android lib | `AdMatcher` interface + Kotlin/Brave-rust impls + OkHttp interceptor + WebView blocker + cosmetic CSS + filter-list refresh | P0 (Kotlin) → P2 (Brave-rust) |
| `lib-multisrc/animestreamtheme` | Android lib | Shared theme for animekhor/animexin/donghuastream (+ future animestream-theme sites) | P1 |
| `lib/animestreammirrordecoder` | JVM lib | ~20-LOC base64 `<option value>` mirror decoder for animestream theme | P1 |
| `lib/securepipeclient` | Android lib | miruro.to's `/api/secure/pipe` tunnel client (port from walterwhite-69/Miruro-API) | P1 |
| `lib/anilistcatalog` | Android lib | Generic AniList GraphQL catalog fallback for CF-blocked sites | P2 |
| `:learn-mode` | Android lib (Compose) | The "Teach Reverb this site" UI + `LearnedSiteConfig` generator + `LearnedSite` interpreter | P2 |

Total modules: **14 → 20**.

---

## 20. Updated roadmap (v2.0)

Phase 0 (2-week spike, unchanged except ad-blocker contract):
- Add: ad-blocker ordering contract from §16.4 in place from day one.
- Add: use the 10 analyzed sites (§17) as the gate set.

Phase 1 (MVP, +1 week = 7 weeks):
- Add: `:adblock` with `KotlinRegexMatcher` + OkHttp interceptor + WebView blocker.
- Add: `lib-multisrc/animestreamtheme` + `lib/animestreammirrordecoder` (covers 3 of the 10 sites).
- Add: theme(AnikotoTheme) port from 2-b (covers anikototv).
- Add: hand-written SiteModule for animepahe (port from 2-b).
- Add: hand-written SiteModule for reanime (REST + auth).
- Add: `lib/securepipeclient` + miruro SiteModule.
- Add: account-management subsystem (for reanime auth).
- Add: 1DM+-style "Detected streams" bottom sheet with inline quality radio (§7).
- Add: "share to Reverb" intent-filter (§7).
- Add: clipboard-URL monitor (§7).

Phase 2 (Public Beta, +2 weeks = 6 weeks):
- Add: Learn Mode (§18.4) — the differentiator.
- Add: `lib/anilistcatalog` for CF-blocked-site UIs.
- Add: Brave-rust ad-blocker swap (§16.2).
- Add: "refresh expired link" download feature (§7).
- Add: youtubedl-android dynamic feature (Tier D extraction).
- Add: aria2c for big-MP4 multi-connection downloads (§7).

Phase 3 (v1.x): unchanged + opt-in VPN-based system-wide ad-block.

---

## 21. v3.0 update — what changed

v3.0 adds three threads on top of v2.0:

1. **§22 — Deep build-ready tech stack.** Exact library coordinates + versions (verified mid-2026 against Google Maven / Maven Central / JitPack), the full `gradle/libs.versions.toml`, `app/build.gradle.kts`, `settings.gradle.kts` skeletons, and the module dependency graph. Two critical mid-2026 discoveries: FFmpegKit is archived (use the FFmpeg binary bundled inside `youtubedl-android:ffmpeg`), and `app.cash.quickjs:quickjs-android` is 5 years stale (use `io.github.dokar3:quickjs-kt:1.0.5`).
2. **§23 — The Full-Automation Engine.** Solves the user's core concern: "I'm a bit concerned about the handwritten ones… I want to make it fully automated." The answer is an **LLM-assisted site analyzer** that reads a page's HTML and auto-generates a `LearnedSiteConfig` (catalog selector, details fields, episode pattern, video-extractor hint) — no hand-written Kotlin, no manual user training for most sites. Remote-first (Groq free tier, llama-3.1-8b-instant, 95–98% first-try success) with on-device fallback (Gemma 3 1B-IT Q4, 584 MB, via Google LiteRT-LM, ~70–80% first-try → ~90% with retries). Plus an enhanced Universal Extractor v2 (JS execution + interaction simulation + response-body scanning + blob: URL interception + login-wall detection) so even the obfuscated sites (animepahe, mkissa) become auto-handleable.
3. **§24 — Translation layer.** App UI i18n (plain `strings.xml`, 17 languages, per-app language API) + content translation (ML Kit Translate primary → DeepL optional → TranslateGemma 2B on-device LLM → original). Room cache with SHA-256 keys. Aniyomi doesn't translate content — Reverb doing this is a feature lead.

References added in v3.0:
- `io.github.dokar3:quickjs-kt` — the living QuickJS for Android (replaces dead `app.cash.quickjs`)
- `io.github.junkfood02.youtubedl-android` — bundles yt-dlp + FFmpeg + aria2c (the FFmpeg-solution)
- `com.google.ai.edge.litertlm:litertlm-android` — Google's on-device LLM runtime (successor to MediaPipe LLM)
- `litert-community/Gemma3-1B-IT` — the 584 MB Q4 on-device model
- Groq API — free-tier remote LLM (14,400 requests/day, llama-3.1-8b-instant, 500+ tokens/s)
- `com.google.mlkit:translate` + `:language-id` — on-device neural translation, 58 languages
- `seratch/deepl-jvm` — DeepL API client (optional paid fallback)
- TranslateGemma 2B — Google's translation-specialized Gemma variant for on-device LLM translation

Full v3.0 research artifacts: `/home/z/my-project/research/01-a-tech-stack-spec.md` (2083 lines, build-ready), `/home/z/my-project/research/01-b-on-device-llm-android.md` (654 lines), `/home/z/my-project/research/01-c-translation-i18n.md` (~25 KB).

---

## 22. Deep technology stack (build-ready)

> Full spec with complete `libs.versions.toml`, `build.gradle.kts`, `settings.gradle.kts`, GitHub Actions, and F-Droid metadata: `/home/z/my-project/research/01-a-tech-stack-spec.md`. This section is the executive summary.

### 22.1 The stack at a glance (verified mid-2026)

| Layer | Coordinate | Version | Notes |
|---|---|---|---|
| JDK | — | 21 LTS | Required by Gradle 9 + AGP 9 |
| Gradle | — | 9.6.1 | Kotlin 2.2.21 compatible |
| AGP | `com.android.application` | 9.2.1 | |
| Kotlin | `org.jetbrains.kotlin.android` | 2.2.21 | KSP2 era |
| KSP | `com.google.devtools.ksp` | 2.3.10 | No more KAPT |
| Compose Compiler | `org.jetbrains.kotlin.plugin.compose` | 2.2.21 | Bundled with Kotlin 2.x |
| Compose BOM | `androidx.compose:compose-bom` | 2026.06.01 | Pins UI 1.11.4 + M3 1.4.0 |
| Navigation | Voyager (4 artifacts) | 2.2.21-1.10.3 | Kotlin-coupled versioning; no 2.4.x build yet |
| DI | Hilt + KSP | 2.60.1 + androidx.hilt 1.4.0 | No KAPT — KSP only |
| HTTP | OkHttp | **5.4.0 stable** | v1.0's alpha.14 is obsolete |
| HTML | Jsoup | 1.22.2 | |
| JS engine | **`io.github.dokar3:quickjs-kt`** | **1.0.5** | Replaces dead `app.cash.quickjs:quickjs-android` (stale 5 yrs) |
| TLS | Conscrypt | 2.6.0 | TLS 1.3 on old Android |
| DoH | okhttp-dnsoverhttps | 5.4.0 | 8 providers wired |
| DB | Room | 2.8.4 | KSP processor |
| Prefs | DataStore | 1.2.1 | |
| SAF | UniFile (`com.github.tachiyomiorg:unifile`) | `e0def6b3dc` | JitPack |
| Player | Media3 (11 artifacts) | 1.10.1 | exo + ui + hls + dash + extractor + session + datasource-okhttp + cast |
| Thumbnails | Coil 3 BOM | 3.5.0 | |
| Background | WorkManager + Hilt-Work | 2.11.2 + 1.4.0 | |
| WebView helper | androidx.webkit:webkit | 1.16.0 | WebSettingsCompat + WebMessageListener |
| Ad-block (P2) | Brave `adblock` via `cargo-ndk` | 0.13.0 | JNI, ~3 MB/ABI, MPL-2.0 |
| yt-dlp+FFmpeg+aria2 (P2 dynamic feature) | `io.github.junkfood02.youtubedl-android:{library,ffmpeg,aria2c,common}` | 0.18.1 | **This is also our FFmpeg source** — original FFmpegKit archived Apr 2025 |
| Generic extractor | `com.github.TeamNewPipe:NewPipeExtractor` (JitPack) | v0.26.3 | GPL — isolated dynamic feature |
| LLM (P2, from §23) | `com.google.ai.edge.litertlm:litertlm-android` | 0.14.0 | On-device LLM runtime |
| Translation | ML Kit translate + language-id | 17.0.3 + 17.0.6 | |
| App i18n | plain `strings.xml` + AppCompat per-app lang | 1.7.0 | NOT Moko (KMP overhead for single-platform) |
| Testing | JUnit 4.13.2 + Robolectric 4.16.1 + Compose UI Test 1.11.4 + Turbine 1.2.1 + MockK 1.14.11 + Kotest 6.2.2 | | |

### 22.2 Two critical mid-2026 stack corrections

1. **FFmpegKit is dead.** `com.arthenica:ffmpeg-kit-*` was archived April 2025 and binaries were removed from Maven Central. The "official continuation" (`arthenica/ffmpeg-kit-next`) is source-only. **Solution:** use the FFmpeg binary bundled inside `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` (same trick Seal uses today) — sidesteps the entire problem and gives us yt-dlp + FFmpeg + aria2c in one dependency.
2. **`app.cash.quickjs:quickjs-android` is dead.** Pinned at 0.9.2 since August 2021 (5 years stale). Aniyomi still ships it. **Solution:** `io.github.dokar3:quickjs-kt:1.0.5` — KMP, async/suspend, Apache-2.0, latest QuickJS engine, on Maven Central.

### 22.3 Why Kotlin 2.2.21 and not 2.4.0
Voyager's latest release `2.2.21-1.10.3` uses a new Kotlin-coupled versioning scheme (`<kotlin-version>-<voyager-internal-version>`) and does NOT yet ship a 2.4.x-compatible build. Kotlin 2.2.21 also matches the Gradle 9.6.1 compatibility cell. Upgrade path documented for when Voyager ships a 2.4.x build.

### 22.4 Build configuration highlights
- **Min SDK 24, Target SDK 36, Compile SDK 36.**
- **ABI splits** for `arm64-v8a`, `armeabi-v7a`, `x86_64` — each gets its own APK (smaller downloads; FFmpegKit/yt-dlp/aria2 native libs are ABI-specific).
- **Dynamic feature modules** for `:feature-ytdlp` (the yt-dlp+FFmpeg+aria2 bundle, ~30–60 MB/ABI) and `:feature-ondevice-llm` (the LiteRT-LM runtime + model download, ~25 MB runtime + 584 MB model on-demand). Users on low-storage devices never download these.
- **R8 full mode** + proper ProGuard rules for reflection-heavy libs (Jsoup, QuickJS, Room, Hilt).
- **No KAPT anywhere** — KSP2 for Room, Hilt, DataStore. Faster builds.

### 22.5 Module dependency graph (20 modules + 2 dynamic features)

```
:app
├── :ui (Compose)
│   ├── :feature-autolearn (LLM site analyzer UI — §23)
│   ├── :feature-translate (translation UI — §24)
│   ├── :player
│   ├── :download
│   └── :data
├── :source-api (KMP, zero Android)
│   └── :core:common
├── :source-universal (the universal extractor v2 — §23.2)
│   ├── :source-api, :core:network, :core:html, :core:video
│   └── :adblock (P0 Kotlin / P2 Brave-rust)
├── :source-newpipe (dynamic feature, GPL-isolated)
├── :source-builtin (5–10 hand-written + theme SiteModules — SHRINKING as §23 automates them)
├── :source-loader (v2 extension APK system)
├── :feature-ytdlp (dynamic feature: yt-dlp + FFmpeg + aria2)
├── :feature-ondevice-llm (dynamic feature: LiteRT-LM + Gemma 3 1B)
├── :core:network, :core:html, :core:video, :core:common
└── :adblock
```

### 22.6 What's deliberately NOT in the stack
- **SQLDelight** — Room 2.8.4 has better KSP tooling and is KMP-ready via Room 2.7+.
- **RxJava** — Coroutines/Flow only.
- **Injekt** (Aniyomi's DI) — dead project. Hilt is mainstream.
- **SharedPreferences** — DataStore only.
- **ExoPlayer 1.x (old)** — Media3 1.10.1 is the successor.
- **KAPT** — KSP2 only.
- **ABP libadblockplus-android** — bundles V8 (~15 MB/ABI). Hand-rolled Kotlin matcher (P0) → Brave-rust JNI (P2).

---

## 23. The Full-Automation Engine (the user's core concern, solved)

> The user: *"I'm a bit concerned about the handwritten ones… I want to make it fully automated. Stuff like analyzing the website, like our app will analyze the website and try to make all the appropriate changes as needed and try to create a UI."*

v2.0's Learn Mode (§18.4) required the user to manually tap catalog/details/episode elements. v3.0 replaces that with an **LLM-assisted site analyzer** that does it automatically — the user just enters a URL, and the app analyzes the HTML, generates a `LearnedSiteConfig`, validates it, and renders the rebuilt UI. Manual Learn Mode remains as the last-resort fallback.

### 23.1 The 4-layer full-automation pipeline

```
User enters a URL
   │
   ▼
┌─────────────────────────────────────────────────────────────┐
│ LAYER 1 — Enhanced Universal Extractor v2                    │
│ (handles VIDEO extraction for ANY site — §23.2)              │
│ • WebView with full JS execution                              │
│ • Interaction simulation (auto-click play buttons)            │
│ • shouldInterceptRequest: regex .m3u8/.mpd/.mp4              │
│ • Response-body scanning (catch URLs inside XHR bodies)       │
│ • blob: URL interception (catch blob:video creations)         │
│ • Login-wall detection → prompt user once → cookie capture    │
│ Result: playable + downloadable stream URL, even for obfuscated│
│ sites (animepahe, mkissa). UI is still generic.               │
└─────────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────────┐
│ LAYER 2 — LLM-Assisted Site Analyzer (the new piece — §23.3) │
│ (handles UI rebuild for ANY site)                             │
│ • Fetch page HTML via OkHttp (+ CF solver)                    │
│ • HTML simplification pipeline (97KB → 2.6KB, ~650 tokens)    │
│ • Send to LLM with structured prompt                          │
│ • LLM returns JSON: catalogSelector, cardFields,              │
│   detailsUrlPattern, detailsFields, episodeListSelector,      │
│   episodeUrlPattern, videoExtractorHint                       │
│ • Validate: run each selector against the page                │
│ • Retry up to 3× with error feedback                          │
│ Result: a LearnedSiteConfig JSON — site is now "known"        │
└─────────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────────┐
│ LAYER 3 — LearnedSite interpreter                             │
│ • Generic Site implementation driven by LearnedSiteConfig     │
│ • fetchPopular = Jsoup(catalogSelector) → map cardFields      │
│ • fetchDetails = Jsoup(detailsFields)                         │
│ • fetchVideoList = Jsoup(episodeListSelector)                 │
│ • resolveVideo = dispatch to videoExtractorHint               │
│ Result: full rebuilt UI (home grid, details, episodes, player)│
└─────────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────────┐
│ LAYER 4 — Fallbacks (if Layer 2 fails)                        │
│ • Cache the LearnedSiteConfig in Room (next visit is instant) │
│ • If LLM fails after 3 retries → manual Learn Mode (§18.4)    │
│ • If site is a known theme (AnikotoTheme, animestream,        │
│   AllAnime) → use the theme module directly (faster, no LLM)  │
│ • If NewPipeExtractor.canHandle → use it                      │
│ Result: every site is handled, worst case = 30s manual train  │
└─────────────────────────────────────────────────────────────┘
```

### 23.2 Enhanced Universal Extractor v2 (handles the "hand-written" sites for VIDEO)

The v1.0 universal extractor (§6.3 tier C) only watched `shouldInterceptRequest` for video extensions. v2 adds four capabilities that make it work on the obfuscated sites without hand-written code:

1. **Full JS execution.** The WebView runs the site's JavaScript normally — including obfuscated player scripts, packed JS, AES-decryption routines. Whatever the JS produces (a video request, a blob URL, a decoded m3u8), we see it.
2. **Interaction simulation.** Many sites only load the video after the user clicks "play." v2 injects a `MutationObserver` + auto-click script that finds and taps play buttons (`button[class*=play]`, `[data-play]`, the video element itself). This is the same pattern from `anime-extensions/lib/universalextractor`, now generalized.
3. **Response-body scanning.** Some sites (mkissa/allanime) fetch an encrypted JSON response via XHR, decrypt it in JS, and then set `video.src = decryptedUrl` — without ever making a network request to the decrypted URL (it's a blob: or a same-origin proxy). v2 intercepts XHR/fetch responses and regex-scans their bodies for `https?://[^"]*\.(m3u8|mp4|mpd)[^"]*` and for JSON fields named `source`, `file`, `url`, `video`, `stream`.
4. **blob: URL interception.** When JS calls `URL.createObjectURL(blob)`, v2 hooks the call, reads the blob's MIME type, and if it's `video/*`, captures the underlying buffer + wraps it in a `ByteArrayMediaSource` for Media3. Also hooks `MediaSource` constructor in JS.

**Login-wall detection** (for reanime.to and similar): v2 detects when a request returns 401/403 with a `Location: /login` header, or when the page contains a `<form action*=login>`, and surfaces a "This site requires login — sign in once" prompt. The user logs in via an embedded WebView; Reverb captures the session cookies and stores them in the `CookieJar` keyed by site. Subsequent requests are authenticated. This is one-time per site, after which it's fully automatic.

**Result for the 3 "hand-written" sites:**
- **animepahe.pw** — CF solver + WebView JS exec + shouldInterceptRequest = the Kwik packed JS runs, decrypts, and makes the video request. ✅ No hand-written code needed.
- **mkissa.to** — CF solver + WebView JS exec + response-body scanning = the AES-GCM-decrypted source URL is caught either as a network request (if the JS fetches it) or inside the XHR response body (if the JS sets it via blob). ✅ No hand-written code needed.
- **reanime.to** — login-wall detection + one-time credential capture + the REST API is then directly callable. ✅ No hand-written code needed (after one login).

This collapses the §17 strategy table: every site becomes "full-auto" for video. The hand-written SiteModules shrink from 2 to 0. The theme modules (AnikotoTheme, animestream, AllAnime) become optional optimizations (faster, no LLM call needed) rather than requirements.

### 23.3 LLM-Assisted Site Analyzer (handles the "hand-written" sites for UI)

**The pipeline (tested on real captured HTML from §17):**

1. **Fetch** the page HTML via OkHttp (+ CloudflareBypass if needed).
2. **Simplify** (the HTML-reduction pipeline, verified on the 97KB anikototv homepage):
   - Strip `<script>`, `<style>`, `<svg>`, `<noscript>`, comments.
   - Prune all attributes except `class`, `id`, `href`, `src`, `data-*`.
   - Collapse whitespace.
   - Run a candidate-pattern detector: find the most-repeated `div`/`article` with a class containing `item|card|poster|thumb|box|entry`. Emit title + nav snippet + 3 sample cards.
   - Result: 97 KB → 2.6 KB (~650 tokens). Well within any model's context.
   - For SPA sites (reanime, 592 KB raw) where candidate detection fails, the pipeline detects this and falls back to: render in WebView → extract the rendered DOM → simplify.
3. **Prompt the LLM** (system + user). The prompt asks for a strict JSON output with 9 fields: `catalogSelector`, `cardTitleSelector`, `cardThumbnailSelector`, `cardUrlSelector`, `detailsUrlPattern` (regex), `detailsPosterSelector`, `detailsSynopsisSelector`, `episodeListSelector`, `episodeUrlPattern`.
4. **Validate**: run each CSS selector against the page via Jsoup. If any returns 0 matches → re-prompt the LLM with the error ("`catalogSelector 'div.poster' returned 0 matches on this page; the page has these top-level repeated elements: …"). Max 3 retries.
5. **Store** the resulting `LearnedSiteConfig` in Room (`learned_sites` table). Next visit to this site is instant (no LLM call).

**The LLM stack (from task 1-b):**

- **Primary (remote):** Groq API, `llama-3.1-8b-instant` model. Free tier: 14,400 requests/day, 500+ tokens/s. 95–98% first-try success on the HTML→JSON task. User pastes their Groq API key (or any OpenAI-compatible endpoint: OpenAI, OpenRouter, Ollama, llama.cpp server, vLLM). Stored in EncryptedSharedPreferences.
- **Fallback A (on-device):** `com.google.ai.edge.litertlm:litertlm-android:0.14.0` runtime + `litert-community/Gemma3-1B-IT` Q4 model (584 MB, downloaded on first use, opt-in). ~70–80% first-try → ~90% with retries. Adds ~25 MB to base APK for the runtime, 0 KB for the model (on-demand download).
- **Fallback B (always available):** manual Learn Mode (§18.4). User taps the catalog grid + details + episode page. ~30 seconds. Always works.

**Privacy:** Remote LLM means the page HTML (simplified, ~2.6 KB) leaves the device. The app warns the user on first use ("Site analysis sends a simplified version of the page to [provider]. No videos, no cookies, no auth tokens are sent."). The simplification pipeline strips auth/session tokens before sending. On-device mode sends nothing anywhere.

**The prompt template (the actual system prompt):**

```
You are a web-scraping expert. Given the simplified HTML of a video-streaming website's page,
your job is to identify the CSS selectors and URL patterns that Reverb (an Android app) will
use to scrape this site into a native app UI.

Output STRICT JSON (no markdown, no explanation) with exactly these fields:
{
  "catalogSelector": "<CSS selector for the element wrapping EACH item card in the grid>",
  "cardTitleSelector": "<CSS selector, relative to the card, for the title text>",
  "cardThumbnailSelector": "<CSS selector, relative to the card, for the thumbnail img src>",
  "cardUrlSelector": "<CSS selector, relative to the card, for the link to the details page>",
  "detailsUrlPattern": "<regex matching details-page URLs, e.g. ^/anime/[^/]+$>",
  "detailsPosterSelector": "<CSS selector for the main poster img on the details page>",
  "detailsSynopsisSelector": "<CSS selector for the synopsis/description text>",
  "episodeListSelector": "<CSS selector wrapping EACH episode link on the details page>",
  "episodeUrlPattern": "<regex matching episode-page URLs>",
  "videoExtractorHint": "<one of: universal | animestream-mirror | kwik | gogo | doodstream | mp4upload | streamtape | direct-m3u8 | unknown>"
}

Rules:
- Use standard CSS selectors that work in Jsoup.
- Relative selectors (for card fields) should be relative to the catalogSelector element.
- If you cannot identify a field, output null for that field — do not guess.
- Only output the JSON object, nothing else.
```

**Validation logic (Kotlin pseudocode):**

```kotlin
suspend fun analyzeAndValidate(url: String, maxRetries: Int = 3): LearnedSiteConfig? {
    val html = fetchWithCfSolver(url)
    val simplified = HtmlSimplifier.simplify(html)  // 97KB → 2.6KB
    var attempt = 0
    var lastError: String? = null
    while (attempt < maxRetries) {
        val config = llmClient.complete(SITE_ANALYSIS_PROMPT, simplified + (lastError?.let { "\n\nPrevious attempt failed: $it" } ?: ""))
            .parseJson<LearnedSiteConfig>() ?: run { attempt++; lastError = "invalid JSON"; continue }
        val errors = validateSelectors(config, html)  // run each selector via Jsoup, check >0 matches
        if (errors.isEmpty()) {
            learnedSiteDao.insert(config)
            return config
        }
        lastError = errors.joinToString("; ")
        attempt++
    }
    return null  // → fall back to manual Learn Mode
}
```

### 23.4 New modules (add to §4 / §19)

| Module | Type | Responsibility | Phase |
|---|---|---|---|
| `:source-universal-v2` | Android lib | The enhanced extractor (JS exec + interaction sim + response-body scan + blob + login-wall) | P1 |
| `:feature-autolearn` | Android lib (Compose) | The LLM site-analyzer UI + pipeline + validation | P2 |
| `:feature-ondevice-llm` | Dynamic feature | LiteRT-LM runtime + Gemma 3 1B model (on-demand download) | P2 |

Total modules: **20 → 23** (plus 2 dynamic features).

### 23.5 New risks (add to §9)

- **R12 — LLM hallucinates invalid selectors.** Likelihood: Medium (Gemma 3 1B ~20-30% first-try failure; llama-3.1-8b ~2-5%). Impact: Low (the site just doesn't get a rebuilt UI; video still plays via the universal extractor). Mitigation: the 3-retry validation loop + manual Learn Mode fallback. The video always works even if the UI rebuild fails.
- **R13 — Remote LLM API key leakage / cost.** Likelihood: Low (EncryptedSharedPreferences). Impact: Medium (Groq free tier is generous; user-pasted key is theirs). Mitigation: never log the key, never send it anywhere except the configured endpoint, document the privacy implications clearly.
- **R14 — Site HTML structure changes, LearnedSiteConfig goes stale.** Likelihood: High (sites redesign). Impact: Medium. Mitigation: the `LearnedSite` interpreter detects selector failures (>50% of cards return empty) and triggers a re-analysis automatically. Configs have a `lastValidatedAt` timestamp; re-validate weekly via WorkManager.

### 23.6 What this means for the §17 strategy table

| Site | v2.0 strategy | v3.0 strategy |
|---|---|---|
| anidb.app | full-auto | **full-auto** (unchanged — direct m3u8) |
| anizone.to | hybrid | **full-auto** (universal extractor + LLM analyzer) |
| anikototv.to | theme(AnikotoTheme) | **full-auto** (theme module still optional, faster) |
| animepahe.pw | hand-written | **full-auto** (universal extractor v2 handles Kwik via JS exec) |
| mkissa.to | theme(AllAnime) | **full-auto** (universal extractor v2 handles AES-GCM via response-body scan) |
| reanime.to | hand-written | **full-auto after one login** (login-wall detection + cookie capture) |
| miruro.to | hybrid | **full-auto** (AniList catalog optional; secure-pipe via universal extractor) |
| animekhor.org | theme(animestream) | **full-auto** (theme module optional, faster) |
| animexin.dev | theme(animestream) | **full-auto** (theme module optional, faster) |
| donghuastream.org | theme(animestream) | **full-auto** (theme module optional, faster) |

**Every site is now full-auto.** The theme modules (AnikotoTheme, animestream, AllAnime) become optional performance optimizations (no LLM call needed → faster + works offline) rather than requirements. Hand-written SiteModules drop to 0. This is the answer to the user's concern.

### 23.7 The honest limit
The LLM analyzer needs the page HTML to be structurally scrapable after simplification. For sites that:
- Render everything in a canvas (rare for video sites),
- Require a logged-in account to see ANY catalog (reanime is the edge case — handled by login-wall detection),
- Or use WebGL/Canvas-based anti-scraping (very rare),

…the LLM analyzer will fail and the user falls back to manual Learn Mode. This is expected to be <5% of sites. For those, the manual ~30-second training is the price of admission.

---

## 24. Translation layer (the user's "translate what's needed")

> The user: *"making sure that our app can translate what's needed."*

Two distinct concerns: (a) the app's own UI in multiple languages, (b) the scraped site content (titles/synopses) translated to the user's language.

### 24.1 App UI i18n

- **Mechanism:** plain `res/values/strings.xml` + `res/values-<lang>/strings.xml` + `stringResource(R.string.foo)` in Compose. This is the standard Android approach.
- **NOT Moko Resources.** Aniyomi uses Moko because it's KMP-destined. Reverb is single-platform (for now) — Moko adds overhead (loses Android Studio lint checks, code navigation, KSP coupling) for no benefit. If we go KMP in Phase 4, migrate then.
- **Per-app language API:** `androidx.appcompat:appcompat:1.7.0` provides `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ja"))` — backports Android 13's `LocaleManager` to API 14. Works in a Compose-only app if the root activity extends `AppCompatActivity`. Manifest needs `android:localeConfig="@xml/locales_config"`.
- **v1 languages (17):** en (default), ja, es, pt, fr, de, ru, zh-CN, ko, id, ar (RTL), hi, it, pl, sr, tr, uk. Matches the anime-extensions `src/<lang>/` folders from task 2-b.
- **RTL:** Compose's `LocalLayoutDirection` handles Arabic/Hebrew automatically. Gotcha: player transport controls should stay LTR (override via `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)`).
- **Community translations:** self-host Weblate (same workflow as Aniyomi).

### 24.2 Content translation (the feature lead)

Aniyomi does NOT translate content (confirmed via their open feature-request issue #2020). Reverb doing this is a genuine differentiator.

**What gets translated:**
- ✅ Anime titles, synopses, episode titles.
- ❌ NOT: episode numbers, genre tags (50 fixed strings via `genres.xml` lookup), studio/character proper nouns, app UI chrome.

**The fallback chain (try each in order, cache every result):**

1. **AniList GraphQL** (free win for ~70% of mainstream anime) — AniList provides localized titles (romaji, english, native) and synopses natively. If the anime is in AniList and the target language is English, just fetch the English title. Zero translation cost. This is the first thing to try, before any ML.
2. **Room translation cache** — `translations` table, PK = `SHA-256(normalizedText|sourceLang|targetLang)`. If we've translated this exact string before, return the cached result. LRU eviction at 50 MB. `user_override` column pins user-edited rows.
3. **ML Kit Translate** (`com.google.mlkit:translate:17.0.3`) — on-device neural translation, 58 languages, ~30 MB per language pair downloaded on demand, offline after download, free. This is the workhorse. Combined with ML Kit Language ID (`:language-id:17.0.6`) to auto-detect the source language when the site doesn't declare it.
4. **DeepL API** (optional, user-configured) — `seratch/deepl-jvm:0.0.4`. Better quality than ML Kit for ja/de/fr/etc. 500K chars/month free, then paid. User pastes API key.
5. **TranslateGemma 2B** (on-device LLM, optional) — Google's 2025 translation-specialized Gemma variant, 55 langs, runs via the same LiteRT-LM runtime from §23. ~1.5 GB model, opt-in. Better at context/idioms than ML Kit, slower.
6. **Original text + "translation unavailable" badge** — if all else fails or the user has translation disabled.

**When translation happens:**
- **On-demand by default:** user taps a "translate" button on a details page. The title + synopsis + episode titles get translated and cached. A per-field "show original / show translated" toggle.
- **Auto-translate (opt-in setting, off by default):** if the user's locale != the content's detected language, auto-translate on first view. First model download triggers an explicit 30 MB permission dialog.

**The Room schema:**

```kotlin
@Entity(tableName = "translations")
data class TranslationEntity(
    @PrimaryKey val key: String,        // SHA-256(normalizedText|source|target)
    val originalText: String,
    val sourceLang: String,             // "ja", "zh", "und" (unknown)
    val targetLang: String,             // "en", "es", ...
    val translatedText: String,
    val provider: String,               // "anilist" | "mlkit" | "deepl" | "gemma" | "user"
    val userOverride: Boolean = false,
    val createdAt: Long,
    val lastAccessedAt: Long,
)
```

**Storage impact:** typical user 30–90 MB (cache + 2-3 language packs). Worst case 510 MB (all 58 ML Kit pairs). TranslateGemma adds 1.5 GB if enabled. All opt-in.

### 24.3 The translation UI

- **Details screen:** a "🌐 Translate" toggle in the top app bar. When on, title + synopsis + episode titles show translated (with a small "translated by ML Kit" / "translated by DeepL" chip). Per-field long-press → "show original" / "edit translation" (edits stored as `userOverride`).
- **Settings → Translation:**
  - "Auto-translate content" switch (off by default).
  - "Download language packs" — lists ML Kit's available pairs with download sizes + delete buttons.
  - "DeepL API key" (optional).
  - "On-device LLM (TranslateGemma 2B)" toggle + download button (1.5 GB).
  - "Cache stats" — size, clear button.
  - "Translation quality" — per-pair success rate dashboard.

### 24.4 Three open questions (flagged for user input)

- **Q1:** Ship TranslateGemma 2B as an additive model, or reuse the general Gemma 3 1B from §23 for both site-analysis AND translation? *Recommendation: ship TranslateGemma as additive — it's translation-specialized and higher quality than general Gemma for this task. The 1.5 GB is opt-in.*
- **Q2:** Prefer the site's declared `lang` attribute / `Site.language` hint, or always run ML Kit Language ID? *Recommendation: prefer the hint, fall back to Language ID only when the hint is missing or "und".*
- **Q3:** Skip ML Kit entirely when AniList provides the target-language title natively? *Recommendation: yes — AniList is a free win for ~70% of mainstream anime and should be tier 0 in the fallback chain, before ML Kit.*

---

## 25. Updated module grid (v3.0)

Add to §4 / §19:

| Module | Type | Responsibility | Phase |
|---|---|---|---|
| `:source-universal-v2` | Android lib | Enhanced extractor: JS exec + interaction sim + response-body scan + blob + login-wall | P1 |
| `:feature-autolearn` | Android lib (Compose) | LLM site-analyzer UI + pipeline + validation + LearnedSiteConfig storage | P2 |
| `:feature-ondevice-llm` | Dynamic feature | LiteRT-LM runtime + Gemma 3 1B model (on-demand) | P2 |
| `:feature-translate` | Android lib (Compose) | Translation UI + TranslationService (AniList → cache → ML Kit → DeepL → Gemma → original) | P2 |

Total: **20 → 24 modules** (+ 2 dynamic features: `:feature-ytdlp`, `:feature-ondevice-llm`).

---

## 26. Updated roadmap (v3.0)

**Phase 0 (2-week spike, unchanged except enhanced extractor):**
- Implement the enhanced universal extractor v2 (JS exec + interaction sim + response-body scan + blob + login-wall) from day one — it's only ~300 LOC on top of v1.
- Test on all 10 analyzed sites — the gate is now ≥9/10 (up from ≥7/10) because the enhanced extractor should handle animepahe + mkissa too.

**Phase 1 (MVP, 7 weeks — unchanged except:):**
- Ship the enhanced universal extractor v2 (not v1).
- Drop the hand-written animepahe + reanime SiteModules (the enhanced extractor handles them). Keep only the 3 theme modules (AnikotoTheme, animestream, AllAnime) as optional performance optimizations.
- Add the translation layer (ML Kit + cache + AniList) — it's low-effort and a feature lead.
- Add 17-language app UI i18n.

**Phase 2 (Public Beta, 6 weeks — now includes:):**
- **Ship the LLM-Assisted Site Analyzer** (`:feature-autolearn`) — the differentiator. Remote-first (Groq) with on-device fallback (Gemma 3 1B via LiteRT-LM). This is what makes Reverb "fully automated" per the user's request.
- Ship the `:feature-ondevice-llm` dynamic feature.
- Ship Brave-rust ad-blocker swap.
- Ship youtubedl-android dynamic feature (Tier D extraction + aria2c downloads + FFmpeg for DASH mux).
- Ship DeepL + TranslateGemma translation options.

**Phase 3 (v1.x):** unchanged + opt-in VPN ad-block + community LearnedSiteConfig sharing.

**Phase 4 (v2 future):** KMP desktop port (now more feasible because `:source-api` + `:core:*` + `:feature-autolearn`'s LLM pipeline are all JVM/KMP-able).

---

## 27. The bottom line for the user

> *"I want to make it fully automated. Stuff like analyzing the website, like our app will analyze the website and try to make all the appropriate changes as needed and try to create a UI."*

**v3.0 delivers exactly this.** The combination of:
1. **Enhanced Universal Extractor v2** (handles video for any site, including the obfuscated ones, via JS exec + interaction sim + response-body scan + blob + login-wall), and
2. **LLM-Assisted Site Analyzer** (handles UI rebuild for any site, via HTML simplification + LLM + selector validation)

…means every one of the 10 analyzed sites is now **full-auto** — no hand-written Kotlin, no manual user training (except a one-time login for auth-gated sites like reanime). The user enters a URL; the app analyzes the HTML, generates a `LearnedSiteConfig`, validates it, renders the rebuilt UI, detects the video, blocks ads, and offers download. All automatic.

The theme modules (AnikotoTheme, animestream, AllAnime) and manual Learn Mode remain as optional fallbacks / performance optimizations — they're not required for any site, but they make repeat visits faster (no LLM call) and work offline.

This is the plan. Build it.

---

*End of PLAN.md — v3.0. Full research record: `/home/z/my-project/research/` (9 files: 01-a tech-stack-spec, 01-b on-device-llm, 01-c translation-i18n, 02-b anime-extensions-anatomy, 02-d ad-blocking, 02-e 1dm-plus-download-ux, 04-b anime-sites-batch2, sites-batch1/, fullpage-v2.png) + `/home/z/my-project/worklog.md` (all task entries 2-a through ORCHESTRATOR-v3).*
