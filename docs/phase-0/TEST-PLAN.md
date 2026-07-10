# Phase 0 — Spike Test Plan (the 10-site gate)

> **Gate criterion:** ≥9/10 of these sites must yield a playable, ad-free stream URL when
> entered into the Phase 0 spike app. If yes → greenlight Phase 1.
> Reference: PLAN.md §8 + §17.3.

## How to run the test

1. Install the Phase 0 debug APK (from GitHub Actions artifacts, or build locally).
2. For each site in the table below, open the spike app, enter the URL, and tap the play arrow.
3. Wait up to 15 seconds (the extractor timeout).
4. Record: did a stream get detected? What format (HLS/DASH/MP4)? How many qualities?
   Were ads blocked (check the shield badge count)? Any CF challenge?
5. If a stream is detected → ✅. If not → ❌ + note why.

## The 10 sites (from PLAN.md §17)

### High-confidence (expect pass — 6 sites)

| # | Site | URL to test | Expected |
|---|---|---|---|
| 1 | anidb.app | `https://anidb.app/home` → pick an anime → pick an episode | ✅ m3u8 directly in embed-page JS |
| 2 | anizone.to | `https://anizone.to/` → pick an anime → pick an episode | ✅ m3u8 in `<media-player src>` |
| 3 | anikototv.to | `https://anikototv.to/home` → pick an anime → pick an episode | ✅ via AnikotoTheme iframe extraction (JS exec) |
| 4 | animepahe.pw | `https://animepahe.pw/` → pick an anime → pick an episode | ✅ Kwik packed JS runs in WebView, video request intercepted |
| 5 | miruro.to | `https://www.miruro.to/` → pick an anime → pick an episode | ⚠️ CF challenge likely; if solver works → ✅ via secure-pipe |
| 6 | aniwave.to | `https://aniwave.to/` → pick an anime → pick an episode | ✅ (11th sanity check — AnikotoTheme, known-good) |

### Medium (need theme module first — but extractor v2 should handle — 3 sites)

| # | Site | URL to test | Expected |
|---|---|---|---|
| 7 | animekhor.org | `https://animekhor.org/` → pick an anime → pick an episode | ⚠️ CF challenge + base64 mirror options; JS exec + response-body scan should catch it |
| 8 | animexin.dev | `https://animexin.dev/` → pick an anime → pick an episode | ✅ same theme, no CF challenge |
| 9 | donghuastream.org | `https://donghuastream.org/` → pick an anime → pick an episode | ✅ same theme |

### Hard (defer — 2 sites, but extractor v2 + login-wall should help)

| # | Site | URL to test | Expected |
|---|---|---|---|
| 10 | mkissa.to | `https://mkissa.to/anime` → pick an anime → pick an episode | ⚠️ AllAnime AES-GCM + XOR; response-body scan should catch the decrypted URL |
| 11 | reanime.to | `https://reanime.to/home` → pick an anime → pick an episode | ❌ likely needs login (login-wall detection should trigger; one-time login then ✅) |

## Pass criteria

- **Green (proceed to Phase 1):** ≥9 of 11 ✅.
- **Yellow (proceed with fixes):** 7–8 of 11 ✅ — investigate the failures, patch the extractor, re-test.
- **Red (re-evaluate):** <7 of 11 ✅ — the universal-extractor approach needs rethinking.

## Known failure modes to document

When a site fails, note which of these it hit:
- [ ] CF challenge not solved (Phase 0 ships the no-op solver — expected for miruro/animekhor)
- [ ] No video request detected (video loaded via a mechanism the extractor doesn't catch)
- [ ] Login wall (reanime)
- [ ] DRM (Widevine — playback works but download won't)
- [ ] Timeout (page too slow / JS too heavy)

## What the extractor SHOULD catch (the 5 capabilities)

For each ✅, note which capability did the work:
1. `shouldInterceptRequest` — the video URL came through as a normal network request
2. `response-body-scan` — the video URL was found inside an XHR/fetch response body
3. `blob` — the video was assembled via `URL.createObjectURL` and caught by the JS hook
4. `js-exec` — the site's obfuscated JS ran in the WebView and made a video request we intercepted
5. `login-wall` — the site required login; after one-time auth, the stream was detected

This tells us which capabilities are load-bearing and which are rarely needed.
