# Task 2-e — 1DM+ Download UX & Open-Source Equivalents for Reverb

**Author**: general-purpose subagent (1DM+ / download UX researcher)
**Audience**: Reverb Android app planners
**Date**: 2026-07-10

This report reverse-engineers 1DM+'s "tap to download any video on any site" UX, then maps each layer to a concrete open-source stack we can lift for Reverb's download module. Code sketches are included for the trickier seams (intent-filter, aria2 invocation, queue Composable).

---

## 1. 1DM+ UX Breakdown

1DM+ (formerly IDM+, now "1DM: Browser & Video Download") is by Vicky Bonick. Two SKUs share one codebase:

| | 1DM (free, ad-supported) | 1DM+ (paid, ~US$1.99–3.99) |
|---|---|---|
| Multi-part HTTP connections | up to **16 parts** | up to **32 parts** |
| Simultaneous downloads | ~3–10 | up to **30** |
| Scheduler | no | **yes** |
| Ads | yes | no |
| Browser, video sniffing, torrents | same | same |

Verified facts are pulled from the live Play Store listing (`id=idm.internet.download.manager`) and 1DM+ (`id=idm.internet.download.manager.plus`). The description (verbatim, key bullets):

> - Supports up to 32 parts to accelerate video download (1DM+)
> - Up to 30 simultaneous downloads
> - Pause and Resume downloads with best error handling
> - Download m3u video / Download m3u8 video / Download MP-DASH video
> - Auto convert ts videos to mp4 after download
> - Browser will auto detect videos, and tap the download icon
> - Choose which video you want to download and download the video
> - Manage download with Video Downloader manager
> - Refresh expired links using the 1DM Browser (very useful for video download)
> - Scheduler (1DM+) to schedule your video & other downloads
> - Website Grabber to download all static files in a webpage
> - Batch downloader to download files with pattern
> - Download videos in hidden folder
> - File cataloging based on file type (music, video, document, zip, picture, torrent)
> - Smart download option to download files when you copy download links (clipboard monitor)
> - Import download links from a text file or clipboard
> - Multi-tab browser with bookmarks, history, incognito mode
> - Adblock + popup blocker + third-party tracker blocking
> - Torrent: magnet link, .torrent URL, or .torrent file

### 1a. (a) Opening the in-app browser

Launch 1DM → bottom nav has **Browser / Downloads / Files / Settings** tabs (and Torrent in 1DM+). Tap Browser. UI:

```
┌───────────────────────────────────────────────┐
│ ＋tabs  🔒 https://example.com              ⋮ │  ← tab strip + address bar + menu
├───────────────────────────────────────────────┤
│ < > ⟳  ⌂    │ search or type URL          ↩ │  ← nav bar
├───────────────────────────────────────────────┤
│                                               │
│       (rendered web page, WebView)            │
│                                               │
│                                               │
├───────────────────────────────────────────────┤
│  ＋ New tab   |  Downloads: 3   |   1DM  ⚙   │
└───────────────────────────────────────────────┘
```

A persistent **floating download icon** (⬇) appears in the bottom-right corner whenever the sniffer has at least one candidate. A badge count above it tracks how many media URLs the sniffer has captured so far on this page. The browser is a stock Android `WebView` with ad-blocking injected as a `WebViewClient.shouldInterceptRequest` filter (the same hook the universal extractor uses for sniffing — see §4).

### 1b. (b) Navigating to a video page

User types a URL or shares a link from Chrome → 1DM Browser opens it. As the page loads, every HTTP(S) request that the WebView issues for media extensions (`.mp4 .m4v .webm .mkv .ts .m3u8 .mpd .m3u .mp3 .m4a .aac .ogg .flac .torrent`) is logged into an in-memory ring buffer with URL + Referer + User-Agent + cookie header + content-type + content-length.

