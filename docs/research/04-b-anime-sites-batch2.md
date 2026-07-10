# Anime Site Analysis — Batch 2 (Task 4-b)

**Sites analyzed:** anidb.app, anizone.to, animekhor.org, animexin.dev, donghuastream.org
**Purpose:** Produce structured technical profiles so the Reverb planning team can decide per-site strategy (full-auto vs hybrid vs hand-written SiteModule) and re-use findings from task 2-b's 10 video-URL-extraction patterns.

**Method:**
- For each site: `curl -sI` for headers (Cloudflare / DDoS-Guard / Turnstile detection); `curl -sL` with desktop Chrome UA for raw HTML; if Cloudflare managed challenge, fall back to Wayback Machine (`web.archive.org/web/<ts>/<url>`) for cached snapshots.
- Parse HTML with Python regex to extract card structure, episode list, player markup.
- Probe known JSON API patterns when a site is clearly framework-driven (Laravel/Alpine).
- Do NOT download any actual video — only inspect the embed/player page HTML for stream-URL presence.

---

## 1. anidb.app

- **URL:** https://anidb.app/home (also `/browse`, `/anime/<slug>-<id>`, `/anime/<slug>-<id>#player`)
- **Rendering:** Server-rendered Laravel blade + Alpine.js sprinkles (x-data / x-init / x-show) + Tailwind + Vite-bundled JS. NOT a SPA — every page is fully formed HTML on first paint; Alpine handles only the player iframe swap and episode search filter.
- **Anti-bot:** Cloudflare CDN (`Server: cloudflare`, `cf-ray` header) but **no managed challenge** — plain `curl` with desktop UA returns 200. Sets Laravel cookies (`XSRF-TOKEN`, `anidb_session`). No Turnstile / reCAPTCHA / DDoS-Guard observed.
- **Catalog structure:** `/home` and `/browse` list cards as
  ```html
  <a href="https://anidb.app/anime/<slug>-<numeric-id>" class="anime-card block group" title="<title>">
    <div class="relative overflow-hidden rounded-xl bg-elevated" style="aspect-ratio:2/3">
      <img src="https://cdn.anidb.app/poster/small/<...>/<id>.jpg" alt="..." loading="lazy"/>
      <span class="badge badge-orange text-[9px]" ...>TV</span>            <!-- type: TV/Movie/ONA/Special -->
      <span class="badge badge-gray ..."><svg...star icon/>8.7</span>      <!-- rating -->
      <div class="card-overlay ..."><p class="text-xs font-semibold text-white line-clamp-2 leading-tight">Title</p></div>
    </div>
    <p class="text-xs text-faint mt-1.5 line-clamp-2 leading-tight">Title</p>
  </a>
  ```
  Fields per card: poster (CDN URL with aspect-ratio 2/3), title (in both `title=` attr and `<p>` text), type badge (TV/Movie/ONA/Special/Music), rating (numeric). NO episode count or year on the card itself.
- **Browse filters:** `/browse?page=N&genres=<id>&sort=order_popular|order_top_airing|order_updated&type=TV|Movie|ONA|OVA|Special|Music&status=Currently+Airing` — pure server-side rendering. Genre taxonomy at `/genres` and `/genres/<id>`.
- **Search:** As-you-type autocomplete via Alpine + `fetch('/search/suggestions?q=<q>')`. Returns **HTML fragments** (not JSON) — one `<a href data-search-item>` per result, each with poster img, title, type/year subtitle. Trivially Jsoup-parseable. Verified:
  ```
  GET /search/suggestions?q=one → 200 OK
  <a href="https://anidb.app/anime/one-punch-man-...-3934" data-search-item ...>
    <img src="https://cdn.anidb.app/poster/small/1782735600/3934.jpg" .../>
    <p class="text-sm font-medium text-white ...">One-Punch Man: ...</p>
    <p class="text-xs text-muted mt-0.5">Special · 2025</p>
  </a>
  ```
