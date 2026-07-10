# Reverb — Project Memory

> Long-term context the AI maintains across sessions. Read this first on every resume.
> Last updated: 2026-07-10 (planning v3.0 complete, build not yet started)

## One-paragraph summary

**Reverb** is an Android app (Kotlin + Jetpack Compose + Material 3 Expressive) that acts as a browser-overlay: it analyzes any website via scraping, rebuilds its UI natively, blocks ads, detects every video stream, lets the user download them, and translates content — fully automated via an LLM-assisted site analyzer. Think Aniyomi + NewPipe + 1DM+ + an LLM site analyzer, in one app.

## Repo

- **URL:** https://github.com/testplay-byte/Reverb
- **Default branch:** `main`
- **License:** Apache-2.0 (app core); `:source-newpipe` is GPL-3.0 isolated as a dynamic feature
- **Working clone:** `/home/z/reverb-build/Reverb`

## Current state (as of this writing)

- **Phase:** Phase 0 spike BUILT + CI GREEN. APK available as GitHub Actions artifact (25.6 MB).
- **Plan:** `docs/PLAN.md` (27 sections, ~1230 lines) — the master blueprint.
- **Research:** `docs/research/` has 7 deep reports + raw HTML captures of 10 sites.
- **Phase 0 app:** 8 Gradle modules (`:app`, `:core:{common,network,html,video}`, `:source-api`, `:source-universal`, `:adblock`). Enhanced Universal Extractor v2 + ad-blocker contract + green M3 Expressive UI. CI builds successfully on push. Unit tests pass (including the 13-case ad-blocker contract test).
- **NOT yet done:** The 10-site gate test (needs a real Android device — can't run in the build sandbox). The Cloudflare WebView solver is a stub.
- **Live preview:** `/home/z/my-project/` runs a Next.js viewer for the plan (16 sections including an end-to-end flow diagram). Not part of the Android app.

## The 5-phase roadmap (from PLAN.md §26)

0. **Phase 0 — Spike (2 weeks, gate: ≥9/10 sites):** throwaway app with enhanced universal extractor v2 + ad-blocker contract. Test on the 10 analyzed sites.
1. **Phase 1 — MVP (7 weeks):** `:core:*` + `:source-universal-v2` + `:source-builtin` (theme modules) + `:player` (Media3) + `:download` + `:data` + `:ui` + `:adblock` (Kotlin matcher) + translation (ML Kit) + 17-language i18n.
2. **Phase 2 — Public Beta (6 weeks):** `:feature-autolearn` (LLM site analyzer — the differentiator) + `:feature-ondevice-llm` (Gemma 3 1B via LiteRT-LM) + Brave-rust ad-block swap + `:feature-ytdlp` dynamic feature + DeepL + TranslateGemma.
3. **Phase 3 — v1.x:** extension APK system + community LearnedSiteConfig sharing + opt-in VPN ad-block.
4. **Phase 4 — v2:** KMP desktop port.

## The tech stack (verified mid-2026 — see `docs/research/01-a-tech-stack-spec.md`)

- **Kotlin 2.2.21** (NOT 2.4.0 — Voyager hasn't shipped 2.4.x yet)
- **Compose BOM 2026.06.01** + **Material 3 Expressive**
- **Voyager 2.2.21-1.10.3** (navigation)
- **Hilt 2.60.1** (KSP only, no KAPT)
- **OkHttp 5.4.0 stable** (not alpha)
- **Jsoup 1.22.2**, **dokar3/quickjs-kt 1.0.5** (NOT app.cash.quickjs — it's dead)
- **Media3 1.10.1** (ExoPlayer + DownloadService)
- **Room 2.8.4**, **DataStore 1.2.1**
- **io.github.junkfood02.youtubedl-android:0.18.1** — bundles yt-dlp + FFmpeg + aria2c (FFmpegKit is DEAD, this is our FFmpeg source)
- **Brave adblock-rust 0.13.0** via cargo-ndk (JNI, ~3 MB/ABI, MPL-2.0)
- **com.google.ai.edge.litertlm:0.14.0** + Gemma 3 1B-IT Q4 (584 MB, on-demand)
- **ML Kit Translate 17.0.3** + Language ID 17.0.6
- **JDK 21 LTS, Gradle 9.6.1, AGP 9.2.1, Min SDK 24, Target SDK 36**

## The two critical mid-2026 stack corrections (don't forget)

1. **FFmpegKit is archived (Apr 2025).** Use `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` instead.
2. **`app.cash.quickjs:quickjs-android` is 5 years stale.** Use `io.github.dokar3:quickjs-kt:1.0.5` instead.

## The 4-layer full-automation engine (PLAN.md §23)

1. **Enhanced Universal Extractor v2** — WebView + JS exec + interaction sim + shouldInterceptRequest + response-body scan + blob interception + login-wall detection. Handles VIDEO for any site.
2. **LLM-Assisted Site Analyzer** — fetch HTML → simplify (97KB→2.6KB) → prompt LLM → validate selectors via Jsoup → retry 3× → store LearnedSiteConfig. Handles UI rebuild. Remote-first (Groq llama-3.1-8b-instant, 95-98%) + on-device fallback (Gemma 3 1B, 90%) + manual Learn Mode (100%).
3. **LearnedSite interpreter** — generic Site impl driven by the JSON config.
4. **Fallbacks** — cache, manual, theme modules (optional), NewPipe.

**Result:** every one of the 10 analyzed sites is full-auto. Hand-written SiteModules = 0.

## The ad-blocker contract (PLAN.md §16.4) — THE critical correctness invariant

The ad-blocker and the universal extractor both want `WebView.shouldInterceptRequest`. Three layers of defense so the ad-blocker never swallows a video URL:
1. **Ordering:** extractor's video regex runs FIRST; if it matches, skip the blocker.
2. **MIME/extension allowlist inside AdMatcher:** never block video/*, audio/*, MPEGURL, DASH, or the video file extensions.
3. **Parse-time rule filtering:** drop `$media` rules targeting trusted video CDN hostnames.
+ Release-gate Robolectric/Espresso test that fails the build if any video URL in the test set gets blocked.

## The 10 analyzed sites (PLAN.md §17) — all now full-auto

anikototv.to, animepahe.pw, mkissa.to, reanime.to, miruro.to, anidb.app, anizone.to, animekhor.org, animexin.dev, donghuastream.org. Raw HTML captures in `docs/research/sites-batch1/`. 3 share the "animestream" WordPress theme (animekhor/animexin/donghuastream) — one theme module covers all three.

## The translation layer (PLAN.md §24)

- **App UI i18n:** plain `strings.xml` (NOT Moko — KMP overhead for single-platform) + AppCompat per-app language API. 17 languages at v1.
- **Content translation 6-tier fallback:** AniList GraphQL (free win for ~70% of mainstream anime) → Room cache (SHA-256 key) → ML Kit Translate (on-device, free) → DeepL (optional paid) → TranslateGemma 2B (on-device LLM) → original + badge.
- **Aniyomi does NOT translate content** — Reverb doing this is a feature lead.

## Design language

**Material 3 Expressive** — the latest M3 variant (2025/2026). Bold typography, expressive motion, dynamic color, large rounded shapes, personality-driven. The user explicitly asked for this. Reference: Material 3 Expressive guidelines + Aniyomi's Compose UI as the structural baseline (but more expressive).

## Distribution (NOT Play Store)

F-Droid + GitHub Releases + direct APK. Play Store is a non-goal (policy risk for video-downloaders). See PLAN.md §11 + R4.

## Open questions for the user (PLAN.md §10 + §24.4)

- Q1: TranslateGemma as additive model, or reuse general Gemma 3 1B? *Recommendation: additive.*
- Q2: Prefer site's `lang` hint or always ML Kit Language ID? *Recommendation: prefer hint.*
- Q3: Skip ML Kit when AniList provides target-language title? *Recommendation: yes.*
- Q4: App name confirmation — "Reverb" is the working name. OK to keep?
- Q5: App icon — needs design. Any preferences?
- Q6: Default remote LLM endpoint — Groq (free, fast) as default, or another?

## How to resume (if environment is lost)

```bash
git clone https://github.com/testplay-byte/Reverb.git /home/z/reverb-build/Reverb
cd /home/z/reverb-build/Reverb
cat memories/MEMORY.md              # this file
ls session-logs/                    # session summaries
tail -100 session-logs/worklog.md   # most recent work
# → the latest session-NN file's "Next session's first step" tells you exactly what to do
```

The dev environment for the Android app will be a fresh Kotlin/Gradle project scaffolded from `docs/research/01-a-tech-stack-spec.md` (which has the complete `libs.versions.toml` + `build.gradle.kts` + `settings.gradle.kts`). The Next.js plan-viewer at `/home/z/my-project/` is separate — it's just the planning visualization, not part of the Android app.
