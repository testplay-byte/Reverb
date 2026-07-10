# Session 01 — 2026-07-10 — Phase 0 build (COMPLETE — CI green, APK built)

## Goal
Build the complete Phase 0 spike Android app: enhanced universal extractor v2 + ad-blocker contract + green Material 3 Expressive UI + GitHub Actions CI to compile the APK.

## What was done
- Scaffolded the full Gradle project (settings.gradle.kts, root build.gradle.kts, gradle/libs.versions.toml, gradle.properties, gradle-wrapper.jar + gradlew committed).
- Created 8 modules: `:app`, `:core:{common,network,html,video}`, `:source-api`, `:source-universal`, `:adblock`.
- `:source-api` — the pure-Kotlin `Site` contract + all DTOs (MediaItem, MediaDetails, VideoRef, ResolvedStream, Quality, SubtitleTrack, FilterList, Capability, VideoExtractorHint).
- `:core:common` — `UrlUtils` (VIDEO_URL_REGEX), `LruCache`, `Logger`.
- `:core:network` — `HttpClientFactory` (OkHttp 5.4.0 + UA + RateLimit + Cloudflare + DoH + Brotli + logging), `CloudflareSolver` contract (Phase 0 stub).
- `:core:html` — `HtmlSimplifier` (97KB→2.6KB pipeline: strip scripts/styles/svg/noscript/comments, prune attributes, candidate-pattern detector).
- `:core:video` — `HlsMasterParser` (EXT-X-STREAM-INF + EXT-X-MEDIA, URL resolution, variant sorting).
- `:adblock` — `AdMatcher` interface + `KotlinRegexMatcher` (EasyList ||domain^, $type, @@exceptions, ## cosmetic) + `AdBlockInterceptor`. **THE CRITICAL CONTRACT enforced inside checkNetwork()**: never block video URLs / MEDIA type / media Accept headers.
- `:source-universal` — `EnhancedUniversalExtractor` (WebView + JS exec + interaction sim + shouldInterceptRequest + response-body scan + blob: interception + login-wall detection) + `UniversalSite` adapter.
- `:app` — green M3 Expressive theme (`ReverbTheme`), `MainActivity`, `ReverbApp` (manual DI), `ExtractorManager`, `SpikeScreen` + `SpikeViewModel` (address bar + status + detected-streams list + quality cards + detail bottom sheet + ad-block badge).
- GitHub Actions CI: JDK 21 + Gradle 9.6.1 + `:app:assembleDebug` + APK artifact upload + unit tests.
- Phase 0 test plan (`docs/phase-0/TEST-PLAN.md`): 11-site gate.
- Unit tests: `AdBlockerContractTest` (13 tests), `HtmlSimplifierTest` (3 tests), `HlsMasterParserTest` (3 tests) — ALL PASSING.

## What worked
- ✅ **CI BUILD SUCCEEDED** — all 14 steps green (build, test, artifact upload).
- ✅ **APK built** — 25.6 MB debug APK, available as a GitHub Actions artifact.
- ✅ **Unit tests pass** — including the critical 13-case ad-blocker contract test.
- ✅ The ad-blocker extractor-non-interference contract is enforced + tested.
- ✅ The HTML simplifier pipeline works (strips noise, detects catalog candidates).
- ✅ The HLS master parser handles multi-variant playlists + audio/subtitle tracks.
- ✅ Green M3 Expressive theme applied.

## What failed / blocked (and how we fixed each — 10 CI iterations)
1. `gradle wrapper` not on runner PATH → committed gradle-wrapper.jar + gradlew directly.
2. `*.jar` in .gitignore excluded gradle-wrapper.jar → added `!gradle/wrapper/gradle-wrapper.jar` exception.
3. AGP 9 rejects `org.jetbrains.kotlin.android` plugin (built-in Kotlin since AGP 9.0) → removed from all modules.
4. Root build.gradle.kts still referenced removed `libs.plugins.kotlin.android` → removed the alias.
5. androidx.hilt 1.4.0 requires compileSdk 37 (we had 36) → bumped to 37.
6. Jsoup `doc.getAllComments()` doesn't exist → replaced with NodeVisitor traversal.
7. OkHttp 5 deprecated `ResponseBody.create()` → use `ByteArray.toResponseBody()` extension.
8. OkHttp 5 deprecated `HttpUrl.get(String)` → use `String.toHttpUrl()` extension.
9. `CloudflareSolver.solve` was `suspend` but OkHttp interceptors are synchronous → changed to plain `fun`.
10. Kotlin 2.4.0 stdlib conflict (AGP 9 bundles 2.4.0, we pinned 2.2.21) + Voyager/KSP version coupling → simplified Phase 0: bumped to Kotlin 2.4.0, removed Hilt/Voyager/Room/KSP (manual DI, single screen, no DB for the spike).
11. App module missing `okhttp` dependency (ReverbApp uses OkHttpClient directly) → added.

## What's NOT done (needs a real device — can't do in this sandbox)
- ❌ The 10-site gate test (`docs/phase-0/TEST-PLAN.md`) — requires installing the APK on an Android device and testing each site. This sandbox has no emulator/device.
- ❌ The Cloudflare WebView solver is a stub (Phase 0 logs + returns false). Sites with CF challenges (miruro, animekhor) will fail until Phase 1 wires the real solver.

## Files changed
- Gradle: settings.gradle.kts, build.gradle.kts, libs.versions.toml, gradle.properties, gradle-wrapper.properties, gradle-wrapper.jar, gradlew, gradlew.bat
- 8× module build.gradle.kts
- :source-api: Site.kt
- :core:common: Logger.kt, UrlUtils.kt
- :core:network: Interceptors.kt, HttpClientFactory.kt
- :core:html: HtmlSimplifier.kt + test
- :core:video: HlsMasterParser.kt + test
- :adblock: KotlinRegexMatcher.kt, AdBlockInterceptor.kt + AdBlockerContractTest
- :source-universal: EnhancedUniversalExtractor.kt, UniversalSite.kt
- :app: AndroidManifest.xml, themes.xml, strings.xml, launcher icons, ReverbApp.kt, MainActivity.kt, ExtractorManager.kt, ReverbTheme.kt, SpikeScreen.kt, SpikeViewModel.kt
- .github/workflows/build.yml
- docs/phase-0/TEST-PLAN.md

## Next session's first step
1. Download the debug APK from GitHub Actions artifacts (run 29112128471).
2. Install on an Android device: `adb install app-reverb-debug.apk`
3. Run the 10-site gate test from `docs/phase-0/TEST-PLAN.md`.
4. Document results. If ≥9/11 pass → greenlight Phase 1.
5. If CF-protected sites fail → priority #1 for Phase 1 is the WebView CF solver.

## Commit(s) pushed
- 5f90003 Foundation: planning v3.0 backup
- 245bcad Phase 0: enhanced universal extractor v2 + ad-blocker contract + green M3 Expressive UI + CI
- 63d945c fix(ci): commit gradle wrapper jar + gradlew script
- 340a3d5 fix(ci): un-ignore gradle-wrapper.jar
- 30ac9bf fix(build): remove kotlin-android plugin (AGP 9 has built-in Kotlin)
- b7373bc fix(build): remove kotlin-android alias from root build.gradle.kts
- 33991b5 fix(build): bump compileSdk + targetSdk to 37
- f80391d fix(compile): Jsoup comment removal + OkHttp 5 ResponseBody API
- 397ad15 fix(compile): OkHttp 5 HttpUrl extension + synchronous CloudflareSolver
- cb0419c fix(compile): add okhttp dep to source-universal + fix smart-cast
- 76c152d fix(compile): simplify Phase 0 — remove Hilt/Voyager/Room (manual DI)
- 5c651c6 fix(compile): add okhttp deps to app module
- (latest) Update session log + memory

**CI run 29112128471: ✅ SUCCESS — APK 25.6 MB, all tests pass.**