- **Details URL pattern:** `/anime/<slug>-<numeric-id>` (slug is the title slug, ID is a small integer). Example: `/anime/one-piece-3880`.
- **Details fields:**
  - Poster: `<meta property="og:image" content="https://cdn.anidb.app/poster/small/<...>/<id>.jpg">` — also in JSON-LD.
  - Synopsis: `<meta name="description">` / `<meta property="og:description">` (truncated ~155 chars in meta, full text in body).
  - Genres: **JSON-LD `<script type="application/ld+json">** `{"@type":"TVSeries","name":"...","description":"...","image":"...","genre":["Action","Adventure","Fantasy"]}`.
  - Year/duration/synonyms: `<dl><dt class="text-muted text-xs mb-0.5">Aired</dt><dd class="text-faint font-medium">Oct 20, 1999</dd></dl>` (also Duration, Synonyms).
  - Studio / season / score: rendered but minimal in body (mostly inside Alpine components).
  - Episode list: rendered **client-side** by Alpine (`x-data='watchPage(<id>, {...})'`) calling a clean REST API.
  - Related anime: cards in same `<a class="anime-card block group">` format as catalog.
- **Player:** `<iframe x-ref="playerFrame">` whose `src` is swapped by Alpine when a language/server is selected. NO direct iframe in initial HTML — Alpine calls API then sets `embedSrc`.
- **Video host:** **AniDB's own embed** — `https://anidb.app/embed/<token>` — a JW Player 8.26.1 page that loads HLS from `https://hls.anidb.app/stream/<token>/master.m3u8`.
- **Stream URL found:** **YES — direct m3u8 in embed page HTML, fetchable with Referer.** Verified end-to-end:
  ```
  # Step 1: get episode list (JSON API)
  GET /api/frontend/anime/3880/episodes
  → {"episodes":[{"id":3512,"number":1,"number2":null,"filler":false}, ...]}

  # Step 2: get languages per episode (JSON API)
  GET /api/frontend/episode/3512/languages
  → {"languages":[{"code":"jpn","name":"Japanese","embed_url":"https://anidb.app/embed/giSaYPT2xrS1mlIe7LgrKpBdl1kSrXaJQ5LMKOLzSNs"}]}

  # Step 3: fetch embed page (HTML)
  GET /embed/giSaYPT2xrS1mlIe7LgrKpBdl1kSrXaJQ5LMKOLzSNs
  → contains:
    var setup = { sources: [{ file: 'https://hls.anidb.app/stream/i3_oFEsF9AtR7fgqMSZsEm03AaeqRdOpej4vDabD-zhegwmTBWvi77qbkiak6z-1/master.m3u8', type: 'hls' }], ... };

  # Step 4: fetch master m3u8 (works with Referer header)
  GET -H "Referer: https://anidb.app/embed/..." https://hls.anidb.app/stream/.../master.m3u8
  → #EXTM3U
    #EXT-X-STREAM-INF:BANDWIDTH=1261457,RESOLUTION=1920x1080,FRAME-RATE=23.974,CODECS="avc1.64001f,mp4a.40.5"
    https://hls.anidb.app/stream/.../index-f1-v1-a1.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=662170,RESOLUTION=1280x720,...
    https://hls.anidb.app/stream/.../index-f2-v1-a1.m3u8
  ```
