# Reverb

> An Android browser that rebuilds the web. Analyze any website via scraping, rebuild its UI natively in Jetpack Compose, block ads, detect every video stream the page loads, and let the user download them — fully automated, on-device.

[![Status](https://img.shields.io/badge/status-planning%20v3.0-emerald)](PLAN.md)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20API%2024+-teal)](#)
[![Language](https://img.shields.io/badge/language-Kotlin%202.2.21-orange)](#)

---

## What is Reverb?

Reverb is an Android app that behaves like a browser, but instead of rendering websites as-is, it:

1. **Receives a URL** (typed, pasted, shared, or picked from a catalog)
2. **Analyzes the page** — enhanced universal extractor (WebView + JS exec + interaction sim + response-body scanning + blob interception + login-wall detection) + LLM-assisted site analyzer
3. **Extracts structured content** — catalog items, video streams, metadata
4. **Rebuilds the UI natively** in Jetpack Compose with Material 3 Expressive design — clean, fast, consistent, dark-mode-friendly
5. **Detects every media stream** the page tries to load (HLS `.m3u8`, DASH `.mpd`, progressive `.mp4`, blob URLs)
6. **Lets the user download** any detected stream — picking quality, muxing audio+video for DASH, saving to `MediaStore.Downloads`
7. **Blocks ads** — EasyList/EasyPrivacy/AdGuard filter lists, with the critical "extractor-before-blocker" contract so video detection never breaks
8. **Translates content** — app UI in 17 languages + on-device content translation (ML Kit → DeepL → TranslateGemma)

Think of it as: **Aniyomi's engine + NewPipe's generic extraction + 1DM+'s download UX + a real browser address bar + LLM-assisted site analysis**, all wrapped in Material 3 Expressive.

## Current status: **Planning v3.0** — build not yet started

The complete blueprint lives in [`PLAN.md`](PLAN.md) (27 sections, ~1230 lines). The build will begin on the user's "go" signal.

### What's done
- ✅ Feasibility analysis (verdict: fully doable)
- ✅ Reference-repo research (aniyomi, anime-extensions, NewPipeExtractor, Brave adblock-rust, Seal, aria2)
- ✅ Live analysis of 10 anime streaming sites
- ✅ Ad-blocking design with the extractor-non-interference contract
- ✅ 1DM+-inspired download UX design
- ✅ Deep build-ready tech stack spec (exact library coordinates + versions verified mid-2026)
- ✅ Full-automation engine design (Enhanced Universal Extractor v2 + LLM-Assisted Site Analyzer)
- ✅ Translation layer design
- ✅ End-to-end flow diagram

### What's next (on "go")
- ⏳ Phase 0: 2-week spike (validate the enhanced universal extractor on 10 sites — gate: ≥9/10)
- ⏳ Phase 1: MVP (7 weeks)
- ⏳ Phase 2: Public Beta + Learn Mode + on-device LLM (6 weeks)

See [`PLAN.md`](PLAN.md) §26 for the full roadmap.

## Repository structure

```
Reverb/
├── PLAN.md                      # The master blueprint (v3.0, 27 sections)
├── LICENSE                      # Apache-2.0 (app core)
├── README.md                    # This file
├── .gitignore                   # Android/Kotlin/Gradle + secrets
│
├── docs/                        # Planning + research artifacts
│   ├── research/                # Deep research reports
│   │   ├── 01-a-tech-stack-spec.md          (2083 lines, build-ready)
│   │   ├── 01-b-on-device-llm-android.md    (654 lines)
│   │   ├── 01-c-translation-i18n.md
│   │   ├── 02-b-anime-extensions-anatomy.md (1434 lines)
│   │   ├── 02-d-ad-blocking.md
│   │   ├── 02-e-1dm-plus-download-ux.md
│   │   └── 04-b-anime-sites-batch2.md
│   └── research/sites-batch1/  # Raw HTML captures of 10 sites
│
├── session-logs/                # Build-session logs (backed up here regularly)
│   └── BACKUP.md                # ← Backup convention doc
│
├── memories/                    # Persistent project memory (backed up here)
│   └── MEMORY.md                # ← Long-term context the AI maintains
│
└── (app source — to be created on "go")
    ├── app/                     # :app — single-activity shell, Hilt wiring
    ├── core/                    # :core:{common,network,html,video}
    ├── source-api/              # :source-api — KMP Site contract (zero Android deps)
    ├── source-universal/        # :source-universal-v2 — the enhanced extractor
    ├── source-builtin/          # :source-builtin — theme + hand-written SiteModules
    ├── source-newpipe/          # :source-newpipe — dynamic feature (GPL-isolated)
    ├── source-loader/           # :source-loader — extension APK system (v2)
    ├── adblock/                 # :adblock — Kotlin matcher → Brave-rust JNI
    ├── player/                  # :player — Media3 ExoPlayer wrapper
    ├── download/                # :download — WorkManager + aria2c + Media3 + FFmpeg
    ├── data/                    # :data — Room + DataStore
    ├── ui/                      # :ui — Material 3 Expressive Compose screens
    ├── feature-autolearn/       # :feature-autolearn — LLM site analyzer
    ├── feature-translate/       # :feature-translate — translation UI + service
    ├── feature-ytdlp/           # dynamic feature: yt-dlp + FFmpeg + aria2
    ├── feature-ondevice-llm/    # dynamic feature: LiteRT-LM + Gemma 3 1B
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── gradle/libs.versions.toml
```

## Tech stack (build-ready — see [`docs/research/01-a-tech-stack-spec.md`](docs/research/01-a-tech-stack-spec.md))

| Layer | Library | Version |
|---|---|---|
| Language | Kotlin | 2.2.21 |
| UI | Jetpack Compose + Material 3 Expressive | BOM 2026.06.01 |
| Navigation | Voyager | 2.2.21-1.10.3 |
| DI | Hilt (KSP, no KAPT) | 2.60.1 |
| HTTP | OkHttp | 5.4.0 stable |
| HTML | Jsoup | 1.22.2 |
| JS engine | dokar3/quickjs-kt | 1.0.5 |
| Player | Media3 (ExoPlayer) | 1.10.1 |
| DB | Room | 2.8.4 |
| Background | WorkManager | 2.11.2 |
| Ad-block | Brave adblock-rust (JNI) | 0.13.0 |
| LLM | Google LiteRT-LM + Gemma 3 1B | 0.14.0 |
| Translation | ML Kit Translate | 17.0.3 |
| Downloads | aria2c + Media3 DownloadService + FFmpeg | via youtubedl-android 0.18.1 |

## License

Apache-2.0 for the app core. The `:source-newpipe` module is GPL-3.0 (NewPipeExtractor) — isolated as a dynamic-feature module so the main APK stays non-GPL. See [`PLAN.md` §11](PLAN.md) for the full legal framing.

## Contributing

Not yet accepting contributions — build hasn't started. Once Phase 1 ships, the `:source-builtin` SiteModules and `:learn-mode` configs will be community-contributable.

## Acknowledgments

Reverb's design is informed by these excellent open-source projects:
- [aniyomiorg/aniyomi](https://github.com/aniyomiorg/aniyomi) — the app-shell blueprint
- [yuzono/anime-extensions](https://github.com/yuzono/anime-extensions) — the scraper-anatomy blueprint
- [TeamNewPipe/NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — the generic-extraction blueprint
- [brave/adblock-rust](https://github.com/brave/adblock-rust) — the ad-block engine
- [JunkFood02/Seal](https://github.com/JunkFood02/Seal) — the download-UX blueprint
- [aria2/aria2](https://github.com/aria2/aria2) — the multi-connection HTTP engine