There are three detection tiers (inferred from observed behavior and what's technically feasible):

1. **`<video>` / `<source>` / `<embed>` DOM scrape** — for sites that put the URL in plain HTML.
2. **`shouldInterceptRequest` URL sniffing** — catches XHR-loaded HLS master playlists, fragmented MP4 byte ranges, and DASH `.mpd` manifests. This is the "magic" tier that catches "every single video stream from any site" (Reddit quote: *"It can detect every single video stream from any site without exception."*).
3. **Per-site extractors** for YouTube etc. — the Play Store listing explicitly says **"DOWNLOADING MUSIC, VIDEO etc FROM YOUTUBE IS NOT SUPPORTED by 1DM"** (ToS). So 1DM relies purely on tiers 1 and 2; YouTube videos are intentionally unsupported. This is a critical insight for Reverb: the universal `shouldInterceptRequest` sniffer handles the long tail, but our per-site extractors (NewPipeExtractor for YouTube etc.) need to complement it.

When the sniffer captures a master m3u8, it pre-fetches the playlist to enumerate variant streams (RESOLUTION/CODECS/BANDWIDTH) so the user can pick a quality in step (c). The floating download icon's badge updates live.

### 1c. (c) Tapping download

User taps the floating ⬇ icon. A **bottom sheet** slides up titled "Detected videos on this page":

```
┌──────────────────────────────────────────────┐
│  Detected videos on this page              ⌄ │
├──────────────────────────────────────────────┤
│  ▶ Big Buck Bunny                           │
│    https://cdn.example.com/bbb.mp4          │
│    MP4 · 1280×720 · H.264 · 1.2 GB          │
│    [ ⬇ Download ]   [ ⟳ Open in browser ]   │
├──────────────────────────────────────────────┤
│  ▶ HLS stream                               │
│    https://cdn.example.com/master.m3u8      │
│    HLS · 4 qualities:                       │
│      ○ 1080p (5.0 Mbps)                     │
│      ● 720p  (3.0 Mbps)  ← default          │
│      ○ 480p  (1.5 Mbps)                     │
│      ○ 240p  (0.5 Mbps)                     │
│    [ ⬇ Download ]   [ ⟳ Open in browser ]   │
├──────────────────────────────────────────────┤
│  ▶ DASH manifest                            │
│    https://cdn.example.com/stream.mpd       │
│    DASH · video+audio separated             │
│    [ ⬇ Download (mux via ffmpeg) ]          │
└──────────────────────────────────────────────┘
```

Behavior:
- **One tap** per detected stream. The sheet shows every quality of an HLS master inline (radio buttons); the user picks one. There's no separate "format picker" dialog unless the user wants to override defaults.
- "Download" button immediately enqueues the URL into the download manager and dismisses the sheet; a toast says *"Added to downloads"*. The user is back in the browser.
- For HLS streams, 1DM downloads every `.ts` segment sequentially, then runs `ffmpeg -i concat:... -c copy out.mp4` to produce a single MP4. The Play Store bullet "Auto convert ts videos to mp4 after download is finished" confirms this.
- For DASH, it fetches the video and audio adaptation sets separately and muxes them with ffmpeg into MP4 (same as aniyomi and Seal).
- Custom file naming, "download to hidden folder", and "save to SD card" are options in Settings that the user sets once.

### 1d. (d) Managing the download

User taps **Downloads** tab in the bottom nav. UI:

```
┌──────────────────────────────────────────────┐
│  Downloads                          ⚙  ⋮    │
│  [ All | Downloading(2) | Completed(5) ]    │
├──────────────────────────────────────────────┤
│  🎬 Big Buck Bunny           720p MP4        │
│      ████████████████░░░░░░  76%   2.4 MB/s  │
│      412 MB / 540 MB · 52s left              │
│      [ ⏸ Pause ]  [ ✕ Cancel ]  [ ⋯ ]        │
├──────────────────────────────────────────────┤
│  🎬 HLS stream               1080p MP4       │
│      ██████░░░░░░░░░░░░░░░░  23%   1.1 MB/s  │
│      120 MB / 520 MB · 6m left               │
│      [ ⏸ Pause ]  [ ✕ Cancel ]  [ ⋯ ]        │
├──────────────────────────────────────────────┤
│  ✅ Yesterday's vlog          480p MP4       │
│      Completed · 180 MB · tap to play        │
│      [ ▶ Play ]  [ ⤴ Share ]  [ ⌫ Delete ]   │
└──────────────────────────────────────────────┘
```

Each row shows thumbnail + title + quality + format, a horizontal progress bar with percent/speed/ETA, and a row of action buttons. Long-press selects for batch pause/cancel/move. The "⋯" overflow exposes: open file, open containing folder, copy URL, refresh expired link (1DM reopens the source page in the browser and re-sniffs for a fresh URL with valid token — a standout feature), retry on error, set custom path.

A persistent foreground notification shows the active download's progress as a `MediaStyle` notification with Pause/Cancel actions. Tapping it opens the Downloads tab.

### What makes 1DM+ UX good (the intangibles)

1. **Zero-config default**: no need to pick a format unless you want to. Sensible defaults (720p MP4) mean a tap is enough.
2. **Inline quality radio**: HLS quality selection is in the same sheet as the URL list, not a second modal. This is faster than NewPipe's two-step (tap download → format dialog).
3. **"Refresh expired link"**: a unique affordance that re-sniffs the source page to get a fresh signed URL when a stale download is resumed. Most download managers fail with 403 here.
4. **Clipboard monitor**: copy any URL from Chrome → 1DM pops a "Download this URL?" notification → tap → it extracts and downloads. This is the "Smart download" toggle in Settings.
5. **Multipart with 32 connections**: achieves 5× typical speeds for the median user, especially on high-latency CDNs.
6. **File cataloging by type**: the Files tab groups downloads into Music / Video / Document / Picture / Torrent / Zip categories, each with its own list view — gives users a sense of organization without needing a file manager.
7. **In-app browser keeps context**: the user never leaves 1DM, so the page session (cookies, login state) is preserved for re-sniffing. This is why 1DM "just works" where IDM-on-PC-style link-catchers don't.

---

## 2. Open-Source Stack Recommendation for Reverb's Download Module

| Concern | 1DM+ approach | Recommended for Reverb | Rationale |
|---|---|---|---|
| HTTP multi-part download | proprietary engine, 32 parts | **aria2c via `libaria2c.so`** (from `youtubedl-android:aria2c` artifact) OR **OkHttp with ranged requests** | aria2c is battle-proven on Android (Seal ships it), supports `-x16 -s16` parallelism, segment merging, retries, range/resume, all in C++; OkHttp is lighter but reimplements segment logic. **Decision: use aria2c for HTTP/MP4; OkHttp for tiny files and as the fallback when aria2c init fails.** |
| HLS (m3u8) | proprietary ts downloader + ffmpeg | **Media3 DownloadService (HlsMediaSource)** as default; **aria2c only for individual segments** if a torrent-style parallelism win is wanted | aria2 cannot parse m3u8 manifests natively (issue #1271, "please supports HLS" — closed wontfix). yt-dlp uses aria2 as a per-segment HTTP downloader via `--downloader libaria2c.so`, but the playlist parsing is still yt-dlp's. Media3 gives us HLS out of the box with adaptive bitrate, segment-level caching, and a foreground DownloadService. |
| DASH (.mpd) | proprietary + ffmpeg mux | **Media3 DashMediaSource DownloadService** + ffmpeg-kit mux when video/audio are separate adaptation sets | Same reasoning as HLS. |
| Torrent | proprietary libtorrent wrapper | **libtorrent4j** (fork of frostwire-jlibtorrent) — best Java/Kotlin libtorrent binding on Android | Out of scope for Reverb Phase 1; defer to Phase 3. |
| Download queue state machine | proprietary | **Steal Seal's `Task` sealed-class state machine verbatim** (Idle → FetchingInfo → ReadyWithInfo → Running → Completed/Canceled/Error) | See §4 for code sketch — it's the cleanest Compose-friendly design I've seen. |
| Queue UI (Material 3, Compose) | proprietary | **Steal Seal's `DownloadPageV2` + `VideoCardV2` + `ActionSheet`** | See §5 for the Composable structure. |
| Format/quality picker | proprietary | **Hybrid**: Seal's `FormatPage` (suggested format at top + collapsible "video+audio / video-only / audio-only" sections) for known sites; **inline radio group** (1DM-style) in the universal-detector sheet for arbitrary sites | Best of both worlds. |
| Foreground service | proprietary | **Media3 `DownloadService` (for HLS/DASH)** + **a custom `LifecycleService` for aria2c/OkHttp HTTP downloads** (mirroring Seal's `DownloadService.kt`) | Media3's DownloadService has built-in notifications, queue management, and lifecycle. We extend it. |
| Storage | proprietary | **SAF `MediaStore.Downloads` for shared; app-private for "hidden folder"** | Same as Seal/Aniyomi. |
| Notifications | proprietary | `NotificationCompat.Builder` + `MediaStyle` for active downloads, with Pause/Cancel/Restart actions | Mirrors Seal's `NotificationUtil`. |
| Retry on expired link | proprietary "refresh" feature | **"Re-sniff source URL" action**: re-run the universal extractor on the saved page URL → replace the download's URL field → restart | This is the 1DM killer feature; we can match it. |
| Clipboard monitor | proprietary | **`ClipboardManager.OnPrimaryClipChangedListener`** + a coroutine that pattern-matches URLs and shows a system notification | ~30 LOC. |
| Scheduler | proprietary (1DM+ only) | **`WorkManager` with network+charging constraints** | Defer to Phase 3. |

### 2a. aria2c vs OkHttp — honest verdict

**aria2c (`libaria2c.so`) wins for**:
- Single large MP4 over flaky/high-latency CDNs (the -x16 -s16 pattern: 16 connections, 16 splits, ~1 MB pieces).
- Resumable downloads where the server supports Range (aria2 auto-saves `.aria2` control files).
- Built-in retry with exponential backoff and piece-level checksums.

**aria2c costs**:
- Adds ~3 MB per ABI to the APK (the .so must ship per architecture: arm64-v8a, armeabi-v7a, x86_64).
- Adds a C++ native crash surface.
- Per-segment progress reporting is via aria2's stdin/stdout polling — clunkier than OkHttp's callback-per-byte.
- Cannot be used for HLS/DASH manifests directly.

**OkHttp with ranged requests wins for**:
- Small/medium files (<50 MB) — no win from parallelism, but no overhead either.
- Sites that don't support Range (aria2 will fall back to single-connection anyway).
- Tight integration with our existing OkHttp interceptor chain (Cloudflare, rate-limit, cookies).
- Pure Kotlin, zero native code, easier to debug.

**Recommendation: dual path.** Default routing:
- If URL ends in `.mp4` / `.webm` / `.mkv` / `.m4a` (progressive single-file) **AND** server supports Range **AND** file size estimate > 20 MB → **aria2c with -x16 -s16 -k1M**.
- Else (small file, no Range, or HLS/DASH master URL) → **OkHttp** (for HTTP) or **Media3 DownloadService** (for HLS/DASH).
- User can override in Settings: "Always use aria2c" / "Always use OkHttp" / "Auto".

### 2b. Embedding aria2c — three paths, ranked

1. **Reuse `youtubedl-android:aria2c` artifact** (`io.github.junkfood02.youtubedl-android:aria2c:0.18.1`). It ships `libaria2c.so` for all 4 ABIs, exposes `Aria2c.getInstance().init(context)`, and is what Seal uses. **Caveat**: the only documented way to invoke aria2c is via the yt-dlp `--downloader libaria2c.so` flag (yt-dlp forks aria2c internally). To use aria2c standalone for a non-yt-dlp URL we'd have to shell out to a CLI `aria2c` binary — `youtubedl-android` doesn't expose aria2's JSON-RPC directly. So this path forces us through yt-dlp even for plain MP4s, which is wasteful for the universal-extractor case.

2. **Bundle a standalone `aria2c` binary** (from Termux's prebuilt `libc++, c-ares, openssl, libxml2, zlib, libiconv` packages — see youtubedl-android's `BUILD_PYTHON.md`-equivalent) and invoke via `ProcessBuilder`. This is the **`devgianlu/aria2lib`** approach (used by Aria2Android/Aria2App): they ship the aria2c executable as an asset, extract it to `context.filesDir`, chmod +x, then `ProcessBuilder` it with `--enable-rpc --rpc-listen-port=6800`. We poll `http://localhost:6800/jsonrpc` for status. **Pros**: cleanest API (aria2's JSON-RPC is excellent); works for any URL. **Cons**: ~5 MB extra for the binary; needs per-ABI extraction logic; GPL-3.0 contamination if we ever ship the binary in a proprietary build (Reverb is Apache-2.0 core, but aria2 itself is GPL-2.0 — bundling its binary makes the app's combined work GPL, same caveat as yt-dlp).

3. **JNI binding** (write a thin `external "C" JNI` wrapper around aria2's C++ library, compile with NDK, ship as `.so`). Theoretically cleanest; in practice nobody has done this on Android — aria2's C++ API isn't designed for embedding. Skip.

**Decision**: For Reverb Phase 1, **use OkHttp with ranged requests** (zero new dependencies, zero native code, works on every URL the extractor finds). Add **aria2c via path #1 (`youtubedl-android:aria2c`)** in Phase 2 *only* when we wire up yt-dlp as the on-demand fallback for the long tail. Add **path #2 (standalone aria2c binary + JSON-RPC)** in Phase 3 if power users demand a real download manager engine for plain HTTP. This phased approach keeps the APK slim initially while preserving the upgrade path.

### 2c. aria2 command-line sketches (reference for when we wire it in)

Plain MP4 with 16 connections, 16 splits, 1 MB pieces:
```
aria2c \
  -x16 \                         # max connections per server
  -s16 \                         # split file into N segments
  -k1M \                         # min split size = 1 MB
  -j3 \                          # max concurrent downloads (per aria2c instance)
  --file-allocation=none \       # no pre-allocation on flash storage
  --continue=true \              # resume from .aria2 control file
  --max-tries=5 \                # retry on error
  --retry-wait=2 \               # seconds between retries
  --header="Referer: https://example.com/" \
  --header="User-Agent: Mozilla/5.0 ..." \
  --header="Cookie: cf_clearance=...; sess=..." \
  --dir="/sdcard/Download/Reverb" \
  --out="Big Buck Bunny.mp4" \
  --console-log-level=warn \
  --summary-interval=1 \         # emit JSON-RPC progress every 1s
  "https://cdn.example.com/bbb.mp4"
```

HLS via yt-dlp + aria2c (the Seal pattern):
```
yt-dlp \
  --downloader libaria2c.so \
  --concurrent-fragments 5 \
  --hls-use-mpegts \
  -f "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best" \
  --merge-output-format mp4 \
  -o "%(title)s.%(ext)s" \
  "https://example.com/master.m3u8"
```

(Note: `--downloader libaria2c.so` makes yt-dlp invoke aria2 for *each segment* of the HLS playlist — parallelism comes from `--concurrent-fragments`. This is the only way aria2 touches HLS today.)

---

## 3. Download Queue UI Patterns from Seal

Seal's `Task` is a sealed-state machine that's Compose-friendly and trivially persistable. **Verbatim source** (`app/src/main/java/com/junkfood/seal/download/Task.kt`):

```kotlin
@Serializable
data class Task(
    val url: String,
    val type: TypeInfo = TypeInfo.URL,
    val preferences: DownloadUtil.DownloadPreferences,
    val id: String = makeId(url, type, preferences),
) : Comparable<Task> {

    val timeCreated: Long = System.currentTimeMillis()

    @Serializable
    sealed interface TypeInfo {
        @Serializable data class Playlist(val index: Int = 0) : TypeInfo
        @Serializable data class CustomCommand(val template: CommandTemplate) : TypeInfo
        @Serializable data object URL : TypeInfo
    }

    @Serializable
    data class State(
        val downloadState: DownloadState,
        val videoInfo: VideoInfo?,
        val viewState: ViewState,
    )

    @Serializable
    sealed interface DownloadState : Comparable<DownloadState> {
        interface Cancelable { val job: Job; val taskId: String; val action: RestartableAction }
        interface Restartable { val action: RestartableAction }

        @Serializable data object Idle : DownloadState
        @Serializable data class FetchingInfo(@Transient override val job: Job = Job(), override val taskId: String) : DownloadState, Cancelable {
            override val action = RestartableAction.FetchInfo
        }
        @Serializable data object ReadyWithInfo : DownloadState
        @Serializable data class Running(@Transient override val job: Job = Job(), override val taskId: String, val progress: Float = -1f, val progressText: String = "") : DownloadState, Cancelable {
            override val action = RestartableAction.Download
        }
        @Serializable data class Canceled(override val action: RestartableAction, val progress: Float? = null) : DownloadState, Restartable
        @Serializable data class Error(@Transient val throwable: Throwable = Throwable(), override val action: RestartableAction) : DownloadState, Restartable
        @Serializable data class Completed(val filePath: String?) : DownloadState
        // ... ordering for priority queue: Running(0) < ReadyWithInfo(1) < FetchingInfo(2) < Idle(3) < Canceled(4) < Error(5) < Completed(6)
    }

    @Serializable
    data class ViewState(
        val url: String = "",
        val title: String = "",
        val uploader: String = "",
        val extractorKey: String = "",
        val duration: Int = 0,
        val fileSizeApprox: Double = .0,
        val thumbnailUrl: String? = null,
        val videoFormats: List<Format>? = null,
        val audioOnlyFormats: List<Format>? = null,
    )
}
```

Key invariants from `DownloaderV2Impl.kt`:

```kotlin
private const val MAX_CONCURRENCY = 3
private val taskStateMap = mutableStateMapOf<Task, Task.State>()
private val snapshotFlow = snapshotFlow { taskStateMap.toMap() }

// start/stop foreground service based on running count
snapshotFlow.map { it.countRunning() }.distinctUntilChanged()
    .collect { if (it > 0) App.startService() else App.stopService() }

// persist on every change (for crash/restart recovery)
snapshotFlow
    .map { it.filter { it.value.downloadState !is Completed } }
    .distinctUntilChanged()
    .collect { PreferenceUtil.encodeTaskListBackup(it) }

// scheduler: pick the highest-priority Idle/ReadyWithInfo task and run it
private fun doYourWork() {
    if (taskStateMap.countRunning() >= MAX_CONCURRENCY) return
    taskStateMap.entries
        .sortedBy { (_, state) -> state.downloadState }      // Running < Ready < Idle
        .firstOrNull { (_, state) ->
            state.downloadState == ReadyWithInfo || state.downloadState == Idle
        }
        ?.let { (task, state) ->
            when (state.downloadState) {
                Idle -> task.prepare()       // -> FetchingInfo -> ReadyWithInfo
                ReadyWithInfo -> task.download()  // -> Running -> Completed
                else -> throw IllegalStateException()
            }
        }
}
```

This is the **most elegant Android download-queue design I've seen**. SnapshotStateMap → Compose recomposition is automatic. MAX_CONCURRENCY=3 is the sweet spot for mobile (matches aniyomi's setting). The `@Transient val job: Job` makes the State serializable for backup while the live coroutine Job stays in memory. Cancellation is `YoutubeDL.destroyProcessById(taskId)` — we'd substitute `downloadJob.cancel()` for OkHttp and `aria2cProcess.destroyForcibly()` for aria2.

### 3a. Seal's queue Composable structure (the layout to copy)

From `DownloadPageV2.kt` (815 LOC) and `VideoCardV2.kt` (667 LOC):

```
DownloadPageV2 (Scaffold)
├── TopAppBar (title "Downloads", filter tabs: All / Downloading / Completed / Error)
├── LazyColumn (items = taskStateMap.entries.sortedBy { state priority })
│   └── VideoCardV2 (per task)
│       ├── Row
│       │   ├── Box (thumbnail, 16:9)
│       │   │   ├── AsyncImage(thumbnailUrl)
│       │   │   └── VideoInfoLabel(duration / fileSizeApprox, bottomEnd overlay)
│       │   └── Column (title, uploader, status)
│       │       ├── Text(title, maxLines=2)
│       │       ├── Text(uploader)
│       │       ├── StatusChip(downloadState)   // Running/Canceled/Error/Completed
│       │       └── LinearProgressIndicator(progress)  // only when Running
│       └── Row (action buttons, conditional on state)
│           ├── when Running  → FilledIconButton(Pause) + IconButton(Cancel)
│           ├── when Canceled → FilledIconButton(RestartAlt) + IconButton(Delete)
│           ├── when Error    → FilledIconButton(RestartAlt, error color) + IconButton(MoreVert)
│           └── when Completed→ FilledIconButton(PlayArrow) + IconButton(MoreVert)
├── ActionSheet (ModalBottomSheet, shown on card tap)
│   ├── VideoCardV2 (large, with thumbnail + stateIndicator overlay)
│   ├── Row (primary action buttons, 3 max)
│   │   ├── when Completed → PlayButton + ShareButton + (overflow)
│   │   ├── when Canceled  → ResumeButton + (overflow)
│   │   └── when Error     → ResumeButton + ErrorReportButton + (overflow)
│   └── LazyColumn (secondary actions: open file, copy URL, re-download, delete, show in folder)
└── FAB (Paste URL to download — opens InputUrlDialog)
```

Status badges and color tokens use Material 3 fixed-color roles (`LocalFixedColorRoles.current.primaryFixed`, etc.) so they look right in both light and dark themes. State transitions animate via `AnimatedContent` with `materialSharedAxisY`.

### 3b. The "video detected" sheet UX (1DM-style, our variant)

The 1DM sheet (§1c) is the universal-extractor output. For Reverb, the sheet Composable:

```kotlin
@Composable
fun DetectedStreamsSheet(
    streams: List<DetectedStream>,           // from universal extractor's shouldInterceptRequest
    sheetState: SheetState,
    onDownload: (DetectedStream, Quality?) -> Unit,
    onDismiss: () -> Unit,
) {
    SealModalBottomSheet(sheetState = sheetState, onDismissRequest = onDismiss) {
        LazyColumn {
            item { Text("Detected streams", style = M3.typography.titleLarge) }
            streams.forEach { stream ->
                item {
                    DetectedStreamCard(
                        stream = stream,
                        onDownload = { q -> onDownload(stream, q) },
                    )
                }
            }
        }
    }
}

@Composable
fun DetectedStreamCard(stream: DetectedStream, onDownload: (Quality?) -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text(stream.url, maxLines = 1, ellipsis = true)
        Text("${stream.format} · ${stream.contentType} · ${stream.contentSize?.toFileSizeText() ?: "unknown size"}")
        when (stream) {
            is DetectedStream.Progressive -> {
                Button(onClick = { onDownload(null) }) { Text("Download") }
            }
            is DetectedStream.HlsMaster -> {
                // inline radio group, 1DM-style
                val qualities by stream.qualitiesFlow.collectAsState()
                var selected by remember { mutableStateOf(qualities.best()) }
                qualities.forEach { q ->
                    Row(verticalAlignment = CenterVertically) {
                        RadioButton(selected = selected == q, onClick = { selected = q })
                        Text("${q.height}p · ${q.bandwidth.toBitrateText()}")
                    }
                }
                Button(onClick = { onDownload(selected) }) { Text("Download ${selected.height}p") }
            }
            is DetectedStream.DashManifest -> { /* similar */ }
        }
    }
}
```

When the user taps Download, we construct a `Task(url = stream.url, preferences = currentPrefs)` with the right `--downloader` flag based on the stream type, and `enqueue(task, state = State(Idle, null, ViewState(...)))`.

---

## 4. Integration with Reverb's Universal Extractor — Data Flow

The universal extractor (task 2-c, plan section §5) detects URLs by hooking `WebView.shouldInterceptRequest`. The download module consumes its output. Clean seam:

```
┌─────────────────────────────────┐         ┌─────────────────────────────────┐
│  :extractor (universal module)  │         │  :download (new module)         │
│                                 │         │                                 │
│  UniversalExtractor             │         │  DownloadQueue (Seal-style)     │
│   .webView: WebView             │         │   taskStateMap: SnapshotStateMap│
│   .shouldInterceptRequest(req) ─┼──flow──▶│   enqueue(Task)                 │
│        ↓                        │  List<  │        ↓                        │
│   matches regex                 │  Detected│   prepare()  ── fetchInfo() ──▶│  NewPipeExtractor
│   .*\\.(mp4|m3u8|mpd|ts)(\\?.*)?$│ Stream >│        ↓                        │  or yt-dlp fallback
│        ↓                        │         │   ReadyWithInfo                 │
│   emit DetectedStream(          │         │        ↓                        │
│     url, referer, ua, cookies,  │         │   download() ──▶  Aria2cRunner  │  -x16 -s16 for MP4
│     contentType, contentLength, │         │                  OR OkHttpRunner│  ranged for small
│     kind: Progressive/Hls/Dash, │         │                  OR Media3Service│  HLS/DASH
│     masterUrl, variants)        │         │        ↓                        │
│                                 │         │   Completed(filePath) → SAFF    │
└─────────────────────────────────┘         └─────────────────────────────────┘
```

The DetectedStream data class (the contract between modules):

```kotlin
@Serializable
sealed class DetectedStream {
    abstract val url: String
    abstract val referer: String?
    abstract val userAgent: String?
    abstract val cookies: String?            // "k1=v1; k2=v2"
    abstract val contentType: String?
    abstract val contentLength: Long?
    abstract val detectedAt: Long

    @Serializable data class Progressive(
        override val url: String, /* … */, val ext: String, // "mp4", "webm"
    ) : DetectedStream()

    @Serializable data class HlsMaster(
        override val url: String, /* … */,
        val variants: List<HlsVariant>, // pre-fetched by extractor
    ) : DetectedStream() {
        @Serializable data class HlsVariant(
            val bandwidth: Long, val resolution: String, val codecs: String?, val url: String,
        )
    }

    @Serializable data class DashManifest(
        override val url: String, /* … */,
        val videoRepresentations: List<DashRep>,
        val audioRepresentations: List<DashRep>,
    ) : DetectedStream()
}

// Bridge: enqueue a download from a detected stream
fun DetectedStream.toTask(prefs: DownloadPreferences): Task = when (this) {
    is DetectedStream.Progressive -> Task(
        url = url,
        preferences = prefs.copy(
            aria2c = contentLength?.let { it > 20 * 1024 * 1024 } ?: false,
            referer = referer, userAgent = userAgent, cookies = cookies,
        ),
    )
    is DetectedStream.HlsMaster -> Task(
        url = url,                                  // m3u8 URL
        preferences = prefs.copy(downloader = Downloader.Media3),
    )
    is DetectedStream.DashManifest -> Task(
        url = url,                                  // mpd URL
        preferences = prefs.copy(downloader = Downloader.Media3),
    )
}
```

The download module's `Downloader` interface (mirrors Seal's):

```kotlin
interface Downloader {
    val taskStateMap: SnapshotStateMap<Task, Task.State>
    fun enqueue(task: Task)
    fun cancel(task: Task): Boolean
    fun restart(task: Task)
    fun remove(task: Task): Boolean
}
```

Three implementations behind this interface, dispatched by `Task.preferences.downloader`:

- **`OkHttpDownloader`**: ranged GET with up to 8 parallel ranges, writes to a `RandomAccessFile`, merges on completion. Used for small MP4s and when aria2c init failed.
- **`Aria2cDownloader`**: shells out to `aria2c` (or calls yt-dlp with `--downloader libaria2c.so`). Used for large MP4s. Parses aria2's stdout for progress.
- **`Media3DownloadService` (bound)**: forwards `Task` to Media3's `DownloadHelper` → `DownloadRequest`. Used for HLS/DASH. Media3 owns its own queue; we mirror its state into our `taskStateMap` via a `Download.Listener`.

The download module's foreground service is **`ReverbDownloadService : Media3 DownloadService`** (subclass) — this gives us Media3's automatic foreground promotion, notification management, and network constraint handling for free, for the HLS/DASH path. The OkHttp/aria2c paths run inside the same service as coroutines, so there's one foreground service total.

---

## 5. The "Share to Reverb" Intent

This is the single most important intent-filter in the manifest. Seal's manifest (`app/src/main/AndroidManifest.xml`) is the reference: it has **two activities** with the share filter, one full (`MainActivity`) and one dialog-only (`QuickDownloadActivity`). The dialog-only activity is the magic — it lets the user share a URL from Chrome → see a tiny dialog → tap "Download" → return to Chrome, never leaving the share-target app's context for more than 2 seconds.

**Recommended `AndroidManifest.xml` for Reverb** (verbatim, drop-in):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="29" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- So we can query Chrome / Firefox / Edge share intents on Android 11+ -->
    <queries>
        <intent>
            <action android:name="android.intent.action.SEND" />
            <data android:mimeType="text/plain" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.BROWSABLE" />
            <data android:scheme="https" />
        </intent>
    </queries>

    <application
        android:name=".ReverbApp"
        android:allowBackup="true"
        android:enableOnBackInvokedCallback="true"
        android:extractNativeLibs="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:requestLegacyExternalStorage="true"
        android:supportsRtl="true"
        android:theme="@style/Theme.Reverb"
        tools:targetApi="tiramisu">

        <!-- Main launcher activity -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:theme="@style/Theme.Reverb">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- Share-text from any browser/app: "Share to Reverb" -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>

            <!-- Open http/https video/audio URLs with Reverb (Open with…) -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="http" />
                <data android:scheme="https" />
                <data android:mimeType="video/*" />
                <data android:mimeType="audio/*" />
            </intent-filter>
        </activity>

        <!-- Lightweight dialog activity for fast share-to-download.
             Opens in a separate task so Chrome stays alive underneath. -->
        <activity
            android:name=".QuickDownloadActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:launchMode="singleInstance"
            android:theme="@style/Theme.Reverb.Dialog">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="http" />
                <data android:scheme="https" />
                <data android:mimeType="video/*" />
                <data android:mimeType="audio/*" />
            </intent-filter>
        </activity>

        <!-- Media3 download service (HLS/DASH) — also hosts aria2c/OkHttp coroutines -->
        <service
            android:name=".download.ReverbDownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync">
            <!-- Media3 DownloadService expects this filter so it can be started from DownloadService.buildIntent -->
            <intent-filter>
                <action android:name="androidx.media3.exoplayer.downloadService.action.RESTART" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </service>

        <receiver
            android:name=".download.NotificationActionReceiver"
            android:exported="false" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.provider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/provider_paths" />
        </provider>
    </application>
</manifest>
```

### 5a. The `QuickDownloadActivity` flow

```kotlin
class QuickDownloadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrlFromIntent(intent)
            ?: run { finish(); return }

        setContent {
            ReverbTheme {
                QuickDownloadDialog(
                    url = url,
                    onConfirm = {
                        lifecycleScope.launch {
                            // Fast path: enqueue + immediately close; user sees the result in the notification
                            Downloader.enqueue(Task(url = url, preferences = prefs))
                            finish()
                        }
                    },
                    onCustomize = {
                        // Slow path: open MainActivity with the URL pre-filled so user can pick format
                        startActivity(
                            Intent(this@QuickDownloadActivity, MainActivity::class.java)
                                .putExtra("url", url)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun extractUrlFromIntent(intent: Intent): String? {
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { findFirstUrl(it) }
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
    }
}
```

UI: a 200dp-tall dialog with the URL, a "Download" primary button, a "Customize format" secondary button, and a Cancel. Default behavior is one tap → download → close. This matches Seal's `QuickDownloadActivity.kt` exactly.

### 5b. Clipboard monitor (the "Smart download" toggle)

```kotlin
class ClipboardUrlMonitor : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cm = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        val url = findFirstUrl(text) ?: return
        // Show a notification: "Download this URL?" with action "Download"
        NotificationUtil.showClipboardDetectedNotification(context, url)
    }
}

// Register in ReverbApp.onCreate via:
context.registerReceiver(
    ClipboardUrlMonitor(),
    IntentFilter(ClipboardManager.ACTION_CLIPBOARD_CHANGED),
)
// Gate behind Settings.getBoolean("smart_download", false)
```

---

## 6. Honest Limits — What We Can Match, What We Can't

### What 1DM+ does that we **should match** (all feasible for Reverb):

| 1DM+ feature | Reverb implementation | Effort |
|---|---|---|
| In-app WebView browser with video sniffing | `:browser` module + `shouldInterceptRequest` regex | M (already in plan) |
| Multi-part HTTP up to 32 connections | aria2c via `youtubedl-android:aria2c` (default 16; configurable to 32) | S (one Gradle dep + init) |
| m3u8 / m3u / MP-DASH download | Media3 `HlsMediaSource` / `DashMediaSource` DownloadService | M (Media3 is already in stack) |
| Auto-convert .ts → .mp4 | ffmpeg-kit `-c copy -f mp4` (no re-encode) | S |
| Pause/resume with error handling | Seal's `Task` state machine + aria2's `.aria2` control files | M |
| Refresh expired links | "Re-sniff source URL" action — re-run universal extractor on saved page URL | S |
| Scheduler (1DM+ only) | WorkManager with network+charging constraints | M (Phase 3) |
| Website grabber (batch download all media on a page) | Iterate `DetectedStream` list, enqueue all | S |
| Hidden folder downloads | App-private `filesDir/hidden/` with .nomedia | S |
| File cataloging by type | MediaStore collection queries + tabs in Files UI | S |
| Clipboard monitor ("Smart download") | `ClipboardManager.OnPrimaryClipChangedListener` + notification | S |
| Import download links from text file/clipboard | `ACTION_OPEN_DOCUMENT` + line-by-line enqueue | S |
| Multi-tab browser with bookmarks/history/incognito | WebView tab manager + Room for bookmarks/history | M |
| Adblock + popup blocker | EasyList rules in `shouldInterceptRequest` (like SuperX's Rust ad-blocker) | M |
| Share-to-Reverb intent | §5 manifest + `QuickDownloadActivity` | S |
| Foreground notification with pause/cancel | `NotificationCompat.Builder` + `MediaStyle` | S |

### What 1DM+ does that we **cannot easily match** (or shouldn't try in Phase 1):

1. **YouTube downloads.** 1DM+ explicitly refuses YouTube ("due to their terms of service"). NewPipeExtractor *can* do it, but it's a legal gray zone and Google Play will reject the app. **Decision: support YouTube via NewPipeExtractor in the F-Droid build; disable in the Play Store build via a BuildConfig flag.** Same approach as Aniyomi.
2. **Members-only / DRM content.** Neither 1DM+ nor any open-source tool can download Widevine-protected streams (Netflix, Disney+, paid Vimeo, etc.). The Play Store EME/CDM is gated behind a per-app license we won't have. **Don't try.**
3. **Cloudflare Turnstile with interstitial solving.** 1DM+ uses a proprietary WebView JS solver. Aniyomi's `lib/cloudflareinterceptor` WebView solver is the open-source equivalent and is what we'll port — but it's not 100% reliable against the latest Turnstile variants. **Accept 70–90% success rate; show a "could not bypass Cloudflare" error gracefully.**
4. **Per-segment parallel HLS download with aria2c (the -x16 pattern applied to HLS).** yt-dlp's `--concurrent-fragments 5` does this, but only via the yt-dlp path. Media3's HLS downloader is sequential per rendition. For Reverb: live with Media3's speed unless the user opts into the yt-dlp path (Phase 2).
5. **Pre-built binary distribution on Google Play.** Play's "USB App" policy and the new "Personal App" policy restrict bundling executable binaries. `youtubedl-android` ships yt-dlp + Python + ffmpeg + aria2c as native libs / assets — Play tolerates it for now but the policy is volatile. **F-Droid build is unrestricted.** Plan for divergent builds.
6. **Truly "any site, any video, no exceptions."** 1DM+ users on Reddit do report failures on obfuscated players (Dood, StreamWish, etc. — the exact hard cases task 2-b documented). The proprietary app's edge is per-site extractors updated silently. **Our universal sniffer + per-site modules will match for ~80% of sites; the long tail needs yt-dlp fallback.**

### What Reverb can do that **1DM+ cannot** (our differentiators):

- **Fully open-source, GPL-friendly, F-Droid-listed.** No ads, no telemetry, no hidden SDKs.
- **Per-site extractors pluggable at runtime** (the `:extensions` module from task 2-a/2-b's design — Tachiyomi-style).
- **yt-dlp backend for 1800+ sites** (1DM+ refuses YouTube; we offer it in F-Droid builds).
- **A proper player.** 1DM+'s player is a basic ExoPlayer wrapper; Reverb inherits Reverb's media player (Media3 + chapter markers + subtitle styling + background audio).
- **Material 3 + dynamic color + Compose UI** (1DM+ is view-based, dated).
- **Cloudflare bypass via WebView cookie-polling** (1DM+'s solver is opaque; ours is inspectable).

---

## 7. Concrete Recommendations for PLAN.md

Update Reverb's PLAN.md (Phase 1) to bake in these decisions:

1. **Add `:download` Gradle module** with these files (mirroring Seal's structure):
   - `download/Task.kt` (sealed-state machine, verbatim from Seal with our `DetectedStream` integration)
   - `download/Downloader.kt` (interface + Impl with MAX_CONCURRENCY=3, SnapshotStateMap, foreground service start/stop on running count)
   - `download/OkHttpDownloader.kt` (ranged GET, 8 parallel ranges, RandomAccessFile merge)
   - `download/Aria2cDownloader.kt` (Phase 2; stub in Phase 1 returning "not implemented")
   - `download/ReverbDownloadService.kt` (extends `androidx.media3.exoplayer.download.DownloadService`, hosts HLS/DASH + OkHttp coroutines)
   - `download/NotificationActionReceiver.kt` (Pause/Cancel/Restart intents)
   - `download/ClipboardUrlMonitor.kt` (the "Smart download" feature)

2. **Manifest additions** — copy §5's intent-filters verbatim. Add `QuickDownloadActivity` to the manifest and implement it (40 LOC).

3. **Wire the universal extractor → download module seam** — define `DetectedStream` in `:extractor:api` (shared KMP module), consume in `:download`. The `DetectedStreamsSheet` Composable lives in `:browser` and calls `Downloader.enqueue(detectedStream.toTask(prefs))`.

4. **Phase 1 download backend**: OkHttp-only (no native code). **Phase 2**: add `youtubedl-android:library:ffmpeg:aria2c` deps, wire yt-dlp as long-tail extractor and aria2c as fast-path HTTP downloader for >20 MB MP4s. **Phase 3**: standalone aria2c binary + JSON-RPC for power users; WorkManager scheduler; libtorrent4j for torrents.

5. **Quality-of-life parity with 1DM+** — implement these before launch (each is <1 day):
   - Clipboard URL monitor + notification
   - "Refresh expired link" action on every download row
   - Website grabber: "Download all detected" button on the DetectedStreamsSheet
   - File cataloging tab in the Files UI (Music / Video / Document / Picture / Torrent)
   - Hidden folder toggle in Settings

6. **Honesty in the Play Store listing** — copy 1DM+'s disclaimer: "Downloading from YouTube is not supported due to their terms of service" (in the Play build only). Add: "Reverb cannot download DRM-protected content from streaming services like Netflix, Disney+, or paid Vimeo."

---

## 8. Key Code Sketches (consolidated)

### 8a. aria2c HLS download via yt-dlp (Phase 2)

```kotlin
// download/Aria2cDownloader.kt
suspend fun downloadHls(
    masterUrl: String,
    outputDir: File,
    cookiesFile: File?,
    referer: String?,
    onProgress: (Float, String) -> Unit,
): Result<File> = withContext(Dispatchers.IO) {
    val request = YoutubeDLRequest(masterUrl).apply {
        addOption("--downloader", "libaria2c.so")
        addOption("--concurrent-fragments", 5)
        addOption("--hls-use-mpegts")            // faster, no ffmpeg for HLS-only
        addOption("--merge-output-format", "mp4")
        addOption("-o", "${outputDir.absolutePath}/%(title)s.%(ext)s")
        addOption("--no-mtime")
        if (cookiesFile != null) addOption("--cookies", cookiesFile.absolutePath)
        if (referer != null) addOption("--referer", referer)
    }
    YoutubeDL.getInstance().execute(request, object : YoutubeDL.Callback {
        override fun onProgress(progress: Float, eta: Long, line: String) =
            onProgress(progress / 100f, line)
    }).let { result ->
        if (result.exitCode == 0) Result.success(File(result.outPath))
        else Result.failure(RuntimeException(result.errOut))
    }
}
```

### 8b. aria2c direct HTTP (Phase 3, via standalone binary + JSON-RPC)

```kotlin
// download/Aria2cStandaloneRunner.kt
class Aria2cStandaloneRunner(context: Context) {
    private val binary = extractBinary(context, "aria2c")   // from assets/aria2c/<abi>/aria2c
    private val port = (50000..60000).random()
    private val secret = UUID.randomUUID().toString()
    private var process: Process? = null

    fun start() {
        process = ProcessBuilder(
            binary.absolutePath,
            "--enable-rpc", "--rpc-listen-port=$port",
            "--rpc-secret=$secret",
            "--rpc-allow-origin-all",
            "--dir=${context.filesDir}/aria2",
            "--max-concurrent-downloads=3",
            "--max-connection-per-server=16",
            "--split=16",
            "--min-split-size=1M",
            "--continue=true",
            "--file-allocation=none",
            "--console-log-level=warn",
        ).redirectErrorStream(true).start()
    }

    suspend fun enqueue(task: Task): String = jsonRpc("aria2.addUri", listOf(
        listOf(task.url),
        buildMap {
            task.preferences.referer?.let { put("referer", it) }
            task.preferences.userAgent?.let { put("user-agent", it) }
            task.preferences.cookies?.let { put("header", listOf("Cookie: $it")) }
            put("dir", task.preferences.outputDir)
            put("out", task.preferences.outputFilename)
            put("max-connection-per-server", "16")
            put("split", "16")
        },
    )).getString("result")

    suspend fun pollProgress(gid: String): Progress = jsonRpc("aria2.tellStatus", listOf(gid)).let { r ->
        val s = r.getJSONObject("result")
        Progress(
            completed = s.optLong("completedLength"),
            total = s.optLong("totalLength"),
            speed = s.optLong("downloadSpeed"),
            status = s.getString("status"),  // active/waiting/paused/complete/error/removed
        )
    }
}
```

### 8c. Detected-stream sheet (1DM-style inline quality picker)

```kotlin
@Composable
fun DetectedStreamsSheet(
    streams: StateFlow<List<DetectedStream>>,
    onDownload: (DetectedStream, Any?) -> Unit,
    onDismiss: () -> Unit,
) {
    val list by streams.collectAsState()
    SealModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("Detected streams", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(list) { stream ->
                    when (stream) {
                        is DetectedStream.Progressive -> ProgressiveCard(stream, onDownload)
                        is DetectedStream.HlsMaster -> HlsCard(stream, onDownload)
                        is DetectedStream.DashManifest -> DashCard(stream, onDownload)
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = {
                    list.forEach { onDownload(it, null) }   // "Download all" — the Website Grabber feature
                }) { Text("Download all ${list.size}") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
```

### 8d. The queue Composable skeleton (Seal-derived)

```kotlin
@Composable
fun DownloadQueueScreen(
    downloader: Downloader,
    onOpenFile: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val tasks = downloader.taskStateMap.toList().collectAsState(emptyList())
    var filter by remember { mutableStateOf(Filter.All) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    FilterChipGroup(
                        selected = filter,
                        onSelected = { filter = it },
                        options = listOf(Filter.All, Filter.Active, Filter.Completed, Filter.Error),
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* show InputUrlDialog */ }) {
                Icon(Icons.Rounded.ContentPaste, "Paste URL")
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            val visible = tasks.value
                .filter { filter.matches(it.value.downloadState) }
                .sortedBy { it.value.downloadState }   // Running first
            items(visible) { (task, state) ->
                VideoCardV2(
                    viewState = state.viewState,
                    stateIndicator = { StateChip(state.downloadState) },
                    actionButton = { ActionButton(state.downloadState,
                        onPause = { downloader.cancel(task) },
                        onResume = { downloader.restart(task) },
                        onCancel = { downloader.cancel(task); downloader.remove(task) },
                        onPlay = { onOpenFile((state.downloadState as Completed).filePath!!) },
                    ) },
                    onButtonClick = { /* show ActionSheet */ },
                )
                HorizontalDivider()
            }
        }
    }
}
```

---

## 9. Sources

- 1DM Play Store listing: `https://play.google.com/store/apps/details?id=idm.internet.download.manager`
- 1DM+ Play Store listing: `https://play.google.com/store/apps/details?id=idm.internet.download.manager.plus`
- Reddit user reviews: r/software "1DM alternative for PC" thread, r/androidapps "thoughts on 1DM browser" thread
- Seal repo: `https://github.com/JunkFood02/Seal` (27.5k stars, GPL-3.0, Kotlin, Compose)
- Seal download engine sources fetched: `download/Task.kt` (168 LOC), `download/DownloaderV2.kt` (453 LOC), `util/DownloadUtil.kt` (1005 LOC), `ui/page/downloadv2/{DownloadPageV2,VideoCardV2,ActionSheet}.kt`, `ui/page/downloadv2/configure/{DownloadDialogV2,FormatPage}.kt`, `AndroidManifest.xml`
- youtubedl-android: `https://github.com/yausername/youtubedl-android` — README confirms `io.github.junkfood02.youtubedl-android:aria2c` artifact and `--downloader libaria2c.so` invocation
- aria2: `https://github.com/aria2/aria2` — README confirms scope (HTTP(S)/FTP/SFTP/BT/Metalink only, NO HLS); issue #1271 ("please supports HLS") confirms HLS is wontfix
- aria2lib (Android executable runner): `https://github.com/devgianlu/aria2lib` (44 stars, Apache-2.0, used by Aria2Android/Aria2App)
- Motrix: `https://github.com/agalwood/Motrix` (Electron + aria2 frontend, desktop reference)
- uGet for Android: SourceForge `urlget` project, last release 1.5.3, effectively abandoned (Android 4.0.3+ only). Skip.
- SuperX Video Downloader (`alexch33/super-video-downloader`, GPL-3.0, F-Droid): open-source 1DM+ clone with WebView + M3U8/DASH interception + Rust ad-blocker. **The closest OSS analog to 1DM+ and a great reference for the `:browser` module.**
- NewPipe DownloadDialog.java (1127 LOC): the format-picker dialog pattern (RadioGroup Video/Audio/Subtitle → Spinner per category)
- Task 2-a (aniyomi) and 2-c (universal extractor) worklog entries informed the integration design