- **Pattern match (task 2-b):** **Pattern #1** (direct JSON field) — the `embed_url` field of `/api/frontend/episode/<id>/languages` is the embed page URL; the embed page contains the m3u8 as a plain JS object literal in `setup.sources[0].file`. Even simpler than pattern #1: no decryption, no unpacking, just regex `file:\s*'([^']+\.m3u8)'`.
- **Video extraction difficulty:** **Easy.** The universal WebView interceptor from task 2-b (`lib/universalextractor`) would catch the `master.m3u8` XHR with zero site-specific code. EVEN EASIER: just regex the embed page HTML — no WebView needed at all.
- **UI rebuild difficulty:** **Easy.** Clean semantic HTML, stable CSS classes (Tailwind utilities), JSON-LD for structured data, AND a documented REST JSON API (`/api/frontend/anime/<id>/episodes`, `/api/frontend/episode/<id>/languages`, `/search/suggestions?q=`). A generic theme extractor could structure this with no hand-written selectors — but a 30-line SiteModule would do it perfectly.
- **Recommended strategy:** **Full-auto for video (universal extractor pattern #1). Theme-based SiteModule for UI** (~50 LOC Kotlin) — a single class hitting the JSON API + the embed-page regex. Reverb's universal WebView fallback would also work out-of-the-box.
- **Notes:**
  - Includes NSFW/mature titles in the catalog (e.g. "What She Fell on Was the Tip of My Dick", "Do You Like Big Girls", "Simple Yet Sexy") — ad-block and content-filtering consideration.
  - "AniDB" is also the name of the long-standing anime database site `anidb.net` (since 2002). `anidb.app` is unrelated — it's a streaming site using the AniDB brand. Likely a clone/lookalike.
  - Stream CDN `hls.anidb.app` is on Cloudflare — fetches work without auth, but rate-limiting possible. Caching may invalidate tokens after some time.

---

## 2. anizone.to

- **URL:** https://anizone.to/ (also `/anime`, `/anime/<8-char-id>`, `/anime/<8-char-id>/<ep-num>`)
- **Rendering:** Server-rendered Laravel + **Livewire** (wire:snapshot / wire:id / wire:navigate attributes everywhere) + Alpine.js + Tailwind + **Vidstack** (modern web component video player, `@vimejs`/`vidstack` library). NOT a SPA — pages are fully HTML-rendered; Livewire handles reactive partial updates (search submit, navbar state) via POST requests with snapshot tokens.
- **Anti-bot:** Cloudflare CDN (`Server: cloudflare`, `cf-ray`) but **no managed challenge** — `curl` returns 200 directly. Sets Laravel cookies (`XSRF-TOKEN`, `anizone_session`). No Turnstile / reCAPTCHA observed.
- **Catalog structure:** Home page `/` and anime index `/anime` list cards as
  ```html
  <a @click.prevent="Livewire.navigate('http://anizone.to/anime/<8-char-id>')"
     href="https://anizone.to/anime/<8-char-id>"
     class="relative block aspect-[5/7] overflow-hidden drop-shadow-lg rounded-lg">
    <img src="https://anizone.to/images/anime/<uuid>.jpg"
         :alt="displayAnimeTitle"
         class="absolute bg-gray-700 object-cover h-full w-full"
         loading="lazy"/>
  </a>
  ```
  Fields per card: **poster only** (no title text, no episode count, no rating visible in the catalog card itself — title is rendered via Alpine `displayAnimeTitle` from a JSON titles object embedded in the page). The anime index `/anime` lists 24 entries per page (paginated via Livewire — POST request to next page).
- **Search:** Livewire form: `<form wire:submit="simpleSearch"><input wire:model="search"/></form>`. Submitting requires Livewire's POST protocol with the wire:snapshot token — not curl-friendly. The `/anime` index page is the browsable fallback (server-rendered, paginated).
- **Details URL pattern:** `/anime/<8-char-id>` (e.g. `/anime/26ofxzce`). IDs look like NanoID-style 8-char base32 strings.
- **Details fields:**
  - Title: Alpine-reactive `<h1 x-text="displayAnimeTitle">` — the raw titles object is embedded in the page as `x-data="{ anmTitles: JSON.parse(...) }"`. Available in en/ja/romaji.
  - Poster: `<img src="https://anizone.to/images/anime/<uuid>.jpg" :alt="displayAnimeTitle"/>`.
  - Episode count: `<span class="inline-block">13 Episodes</span>` next to a "TV Series" type badge.
  - Synopsis: `<h3 class="sr-only">Synopsis</h3>` followed by a description paragraph (also in `<meta name="description">`).
  - Type, status: visible badges near the title.
  - Episode list: server-rendered as a `<table>`-style list with sort dropdown (release-asc, default-desc, etc.) and pagination — but interactions are Livewire.
  - "Start Watching" button: `<a href="https://anizone.to/anime/<id>/1">` (direct link to first episode — server-rendered, can scrape without JS).
- **Player:** Episode page `/anime/<id>/<ep-num>` contains the **Vidstack `<media-player>` web component** with the master m3u8 URL **directly in the `src` attribute**:
  ```html
  <media-player wire:ignore class="overflow-hidden" style="border:0!important;"
      src="https://suzaku.xin-cdn.xyz/8825aba7-1f0f-4d94-ba28-423ba3bddc1b/master.m3u8"
      storage="v-8825aba7-1f0f-4d94-ba28-423ba3bddc1b"
      playsinline crossorigin keep-alive>
  </media-player>
  <media-video-layout thumbnails="https://suzaku.xin-cdn.xyz/8825aba7-.../storyboard.vtt"></media-video-layout>
  ```
- **Video host:** **Direct HLS** from `suzaku.xin-cdn.xyz` (AniZone's own CDN, fronted by Cloudflare). No third-party host (no Doodstream/Filemoon/etc.) — content is self-hosted.
- **Stream URL found:** **YES — master m3u8 in plain HTML attribute.** Verified:
  ```
  GET -H "Referer: https://anizone.to/" https://suzaku.xin-cdn.xyz/<uuid>/master.m3u8
  → HTTP/2 200, content-type: application/vnd.apple.mpegurl
    access-control-allow-origin: https://anizone.to   # CORS-restricted to anizone.to (browser-only)
    #EXTM3U
    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="group_audio",NAME="Japanese",DEFAULT=YES,LANGUAGE="ja",URI="audio/0_ja/playlist.m3u8"
    #EXT-X-STREAM-INF:BANDWIDTH=946000,RESOLUTION=640x360,...,AUDIO="group_audio"
    video/360/playlist.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=2376000,RESOLUTION=1280x720,...
    video/720/playlist.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=3476000,RESOLUTION=1920x1080,...
    video/1080/playlist.m3u8
  ```
  CORS restriction is irrelevant for Android Media3 (ExoPlayer) — only browser Web players enforce it. Also has separate audio track (Japanese) and storyboard thumbnails (VTT).
- **Pattern match (task 2-b):** **Pattern #1** (direct attribute / direct JSON field). Same simplicity as KissKH from task 2-b's walkthrough. The m3u8 is in the HTML `src` attribute of a custom element — regex `src="([^"]*master\.m3u8)"` on the episode page.
- **Video extraction difficulty:** **Easy.** Universal WebView interceptor catches the m3u8 XHR trivially. Even simpler: regex the `src` attribute from the page HTML — no JS execution needed at all.
- **UI rebuild difficulty:** **Medium.** Catalog cards are sparse (no titles in HTML — rendered by Alpine from embedded JSON). Browse pagination and search use Livewire POSTs with snapshot tokens (annoying to scrape without executing JS, but the server-rendered initial page is usable). Detail and episode pages are clean server HTML.
- **Recommended strategy:** **Full-auto for video (regex m3u8 from `media-player src=`). Hybrid for UI** — a small hand-written SiteModule (~80 LOC) that parses the anime index `/anime` page (server-rendered), the detail page (parse `anmTitles` JSON for the title), and the episode page (regex the m3u8 from `media-player`). Skip Livewire search — use `/anime` browse instead, or implement a Livewire-aware HTTP client.
- **Notes:**
  - Uses Bunny Fonts (`fonts.bunny.net`) instead of Google Fonts — privacy-focused.
  - Vidstack is a modern alternative to video.js — supports HLS/DASH/MP4 natively via hls.js or native.
  - The `crossorigin` attribute on `<media-player>` plus the CDN's `access-control-allow-origin: https://anizone.to` means a WebView-based player would work; an Android Media3 player bypasses CORS entirely.
  - "AniZone" branding — note that `anizone.to` is unrelated to the long-running AnimeZone.ro Romanian anime site.

---

## 3. animekhor.org

- **URL:** https://animekhor.org/ (also `/anime/<slug>/`, `/<slug>-episode-N-subtitles-.../`)
- **Rendering:** Server-rendered **WordPress** (Yoast SEO 28.0) using a commercial WP theme named **"animestream"** (`/wp-content/themes/animestream/style.css?ver=2.3.0`). NOT a SPA.
- **Anti-bot:** **Cloudflare MANAGED CHALLENGE** — `curl` returns 403 with `cf-mitigated: challenge` and serves the "Just a moment..." JS challenge page (`content-security-policy` references `challenges.cloudflare.com`). Reverb would need the `lib/cloudflareinterceptor` WebView cookie-poll bypass from task 2-b for every request. Older Wayback Machine captures show an additional "Bot Verification" reCAPTCHA page (v2 sitekey `6LewU34UAAAAAHvXqFOcQlm8z1MP1xpGAZCYEeZY`) — possibly DDoS-Guard layered on top of CF historically, though current live site only shows CF challenge.
- **Catalog structure:** Home page lists cards inside `<div class="listupd normal"><div class="excstf">`:
  ```html
  <article class="bs" itemscope itemtype="http://schema.org/CreativeWork">
    <div class="bsx">
      <a href="https://animekhor.org/<slug>/" itemprop="url" title="<full title>" class="tip" rel="<post_id>">
        <div class="limit">
          <div class="hotbadge"><i class="fas fa-fire-alt"></i></div>      <!-- trending badge (optional) -->
          <div class="typez Comic">Comic</div>                            <!-- type: Anime/Comic/Movie/Donghua -->
          <div class="ply"><i class="far fa-play-circle"></i></div>        <!-- play overlay -->
          <div class="bt">
            <span class="epx">Ep 41 to 50</span>                          <!-- episode range -->
            <span class="sb Sub">Sub</span>                                <!-- Sub/Dub -->
          </div>
          <img src="https://i0.wp.com/animekhor.org/wp-content/uploads/.../<slug>.webp?resize=247,350"
               class="ts-post-image wp-post-image attachment-post-thumbnail size-post-thumbnail"
               loading="lazy" itemprop="image" width="247" height="350"/>
        </div>
        <div class="tt">
          <h2 itemprop="headline"><full title></h2>
        </div>
      </a>
    </div>
  </article>
  ```
  Fields per card: poster (Jetpack-photon-cached `i0.wp.com`), full title (`title=` attr + `<h2>`), post ID (`rel=`), type (Anime/Comic/Movie/Donghua), sub/dub badge, episode range. No rating/year in the card.
- **Browse:** `/anime/?status=&type=&order=update` (filterable listing). Pagination via WP standard `/?paged=N` or `/page/N/`. Standard WP search `/?s=<q>` works server-side.
- **Details URL pattern:** `/anime/<slug>/` (e.g. `/anime/100000-levels-of-body-refining-all-the-dogs-i-raise-are-the-emperor/`).
- **Details fields:** standard WP theme fields — poster, title, alternative names, genres (linked tags), studio, status, released year, episodes count, synopsis in a `.entry-content` block. All server-rendered.
- **Episode list:**
  ```html
  <div class="eplister">
    <div class="ephead"><div class="eph-num">Ep</div><div class="eph-title">Title</div><div class="eph-date">Release Date</div></div>
    <ul>
      <li data-index="0">
        <a href="https://animekhor.org/<slug>-episode-168-subtitles-english-indonesian/">
          <div class="epl-num">168</div>
          <div class="epl-title">Episode 168</div>
          <div class="epl-date">December 4, 2024</div>
        </a>
      </li>
      ... (descending order)
    </ul>
  </div>
  ```
- **Player:** Episode page has:
  ```html
  <div class="player-embed" id="pembed">
    <iframe width="640" height="360" src="//ok.ru/videoembed/6604265097824" frameborder="0" allow="autoplay" allowfullscreen></iframe>
  </div>
  <select class="mirror" name="mirror" onchange="loadMi(this);" aria-label="mirror">
    <option value="">Select Video Server</option>
    <option value="PGlmcmFtZSB3aWR0aD0iNjQwIiBoZWlnaHQ9IjM2MCIgc3JjPSIvL29rLnJ1L3ZpZGVvZW1iZWQvNjYwNDI2NTA5NzgyNCIg... " data-index="1">ok.ru《Ads Free》[ENGLISH]</option>
    <option value="PGlmcmFtZSBjbGFzcz0icnVtYmxlIiB3aWR0aD0iNjQwIiBoZWlnaHQ9IjM2MCIgc3JjPSJodHRwczovL3J1bWJsZS5jb20vZW1iZWQv..." data-index="2">RumblePlayer [ENGLISH]</option>
    <option value="PElGUkFNRSBTUkM9Imh0dHBzOi8vd29sZnN0cmVhbS50di9lbWJlZC1ubG9ja2xodXM4YS5odG1sIiBGUkFNRUJPUkRFUj0wIC4uLg==" data-index="3">All New Player [ENGLISH]</option>
    <option value="PElGUkFNRSBTUkM9Imh0dHBzOi8vY2Rud2lzaC5jb20vZS9saHk0em1mdHB0ZTUiIC4uLg==" data-index="4">StreamWish [MULTI SUB]</option>
    <option value="PElGUkFNRSBTUkM9Imh0dHBzOi8vdmlkaGlkZXZpcC5jb20vdi9qNGNld2VhZzF4dnEiIC4uLg==" data-index="5">FilePlayer [MULTI SUB]</option>
    <option value="PElGUkFNRSBTUkM9Imh0dHBzOi8vd3d3Lm1wNHVwbG9hZC5jb20vZW1iZWQtazk5bGpvOHdrZ2U4Lmh0bWwiIC4uLg==" data-index="6">mp4Upload [ENGLISH]</option>
    <option value="PGlmcmFtZSB3aWR0aD0iNjQwIiBoZWlnaHQ9IjM2MCIgc3JjPSJodHRwczovL3Nob3J0Lmluay9NS1RudkpSZkciIC4uLg==" data-index="7">AbyssPlayer [MULTI SUB]</option>
    <option value="PGlmcmFtZSB3aWR0aD0iNjAwIiBoZWlnaHQ9IjQ4MCIgc3JjPSJodHRwczovL2QwMDBkLmNvbS9lL2k3bHUwNWs5dzB3ZGhsbGN3MHljNzQ5a3g2OXk1MmtmIiAuLi4=" data-index="8">Doodstream [MULTI SUB]</option>
  </select>
  ```
  **Critical: each `<option value="...">` is BASE64-encoded iframe HTML.** Decoding (verified) reveals the iframe `src` for each mirror:
  | Mirror | Decoded iframe src | Video host |
  |--------|--------------------|------------|
  | ok.ru | `//ok.ru/videoembed/<id>` | Odnoklassniki (Ok.ru) |
  | RumblePlayer | `https://rumble.com/embed/<id>?pub=<id>` | Rumble |
  | All New Player | `https://wolfstream.tv/embed-nlockchlhus8a.html` | WolfStream |
  | StreamWish | `https://cdnwish.com/e/<id>` | StreamWish (cdnwish.com proxy) |
  | FilePlayer | `https://vidhide.com/v/<id>` | VidHide |
  | mp4Upload | `https://www.mp4upload.com/embed-<id>.html` | mp4Upload |
  | AbyssPlayer | `https://short.link/MKTnvJRfG` | short.link redirector |
  | Doodstream | `https://d000d.com/e/<id>` | Doodstream (d000d.com proxy) |
- **Video host:** Multiple — Doodstream, StreamWish, mp4Upload, ok.ru, Rumble, WolfStream, VidHide, short.link (AbyssPlayer).
- **Stream URL found:** **NO direct m3u8/mp4 in the episode page** — would need to (a) base64-decode each mirror option, (b) fetch the iframe URL, (c) apply the corresponding per-host extractor.
- **Pattern matches (task 2-b):**
  - **Doodstream** → **Pattern #4** (MD5 + token + random 10-char suffix via `/pass_md5/<id>` GET) — exactly `lib/doodextractor`
  - **StreamWish** → **Pattern #8** (Synchrony-deobfuscated main.js + DMCA server rotation + regex `https[^"]*m3u8[^"]*`) — exactly `lib/streamwishextractor`
  - **mp4Upload** → **Pattern #6** (Dean-Edwards packed JS `eval(function(p,a,c,k,e,d)` + `.src(` substring chain) — exactly `lib/mp4uploadextractor`
  - **ok.ru / Rumble / WolfStream / VidHide** → various per-host extractors (some in `lib/`, some custom); each ultimately resolves to an mp4 or m3u8.
  - **Universal fallback** → **Pattern #10** (universal WebView `shouldInterceptRequest` regex `.*\.(mp4|m3u8|mpd)(\?.*)?$`) would catch the stream URL after the iframe loads, with no per-host code.
- **Video extraction difficulty:** **Hard** (without WebView) / **Medium** (with WebView). Per-host extractors needed for the no-WebView path; OR the universal WebView interceptor pattern handles it transparently once an iframe is loaded. The non-obvious step is **base64-decoding the `<option value="...">` to discover the iframe URL** — that's a 3-line snippet (`Base64.decode(option.value)` → Jsoup parse → `<iframe src=...>`), but it IS site-specific.
- **UI rebuild difficulty:** **Easy** (assuming CF bypass) — clean semantic HTML with stable, distinctive class names (`bsx`, `eplister`, `epl-num`, `epl-title`, `bs`, `tip`, `tt`, `bt`, `epx`, `sb Sub`). Crucially, **the same theme is shared with animexin.dev and donghuastream.org** — one shared SiteModule class instantiated 3× with different base URLs would handle all three.
- **Recommended strategy:** **theme("animestream")** — a shared `lib-multisrc/animestreamtheme/` module (mirroring yuzono's `lib-multisrc/anikototheme` pattern) handling animekhor, animexin, and donghuastream. The module would: (1) use `CloudflareBypass` interceptor for HTTP, (2) parse `<article class="bs">` cards, (3) parse `<div class="eplister">` for episodes, (4) base64-decode `<select class="mirror">` options to enumerate iframe URLs, (5) dispatch each iframe URL to the appropriate `lib/*` extractor (dood, streamwish, mp4upload, okru, etc.), (6) fall back to `lib/universalextractor` (WebView) for unknown hosts (wolfstream, vidhide, abyss).
- **Notes:**
  - Cloudflare challenge is the gating constraint — every page fetch requires the WebView cookie-poll dance.
  - Some content is mature/NSFW-adjacent (e.g. adjacent listings include "Do You Like Big Girls", "What She Fell on Was the Tip of My Dick"). Ad-block + content-filter consideration.
  - WolfStream, VidHide, AbyssPlayer (short.link) are not in the standard `lib/*` of yuzono — they'd need either a new per-host extractor or the universal WebView fallback.
  - The theme uses Photon (Jetpack) image caching at `i0.wp.com/<site>/...` — posters load fine off-domain.

---

## 4. animexin.dev

- **URL:** https://animexin.dev/ (also `/anime/<slug>/`, `/<slug>-episode-N-...-sub/`)
- **Rendering:** Server-rendered **WordPress** (Yoast SEO 28.0) using **the same commercial WP theme "animestream"** as animekhor.org (`/wp-content/themes/animestream/style.css?ver=2.3.2` — note slightly newer version 2.3.2 vs animekhor's 2.3.0). Nucuta cache plugin (`nucuta-cache-location: /wp-content/cache/all/index.html`). NOT a SPA.
- **Anti-bot:** Cloudflare CDN (`Server: cloudflare`) but **no managed challenge** — `curl` returns 200 directly. No Turnstile / reCAPTCHA / DDoS-Guard observed. Page-load is very fast due to Nucuta static HTML cache.
- **Catalog structure:** **Identical to animekhor** — `<article class="bs"><div class="bsx"><a class="tip" rel="<post_id>" href="/<slug>/"><div class="limit">...<img class="ts-post-image wp-post-image".../><div class="bt"><span class="epx">Ep N</span><span class="sb Sub">Sub</span></div><div class="typez Donghua">Donghua</div></div><div class="tt"><h2 itemprop="headline">Title</h2></div></a></div></article>` inside `<div class="listupd popularslider">` / `<div class="listupd normal">` containers.
- **Browse / Search / Pagination:** Identical WP patterns to animekhor — `/?s=<q>` for search, `/page/N/` for pagination, `/anime/?status=&type=&order=update` for filters.
- **Details URL pattern:** `/anime/<slug>/` (e.g. `/anime/perfect-world-wanmei-shijie/`). Some slugs omit `/anime/` prefix on episode pages.
- **Details fields:** same as animekhor — poster, title, alt names, genres (linked tags), studio, status, year, episodes count, synopsis.
- **Episode list:** **Identical `<div class="eplister">` structure** to animekhor — `<ul><li data-index="N"><a href="/<slug>-episode-N-...-sub/"><div class="epl-num">N</div><div class="epl-title">Episode N</div><div class="epl-date">...</div></a></li>...</ul>`.
- **Player:** **Identical pattern** to animekhor — `<div class="player-embed" id="pembed"><iframe src="<host>"></iframe></div>` + `<select class="mirror" name="mirror" onchange="loadMi(this);"><option value="<base64-iframe-HTML>" data-index="N">`. Verified on episode 276 of "Perfect World [Wanmei Shijie]" — first iframe points to `https://geo.dailymotion.com/player/x1kcvu.html?video=<id>`. Mirror options visible in HTML include: Dailymotion (default), Doodstream ("All Sub Player Dood"), StreamWish ("All Sub Streamwish"), ok.ru (Hardsub Indonesia + Hardsub English), Rumble (Hardsub ID + EN).
- **Video host:** Multiple — Dailymotion, Doodstream, StreamWish, ok.ru, Rumble (subset of animekhor's host set, plus Dailymotion).
- **Stream URL found:** **NO direct m3u8/mp4 in the episode page** — same base64-mirror-decode + per-host extractor flow as animekhor.
- **Pattern matches (task 2-b):** Identical per-host patterns as animekhor — Doodstream (#4), StreamWish (#8), mp4Upload (#6), ok.ru, Rumble, Dailymotion (custom extractor needed; or universal WebView fallback). Plus universal Pattern #10 as fallback.
- **Video extraction difficulty:** **Hard** (without WebView) / **Medium** (with WebView) — same as animekhor.
- **UI rebuild difficulty:** **Easy** — same theme as animekhor/donghuastream. Zero hand-written work needed beyond the shared animestream theme module.
- **Recommended strategy:** **theme("animestream")** — instantiate the same shared module as animekhor and donghuastream with `baseUrl = "https://animexin.dev"`. The only animexin-specific quirk: include Dailymotion extractor in the dispatch table (Dailymotion has a public API; extractor is straightforward).
- **Notes:**
  - Site focuses on donghua (Chinese anime) with Indonesian + English subs.
  - Nucuta cache means HTML is fully static — extremely fast scraping, no JS execution needed for any page.
  - No Cloudflare challenge — the CF bypass layer is unnecessary for this site, only for animekhor.

---

## 5. donghuastream.org

- **URL:** https://donghuastream.org/ (also `/anime/<slug>/`, `/<slug>-episode-N-...-subtitles/`)
- **Rendering:** Server-rendered **WordPress** (Rank Math SEO) using **a nullified (pirated) copy of the same "animestream" theme** (`/wp-content/themes/null%20animestream/assets/js/jquery.min.js?ver=3.5.1` — note the `null%20` URL-encoded prefix indicating a "null" / cracked version). LiteSpeed Cache plugin (`x-litespeed-cache: hit`, `x-turbo-charged-by: LiteSpeed`). NOT a SPA.
- **Anti-bot:** Cloudflare CDN (`Server: cloudflare`) but **no managed challenge** — `curl` returns 200 directly. No Turnstile / reCAPTCHA / DDoS-Guard observed.
- **Catalog structure:** **Identical to animekhor/animexin** — `<article class="bs"><div class="bsx"><a class="tip" href="/anime/<slug>/"><div class="limit">...</div></a></div></article>` inside `<div class="listupd normal">`. Same fields (poster, title, type, episode count, sub/dub). One quirk: LiteSpeed lazy-loading means posters use `<img data-lazyloaded="1" src="data:image/svg+xml;base64,..." data-litespeed-src="<real-url>"/>` — scraper must read `data-litespeed-src` not `src`.
- **Browse / Search / Pagination:** Search: `/?s=<q>` (standard WP). **Pagination is the ONE differentiator** — uses `/pagg/N/` (custom rewrite, note the double-G) instead of the standard WP `/page/N/`. Confirmed via `<link rel="next" href="https://donghuastream.org/pagg/2/">`.
- **Details URL pattern:** `/anime/<slug>/` (e.g. `/anime/the-last-dynasty/`).
- **Details fields:** Same as animekhor/animexin — full WP theme structure with poster, title, alt names, genres, studio, year, synopsis, `<div class="eplister">` for episodes.
- **Episode list:** Identical `<div class="eplister">` structure.
- **Player:** **Identical pattern** — `<div class="player-embed" id="pembed"><iframe data-lazyloaded="1" src="about:blank" data-litespeed-src="<host>"></iframe></div>` + `<select class="mirror" name="mirror" onchange="loadMi(this);"><option value="<base64-iframe-HTML>" data-index="N">`. LiteSpeed lazy-loading quirk: iframe `src="about:blank"` initially, real URL in `data-litespeed-src`. Verified on episode 16 of "The Chosen One (The Last Dynasty)" — first iframe (after lazy-load) points to `https://geo.dailymotion.com/player/x19jsm.html?video=<id>`. Mirror options visible include Dailymotion, ok.ru (English), Rumble (English), plus likely more (Doodstream/StreamWish — the base64 options were truncated in my grep).
- **Video host:** Multiple — Dailymotion (default), ok.ru, Rumble, and (extrapolating from the shared theme) likely Doodstream/StreamWish/mp4Upload when available for other episodes.
- **Stream URL found:** **NO direct m3u8/mp4 in the episode page** — same base64-mirror-decode + per-host extractor flow.
- **Pattern matches (task 2-b):** Same per-host patterns as animekhor/animexin. Universal Pattern #10 as fallback.
- **Video extraction difficulty:** **Hard** (without WebView) / **Medium** (with WebView) — same as animekhor/animexin.
- **UI rebuild difficulty:** **Easy** — same theme. Two minor quirks to handle in the shared module: (1) read `data-litespeed-src` instead of `src` for lazy-loaded iframes/images, (2) use `/pagg/N/` for pagination instead of `/page/N/`.
- **Recommended strategy:** **theme("animestream")** — instantiate the same shared module as animekhor and animexin with `baseUrl = "https://donghuastream.org"` and two config flags: `lazyLoadAttr = "data-litespeed-src"` and `paginationPath = "/pagg/"`.
- **Notes:**
  - Site focuses on donghua (Chinese anime) with subs in 15+ languages (English, Indonesian, Myanmar, Arabic, French, German, Italian, Khmer, Persian, Polish, Portuguese, Russian, Spanish, Thai, Turkish, Vietnamese).
  - LiteSpeed cache makes pages static and fast.
  - Dailymotion is the primary/default player here (and on animexin) — a Dailymotion extractor would be needed (Dailymotion has a public API; straightforward).
  - The theme is "null animestream" — a cracked version of the commercial theme. Don't include that detail in user-facing UI.

---

## Batch Summary

### Per-site verdicts

| # | Site | Rendering | Anti-bot | Video host | Stream URL found | Video diff | UI diff | Recommended strategy |
|---|------|-----------|----------|-----------|------------------|-----------|--------|----------------------|
| 1 | **anidb.app** | Laravel + Alpine + Tailwind | CF (no challenge) | AniDB self-host (JW Player + hls.anidb.app) | YES — m3u8 in embed page JS object | **Easy** | **Easy** | **Full-auto** (universal extractor or 30-LOC site module hitting JSON API) |
| 2 | **anizone.to** | Laravel + Livewire + Vidstack | CF (no challenge) | Direct HLS (suzaku.xin-cdn.xyz) | YES — m3u8 in `<media-player src=>` attr | **Easy** | **Medium** | **Hybrid** — hand-written for UI (Livewire quirks), full-auto for video |
| 3 | **animekhor.org** | WordPress + animestream theme | **CF managed challenge** | Multi (Dood, StreamWish, mp4Upload, ok.ru, Rumble, WolfStream, VidHide, Abyss) | NO — base64-encoded iframe options | **Hard** (no WebView) / Medium (with WebView) | **Easy** (post-CF) | **theme("animestream")** + CloudflareBypass + per-host extractors |
| 4 | **animexin.dev** | WordPress + animestream theme | CF (no challenge) | Multi (Dailymotion, Dood, StreamWish, ok.ru, Rumble) | NO — base64-encoded iframe options | **Hard** (no WebView) / Medium (with WebView) | **Easy** | **theme("animestream")** — share module with animekhor/donghuastream |
| 5 | **donghuastream.org** | WordPress + null animestream theme | CF (no challenge) | Multi (Dailymotion, ok.ru, Rumble, ...) | NO — base64-encoded iframe options | **Hard** (no WebView) / Medium (with WebView) | **Easy** (with `/pagg/` + `data-litespeed-src` quirks) | **theme("animestream")** — share module, 2 config flags |

### Counts

- **Full-auto-able for video (universal WebView interceptor would catch the stream URL with ZERO site-specific code): 2 of 5** — anidb.app (m3u8 in embed page), anizone.to (m3u8 in `media-player` src).
- **Full-auto-able for video WITH a tiny site-specific step (base64-decode + iframe load, then universal interceptor): +3 of 5** — animekhor/animexin/donghuastream all use the same base64-mirror-select pattern. A 5-line "decode option value, load iframe in WebView, let universal extractor catch m3u8" approach would handle all three with one shared snippet.
- **Total video full-auto-able (with the shared animestream base64-decode helper): 5 of 5.**
- **Full-auto-able for UI (no hand-written selectors needed): 1 of 5** — anidb.app (clean JSON API + JSON-LD).
- **Theme-shared UI (one SiteModule instantiated for multiple sites): 3 of 5** — animekhor + animexin + donghuastream all use the same "animestream" WP theme. ONE `lib-multisrc/animestreamtheme/` module handles all three.
- **Hand-written UI required: 1 of 5** — anizone.to (Livewire snapshot/POST protocol for search/pagination).
- **Cloudflare managed challenge (requires WebView cf_clearance bypass): 1 of 5** — animekhor.org. The other 4 sit behind Cloudflare but don't challenge.

### Cross-cutting findings

1. **The "animestream" WP theme is the dominant CMS in this batch** — 3 of 5 sites use it. This is exactly the situation yuzono's `lib-multisrc/` "theme" pattern was designed for: write one shared Kotlin class, instantiate it three times with different `baseUrl`s. The theme has a distinctive signature: `<article class="bs">` → `<div class="bsx">` → `<a class="tip" rel="<post_id>">` → `<div class="limit">` (with `<div class="bt"><span class="epx">Ep N</span><span class="sb Sub">Sub</span></div>` + `<div class="typez <Type>">` + `<img class="ts-post-image wp-post-image">`) → `<div class="tt"><h2 itemprop="headline">` for cards; `<div class="eplister"><ul><li data-index="N"><a href="/<slug>-episode-N-.../">` for episode lists; `<div class="player-embed" id="pembed"><iframe>` + `<select class="mirror" name="mirror" onchange="loadMi(this);"><option value="<base64-iframe-HTML>">` for players. Any future Reverb site-discovery could fingerprint a new site as "animestream theme" by checking for the `class="bsx"` + `class="eplister"` + `class="mirror"` selectors together.

2. **The base64-encoded `<option value>` mirror pattern is unusual and worth a dedicated helper.** It's not in yuzono's `lib/*` (none of the 16 lib modules we studied handle it). Reverb should add a small `AnimestreamMirrorDecoder` helper (~20 LOC) that takes the `<select class="mirror">` HTML and returns a `List<VideoSource>` of (label, iframe-url) pairs ready for per-host dispatch. This helper + the universal WebView fallback would auto-handle any animestream-theme site without per-site code.

3. **Two of five sites have DIRECT m3u8 in HTML** — anidb.app (in a JS object literal in the embed page) and anizone.to (in a `<media-player src>` attribute). Both are pattern #1 (direct JSON/attribute) from task 2-b's 10 patterns. These are the easiest possible cases — no extractor code needed beyond a regex.

4. **No site uses Doodstream/StreamWish/mp4Upload exclusivity** — the three animestream sites all offer MULTIPLE mirrors per episode. This is good for Reverb: if Doodstream is rate-limited or down, fall back to mp4Upload, etc. The shared animestream module should expose all mirrors as separate `Video` entries (with the host name in the label), matching yuzono's `getVideoList` convention.

5. **Cloudflare is on ALL 5 sites** but only animekhor has the managed challenge. Reverb should ship with `lib/cloudflareinterceptor` (the WebView cookie-poll pattern from task 2-b) enabled by default for all requests — it's a no-op when no challenge is present.

6. **NSFW content present** on anidb.app and adjacent to listings on animekhor — ad-block + content-filtering consideration. None of the sites are exclusively NSFW, but they don't filter adult-rated titles from browse either. Reverb should consider an opt-in "hide adult" filter (similar to Aniyomi's `isNsfw` extension flag from task 2-a).

7. **The `vidstack` web component on anizone.to is a modern, growing player choice.** Reverb's universal WebView interceptor already handles it (the m3u8 is right in the `src` attribute). No special handling needed.

8. **Two distinct Laravel patterns observed:** anidb.app uses pure Laravel + Alpine (no Livewire) — fully scrapeable via REST JSON API. anizone.to uses Laravel + Livewire — partial-hydration POSTs are awkward but the initial server HTML is scrapeable. The Reverb theme-extractor should distinguish these (look for `wire:snapshot` / `wire:id` attributes → Livewire mode).

### Implications for the Reverb plan

- The PLAN.md "Phase 0 spike gate ≥7/10 sites yield a playable stream" is achievable: this batch contributes 5/5 streamable URLs (2 trivially via regex, 3 trivially via base64-decode + universal WebView).
- The "theme" extraction strategy (yuzono `lib-multisrc/` pattern) is validated: 3 of 5 sites in this batch are theme-sharable.
- The universal WebView interceptor (`lib/universalextractor` pattern from task 2-b) is sufficient as the SOLE video-extraction mechanism for ALL 5 sites — provided Reverb ships the small `AnimestreamMirrorDecoder` helper to bridge the base64-mirror gap (which is itself just 5 lines: `<select class="mirror"> option.value → Base64.decode → Jsoup.parse → iframe.src`).
- Anidb.app's JSON API is a bonus — a hand-written 50-LOC SiteModule for anidb.app would be cleaner than the universal WebView path (no JS execution needed at all, just 3 HTTP calls: details page → episodes API → languages API → embed page → m3u8). Worth including as a "showcase" reference implementation in the Reverb codebase.
