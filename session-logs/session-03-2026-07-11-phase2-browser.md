# Session 03 — 2026-07-11 — Phase 2: in-app website browser (CI GREEN)

## Problem
User reported: "the app is not functioning properly. I opened a website and it was unable to navigate that website. I entered the URL and it tried to extract and it failed."

Root cause (from logcat analysis):
1. The user entered `https://anikoto.cz` (the HOMEPAGE). Phase 1 only tried to extract video streams — but there's no video on a homepage. It timed out after 15s with "No streams captured."
2. The app didn't rebuild the webpage UI for navigation at all — it only extracted video.
3. The rate limiter was blocking `cloudflare-dns.com` (DoH), causing page loads to take 7-15s.

## What was done

### 1. Fixed rate limiter (DoH exemption)
`RateLimitInterceptor` now exempts infrastructure hosts (`cloudflare-dns.com`, `dns.google`, `dns.quad9.net`, `dns.adguard.com`). Page loads are now fast.

### 2. Built the in-app website browser (the core UX the user requested)
New files:
- `app/ui/browser/WebsiteBrowser.kt` — embedded WebView showing the ACTUAL website with:
  - Ad-blocking (shouldInterceptRequest blocks ads, never blocks video URLs — the extractor-before-blocker contract)
  - Cosmetic CSS injection (hides ad placeholders via `##` selectors)
  - Video URL detection (captures .m3u8/.mp4/.mpd as the user navigates the site)
  - Back/Forward/Reload navigation buttons
  - Progress bar
  - "Ads blocked" counter in the bottom bar
  - "Play (N)" FAB when video is detected → opens DetectedStreamsSheet
- `app/ui/browser/WebsiteBrowserViewModel.kt` — browser state + stream detection + history recording
- `app/ui/browser/DetectedStreamsSheet.kt` — 1DM+-style bottom sheet with quality cards (Play / Download / Copy)

### 3. Rewrote BrowseScreen
Now has two modes:
- **Home mode**: address bar + quick-sites chips (anidb, anizone, anikototv, animepahe, miruro, donghuastream) + recent history. User enters a URL → opens the WebsiteBrowser.
- **Browser mode**: the WebsiteBrowser takes over the full screen.

### 4. Navigation flow (what the user wanted)
User enters a site URL → the site loads in the embedded WebView (with ads blocked) → user browses the site normally (home → anime → episode) → when they land on a page with video, Reverb detects the stream and shows a "Play (N)" FAB → tapping it opens the detected-streams sheet with quality options → user can Play or Download.

This is exactly: "extract a web page and recreate it so the user can click buttons and navigate, but the UI will be simplified and made good."

## CI
- Run 29138253499: ✅ SUCCESS — APK 29.4 MB, all 14 steps green.
- 2 iterations: (1) `app.reverb.data.DownloadTask` was parsed as `ReverbApp.reverb.data.*` (app is the instance, not the package) → fixed with imports; (2) ✅ green.

## What's next
- Test on device: enter a site, navigate to an episode, verify the "Play (N)" FAB appears, verify playback works.
- Phase 2 continued: the LLM-assisted site analyzer (auto-generate native UI from HTML) — this is the enhancement ON TOP of the browser. The browser is the foundation; the native rebuilt UI replaces the WebView for known sites.
