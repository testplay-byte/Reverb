# Reverb Android — Build-Ready Technology Stack Specification

**Task ID:** 1-a
**Status:** Build-ready spec for the lead developer to scaffold the project on day one.
**Verification date:** July 2026 (mid-2026 cutoff)
**Method:** Direct fetches of Google Maven (`dl.google.com/dl/android/maven2/<group>/<artifact>/maven-metadata.xml`) and Maven Central (`repo1.maven.org/maven2/...`) for every coordinate. JitPack API for `com.github.*` artifacts. Crates.io for Rust crates. GitHub releases page for version-tag ambiguities. Cross-referenced against Aniyomi's and Seal's `gradle/libs.versions.toml` (live master branches).

---

## TL;DR — the recommended stack at a glance

| Layer | Pick | Coordinate | Version |
|---|---|---|---|
| JDK | **21 LTS** (run Gradle + AGP 9.x on JDK 21; source/target 17 for Android) | — | 21 |
| Gradle | Gradle (wrapper) | `gradle-wrapper.properties: distributionUrl` | **9.6.1** |
| AGP | `com.android.application` (and `.library`) | `com.android.application` | **9.2.1** |
| Kotlin | `org.jetbrains.kotlin.android` | `org.jetbrains.kotlin.android` | **2.2.21** |
| Compose Compiler | `org.jetbrains.kotlin.plugin.compose` (bundled with Kotlin 2.x — confirmed) | `org.jetbrains.kotlin.plugin.compose` | **2.2.21** |
| KSP | `com.google.devtools.ksp` (KSP2) | `com.google.devtools.ksp` | **2.3.10** |
| Compose BOM | `androidx.compose:compose-bom` | `androidx.compose:compose-bom` | **2026.06.01** |
| Material 3 | `androidx.compose.material3:material3` (in BOM) | in BOM | 1.4.0 |
| Navigation | **Voyager** (Kotlin-coupled versioning) | `cafe.adriel.voyager:voyager-navigator` etc. | **2.2.21-1.10.3** |
| DI | **Hilt** (with KSP) | `com.google.dagger:hilt-android` | **2.60.1** |
| Networking | OkHttp | `com.squareup.okhttp3:okhttp` | **5.4.0** |
| HTML | Jsoup | `org.jsoup:jsoup` | **1.22.2** |
| TLS | Conscrypt | `org.conscrypt:conscrypt-android` | **2.6.0** |
| JS engine | **quickjs-kt** (replaces dead `app.cash.quickjs:quickjs-android:0.9.2`) | `io.github.dokar3:quickjs-kt` | **1.0.5** |
| Serialization | kotlinx.serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.11.0** |
| Coroutines | kotlinx.coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | **1.11.0** |
| Database | Room (KSP) | `androidx.room:room-runtime` | **2.8.4** |
| Prefs | DataStore Preferences | `androidx.datastore:datastore-preferences` | **1.2.1** |
| SAF | UniFile (Aniyomi pattern) | `com.github.tachiyomiorg:unifile` | **e0def6b3dc** |
| Player | Media3 | `androidx.media3:media3-exoplayer` (+ui, +session, +hls, +dash, +datasource-okhttp, +exoplayer-workmanager) | **1.10.1** |
| Thumbnails | Coil 3 | `io.coil-kt.coil3:coil-compose` (+coil-network-okhttp, via `coil-bom`) | **3.5.0** |
| Background | WorkManager | `androidx.work:work-runtime-ktx` | **2.11.2** |
| Hilt+Work | `androidx.hilt:hilt-work` + `hilt-compiler` (KSP) | `androidx.hilt:hilt-work` | **1.4.0** |
| WebKit | `androidx.webkit:webkit` | `androidx.webkit:webkit` | **1.16.0** |
| Adblock (Phase 2) | Brave `adblock-rust` via cargo-ndk JNI | crates.io `adblock` | **0.13.0** |
| Native bundling | youtubedl-android (also bundles FFmpeg + aria2c) | `io.github.junkfood02.youtubedl-android:{library,ffmpeg,aria2c,common}` | **0.18.1** |
| Generic extractor | NewPipeExtractor | `com.github.TeamNewPipe:NewPipeExtractor` (JitPack) | **v0.26.3** |
| LLM (Phase 2) | LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android` | **0.14.0** |
| Translation | ML Kit + Moko Resources | `com.google.mlkit:translate` + `dev.icerock.moko:resources` | 17.0.3 / 0.24.5 |
| Testing | JUnit4 + Robolectric + Compose UI Test + Turbine + MockK + Kotest | (see §13) | (see §13) |

> **Two big mid-2026 surprises that affect this stack:**
> 1. **FFmpegKit is officially dead.** `com.arthenica:ffmpeg-kit-*` was archived April 2025 and binaries were removed from Maven Central. The "official continuation" (`arthenica/ffmpeg-kit-next`) is **source-only** (Nix-built). For Reverb we sidestep the whole problem by using the FFmpeg binary that ships **inside** `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` — same trick Seal uses today. This is the cleanest path.
> 2. **`app.cash.quickjs:quickjs-android` is dead.** Pinned at 0.9.2 since August 2021. Aniyomi still ships it but it's effectively abandoned. The modern replacement is `io.github.dokar3:quickjs-kt:1.0.5` — KMP, async, Apache-2.0, latest QuickJS engine.

---

## 1. Build tooling

### 1.1 JDK
- **JDK 21 LTS** to run Gradle 9.6.1 and AGP 9.2.1 (Gradle 9.x requires JDK 17+ minimum, 21 recommended). Android source/target stays at JVM 17 (`jvmTarget = "17"`, `JavaVersion.VERSION_17`) for the broadest Android-compatible bytecode.
- Verify on day one: `java -version` should print `21.x.x`. Configure via `org.gradle.java.home` in `gradle.properties` or rely on Android Studio's bundled JDK 21.

### 1.2 Gradle
- **Gradle 9.6.1** (current stable as of 2026-07-10). Verified via `https://services.gradle.org/versions/current`.
- AGP 9.2.1 requires Gradle 8.13 minimum; Gradle 9.x is officially supported. The Gradle compatibility matrix at `docs.gradle.org/current/userguide/compatibility.html` confirms "Gradle is tested with Android Gradle Plugin 9.0 through 9.3.0-alpha06."
- Set in `gradle/wrapper/gradle-wrapper.properties`:
  ```properties
  distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip
  distributionSha256Sum=9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14
  ```

### 1.3 Android Gradle Plugin (AGP)
- **AGP 9.2.1** (latest stable as of July 2026). The metadata at `dl.google.com/dl/android/maven2/com/android/application/com.android.application.gradle.plugin/maven-metadata.xml` lists `9.2.1` as the latest non-RC/non-alpha version (RC stage has `9.3.0-rc02`; `9.4.0-alpha04` is bleeding edge).
- **AGP 9.x dropped** the old `kotlin-android-extensions` (already gone) and **`android.buildFeatures.buildConfig`** now defaults to `true` only if `buildConfig = true` is explicitly set; you must opt in: `buildFeatures { buildConfig = true }`.
- **AGP 9.x requires** `android.useAndroidX=true` (already standard).

### 1.4 Kotlin + Compose Compiler + KSP
- **Kotlin 2.2.21** (latest stable 2.2.x as of July 2026; 2.4.0 is stable but many libs — including Voyager — have not yet shipped 2.4 builds).
  - The Compose Compiler plugin is **bundled with Kotlin 2.x** — confirmed. The old `androidx.compose.compiler:compiler` artifact no longer exists; you apply `org.jetbrains.kotlin.plugin.compose` with the same version as Kotlin.
  - **Why 2.2.21 and not 2.4.0?** Voyager's latest release is `2.2.21-1.10.3` — it's a Kotlin-version-pinned build (see §2.3). Voyager does NOT yet have a 2.4.x-compatible release. Kotlin 2.2.21 also matches the Gradle 9.6.1 / Kotlin compatibility cell ("Gradle 9.6.0 → Kotlin 2.2.21" per the compat matrix).
- **KSP 2.3.10** (KSP2 — the decoupled versioning scheme that started at KSP 2.3.0).
  - KSP2 supports any Kotlin 2.2+ — verify by adding `ksp.useKSP2=true` in `gradle.properties` (KSP2 is default in recent versions, but explicitly setting this avoids falling back to KSP1).
  - **Hilt with KSP**: Hilt has supported KSP since 2.48; with Hilt 2.60.1 + KSP 2.3.10 you can drop KAPT entirely. The Hilt processor artifact is `com.google.dagger:hilt-android-compiler:2.60.1` applied via the `ksp(...)` configuration.

### 1.5 Compose BOM (Bill of Materials)
- **`androidx.compose:compose-bom:2026.06.01`** — latest stable as of 2026-07-01.
- The BOM **pins** the following artifact versions (verified by parsing the BOM POM at `dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.06.01/compose-bom-2026.06.01.pom`):
  - `androidx.compose.ui:ui`, `ui-graphics`, `ui-text`, `ui-tooling`, `ui-tooling-preview`, `ui-test-junit4`, `ui-test-manifest` → **1.11.4**
  - `androidx.compose.runtime:runtime` → **1.11.4**
  - `androidx.compose.foundation:foundation`, `foundation-layout` → **1.11.4**
  - `androidx.compose.animation:animation`, `animation-core`, `animation-graphics` → **1.11.4**
  - `androidx.compose.material3:material3` → **1.4.0** ✅ (in BOM)
  - `androidx.compose.material:material-icons-core`, `material-icons-extended` → **1.7.8** (still on the older line; you can override if you need newer icons)
  - `androidx.compose.material:material` (legacy Material 1/2) → **1.11.4** (avoid — use M3)
- To consume the BOM in a module:
  ```kotlin
  dependencies {
      val composeBom = platform(libs.androidx.compose.bom)
      implementation(composeBom)
      androidTestImplementation(composeBom)
      implementation(libs.androidx.compose.ui.ui)
      implementation(libs.androidx.compose.material3.material3)
      implementation(libs.androidx.compose.material.material.icons.extended)
      debugImplementation(libs.androidx.compose.ui.ui.tooling)
      debugImplementation(libs.androidx.compose.ui.ui.test.manifest)
  }
  ```

### 1.6 `settings.gradle.kts` — pluginManagement + dependencyResolutionManagement

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.github\\.tfcpor.*") // not needed unless using tfcpor slf4j
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // JitPack is NOT needed in pluginManagement (no JitPack-hosted plugins used)
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // UniFile, NewPipeExtractor, deepl-jvm
        maven { url = uri("https://raw.githubusercontent.com/tachiyomiorg/unifile/master") } // fallback if JitPack fails (Aniyomi pattern)
    }
}

rootProject.name = "Reverb"

// Modules (see §16 for the dependency graph)
include(":app")
include(":core:common")
include(":core:network")
include(":core:html")
include(":core:video")
include(":source-api")
include(":source-universal")
include(":source-newpipe")
include(":source-builtin")
include(":source-loader")
include(":player")
include(":download")
include(":data")
include(":ui")
include(":adblock")
include(":learn-mode")
include(":lib:animestreamtheme")
include(":lib:animestreammirrordecoder")
include(":lib:securepipeclient")
include(":lib:anilistcatalog")
// :features:yt-dlp is a dynamic-feature module (Phase 2)
include(":features:yt-dlp")
```

**Repository wiring notes:**
- `google()` — required for all `androidx.*`, `com.android.*`, `com.google.*` (Hilt, ML Kit, LiteRT-LM, Media3) artifacts.
- `mavenCentral()` — required for Kotlin, KSP, OkHttp, Jsoup, Conscrypt, Coil, Voyager, Room (post 2.7), Hilt compiler, kotlinx libs, youtubedl-android (published directly on Maven Central as `io.github.junkfood02.*`).
- `maven("https://jitpack.io")` — required for `com.github.tachiyomiorg:unifile`, `com.github.TeamNewPipe:NewPipeExtractor`, `com.github.seratch:deepl-jvm`. JitPack builds on-demand from a Git tag/commit hash; first build takes ~3 minutes.
- The `content { includeGroupByRegex(...) }` filter on `google()` is optional but recommended to speed up resolution (prevents Maven Central from being queried for `androidx.*`).

### 1.7 Version catalog (`gradle/libs.versions.toml`)

See **§15** for the complete starter file.

---

## 2. Core AndroidX + Compose

### 2.1 Core KTX
| Coordinate | Version | Note |
|---|---|---|
| `androidx.core:core-ktx` | **1.19.0** | Core Kotlin extensions; provides `Context.getSystemService<K>()`, `View.doOnLayout {}`, etc. Latest stable as of 2026-06-03. |
| `androidx.core:core-splashscreen` | **1.2.0** | Android 12+ SplashScreen API backport. Apply theme via `Theme.SplashScreen` parent + `postSplashScreenTheme`. |

### 2.2 Lifecycle
| Coordinate | Version | Note |
|---|---|---|
| `androidx.lifecycle:lifecycle-runtime-ktx` | **2.11.0** | `lifecycleScope`, `Lifecycle.repeatOnLifecycle`. |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | **2.11.0** | `viewModel()` and `viewModel<VM>(factory)` Compose APIs. |
| `androidx.lifecycle:lifecycle-runtime-compose` | **2.11.0** | `collectAsStateWithLifecycle()` — **the** correct way to collect a `StateFlow` in Compose (lifecycle-aware). |
| `androidx.lifecycle:lifecycle-process` | **2.11.0** | `ProcessLifecycleOwner` for app-foreground/background detection (used by the download service). |

> All lifecycle artifacts are released in lockstep — pin them all to the same version (here, `2.11.0`).

### 2.3 Activity + Compose integration
| Coordinate | Version | Note |
|---|---|---|
| `androidx.activity:activity-compose` | **1.13.0** | `setContent { ... }`, `rememberLauncherForActivityResult`, `BackHandler`. Latest stable as of 2026-03-11. |
| `androidx.activity:activity-ktx` | **1.13.0** | (Implicitly pulled in by `-compose`; declare only if you need the by-activity API surface directly.) |

### 2.4 Compose BOM + Material 3
See §1.5 above. Material 3 (`androidx.compose.material3:material3:1.4.0`) is in the BOM; do not pin it explicitly.

### 2.5 Material Icons Extended
| Coordinate | Version | Note |
|---|---|---|
| `androidx.compose.material:material-icons-extended` | via BOM → **1.7.8** | The full icon set (~24 MB of classes; consider keeping it `implementation` rather than `api` to avoid bloating downstream modules). Use `androidx.compose.material:material-icons-core` (also in BOM) for the small default set. |

> **Gotcha:** Material Icons Extended is on a slower release cadence than the rest of Compose. As of BOM 2026.06.01 it's at 1.7.8 (older than the rest of the Compose 1.11.4 line). This is fine — icons rarely need version-parity with the rest of Compose.

### 2.6 Material 3 Adaptive (large-screen)
| Coordinate | Version | Note |
|---|---|---|
| `androidx.compose.material3:material3-adaptive` | **1.0.0-alpha06** | `ListDetailPaneScaffold`, `SupportingPaneScaffold`. Still alpha but stable enough for production. Used by the rebuilt site UI for tablet/foldable layouts. |
| `androidx.compose.material3:material3-adaptive-navigation-suite` | **1.4.0** (stable, in BOM) | The bottom-nav / nav-rail / drawer switcher based on window size class. |

### 2.7 Other Compose helpers
| Coordinate | Version | Note |
|---|---|---|
| `androidx.constraintlayout:constraintlayout-compose` | **1.1.1** | Use only if you need ConstraintLayout in Compose (rare — prefer `Row`/`Column`/`Flow`). |
| `org.jetbrains.kotlinx:kotlinx-collections-immutable` | **0.5.1** | `ImmutableList`, `PersistentList` — used by Compose state holders to avoid the "mutable list passed to Compose" footgun. |

### 2.8 Navigation — Voyager (the Aniyomi pattern)
| Coordinate | Version | Note |
|---|---|---|
| `cafe.adriel.voyager:voyager-navigator` | **2.2.21-1.10.3** | Core `Navigator(Screen)` composable. |
| `cafe.adriel.voyager:voyager-screenmodel` | **2.2.21-1.10.3** | `ScreenModel` (a lifecycle-scoped ViewModel equivalent; survives configuration changes, cleared on `onDispose`). |
| `cafe.adriel.voyager:voyager-transitions` | **2.2.21-1.10.3** | Screen enter/exit transitions (fade, slide, scale). |
| `cafe.adriel.voyager:voyager-hilt` | **2.2.21-1.10.3** | Hilt integration: `hiltScreenModel()` and `ScreenModelImpl`. |

> **Versioning scheme change:** As of the 1.10.x line, Voyager adopted a `<kotlin-version>-<compose-multiplatform-version>` scheme. The release `2.2.21-1.10.3` means "compiled against Kotlin 2.2.21 + Compose Multiplatform 1.10.3". This is why we pin Kotlin to **2.2.21** for the project — to match Voyager's build cell exactly. When Voyager ships a 2.4.x-compatible release, we can bump Kotlin to 2.4.x.
>
> **Voyager vs Navigation-Compose:** Voyager is what Aniyomi uses, and the v1.x screen-graph model (a `Screen` is just a `Composable` — no routes, no type-safe nav graphs) is materially simpler for an app that builds screens dynamically from site data. Navigation-Compose 2.9.x is also fine, but Voyager saves ~30% of the boilerplate for deep screen graphs (e.g. CatalogueList → Details → EpisodeList → Player → Settings).
>
> **Compatible with Compose 2026:** Yes — Voyager 1.10.3 is built against Compose Multiplatform 1.10.3 which corresponds to the AndroidX Compose 1.11.x line (which is what's pinned by BOM 2026.06.01). The artifact is multiplatform — the `.android` sub-artifact is what you actually pull in.

### 2.9 Misc AndroidX
| Coordinate | Version | Note |
|---|---|---|
| `androidx.appcompat:appcompat` | **1.7.1** | Needed for `AppCompatActivity` (the single-activity host) + AppCompat delegates. (1.8.0-alpha01 exists; pin to 1.7.1 stable.) |
| `com.google.android.material:material` | **1.14.0** | Material Components for Android (legacy View system). Only needed if you use `MaterialAlertDialogBuilder` or `TextInputLayout` outside Compose. |
| `androidx.browser:browser` | **1.10.0** | CustomTabsIntent — used for "open in browser" share target. |
| `androidx.startup:startup` | **1.2.0** (alpha — wait for 1.2.0 stable or use 1.1.1) | App initializer; only needed if you want to bootstrap libraries without a custom `Application.onCreate` chain. Skip in v1. |

---

## 3. Dependency Injection

### 3.1 Decision: **Hilt** (with KSP, no KAPT)

**Why Hilt over Koin:**
- **Hilt is more mainstream** (PLAN.md §5.1 explicitly says "Hilt (more mainstream than Aniyomi's Injekt; better IDE support)").
- Hilt is **compile-time verified** — if you forget a `@Inject` or have a missing binding, the build fails. Koin is runtime-only — you discover DI bugs in production.
- **Hilt's Android integration is first-class** — `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`, `@HiltWorker`, `@HiltAndroidService` all work out of the box.
- **Voyager has first-class Hilt support** (`voyager-hilt` artifact).
- **Hilt 2.60.1 supports KSP** — no more KAPT (which is deprecated in Kotlin 2.x and slow).

**Why not Koin:**
- Koin is KMP-friendly and annotation-free, but Aniyomi's Injekt is dead — there's no production-grade Voyager+Koin reference in the scraping-app space.
- Koin's DSL is verbose for ~100 bindings (Reverb will have ~30 SiteModules + repositories + use cases + interceptors).
- Koin 4.x (2025+) is excellent, but Hilt's compile-time safety is a stronger foundation for a long-lived app.

### 3.2 Coordinates

| Coordinate | Version | Note |
|---|---|---|
| `com.google.dagger:hilt-android` | **2.60.1** | Hilt runtime. |
| `com.google.dagger:hilt-android-compiler` | **2.60.1** | KSP processor (apply via `ksp(...)` configuration). |
| `com.google.dagger:hilt-android-gradle-plugin` | **2.60.1** | Gradle plugin (applied as `com.google.dagger.hilt.android`). |
| `androidx.hilt:hilt-navigation-compose` | **1.4.0** | `hiltViewModel()` Compose accessor — bridges `@HiltViewModel` to Compose. |
| `androidx.hilt:hilt-work` | **1.4.0** | `@HiltWorker` annotation + `HiltWorkerFactory` for WorkManager. |
| `androidx.hilt:hilt-compiler` | **1.4.0** | KSP processor for `hilt-work` (generates the `HiltWorkerFactory` bindings). Apply via `ksp(...)`. |

### 3.3 Wiring gotchas

1. **Drop KAPT entirely.** Hilt 2.60.1 + KSP 2.3.10 is the modern stack. Don't apply `kotlin-kapt` plugin at all. Use `ksp(libs.google.dagger.hilt.android.compiler)` and `ksp(libs.androidx.hilt.compiler)` instead.
2. **Hilt + WorkManager** requires:
   - A custom `Configuration.Provider` on the `Application`:
     ```kotlin
     @HiltAndroidApp
     class ReverbApp : Application(), Configuration.Provider {
         @Inject lateinit var workerFactory: HiltWorkerFactory
         override val workManagerConfiguration: Configuration
             get() = Configuration.Builder()
                 .setWorkerFactory(workerFactory)
                 .build()
     }
     ```
   - The `WorkManagerInitializer` must be disabled in `AndroidManifest.xml`:
     ```xml
     <provider
         android:name="androidx.startup.InitializationProvider"
         android:authorities="${applicationId}.androidx-startup"
         android:exported="false"
         tools:node="merge">
         <meta-data
             android:name="androidx.work.WorkManagerInitializer"
             android:value="androidx.startup"
             tools:node="remove" />
     </provider>
     ```
3. **Voyager-Hilt**: `voyager-hilt` provides `ScreenModelImpl` and the `hiltScreenModel()` extension. It does NOT auto-discover `@HiltViewModel` — Voyager's `ScreenModel` is the abstraction, not `ViewModel`.
4. **Hilt aggregate-classes**: If you have multiple Gradle modules with `@HiltAndroidApp`-dependent code, you may need `com.google.dagger:hilt-android-gradle-plugin`'s `enableAggregatingTask = true` (default true in 2.60.1).

---

## 4. Networking & scraping

### 4.1 HTTP — OkHttp 5.4.0 (stable!)

| Coordinate | Version | Note |
|---|---|---|
| `com.squareup.okhttp3:okhttp` | **5.4.0** | HTTP client. **5.0.0 went stable** in late 2024 — no longer alpha. Aniyomi's `5.0.0-alpha.14` is OBSOLETE; do not copy. |
| `com.squareup.okhttp3:okhttp-brotli` | **5.4.0** | Brotli interceptor — most sites ship Brotli-compressed HTML; smaller payloads = faster scraping. Apply `BrotliInterceptor` to your client. |
| `com.squareup.okhttp3:logging-interceptor` | **5.4.0** | HttpLoggingInterceptor — only in `debugImplementation` to avoid leaking URLs in production logs. |
| `com.squareup.okhttp3:okhttp-dnsoverhttps` | **5.4.0** | `DnsOverHttps` — DNS-over-HTTPS pluggable resolver. |
| `com.squareup.okio:okio` | **3.17.0** | Pulled in transitively by OkHttp 5.x; explicit pin recommended for security advisories. |

> **OkHttp 5.x breaking changes vs 4.x:** the `okhttp3.internal.*` packages are now in Kotlin; some `Interceptor` signatures moved. If porting Aniyomi interceptors, replace any `okhttp3.internal.*` imports with the public API or reimplement.

### 4.2 TLS — Conscrypt

| Coordinate | Version | Note |
|---|---|---|
| `org.conscrypt:conscrypt-android` | **2.6.0** | TLS 1.3 / modern cipher suites on Android 7+ (Android 10+ already has these natively, but Conscrypt is consistent across the API 24-35 range). |

**Wiring (Application.onCreate):**
```kotlin
import org.conscrypt.Conscrypt
import java.security.Security

override fun onCreate() {
    super.onCreate()
    Security.insertProviderAt(Conscrypt.newProvider(), 1)
    // ... then build the OkHttp client; OkHttp auto-detects Conscrypt as the highest-priority Provider
}
```

Gotcha: Conscrypt adds ~5 MB to the APK per ABI but only ONE ABI's worth (it's pure Java + a single native lib that's the same across ABIs).

### 4.3 HTML — Jsoup

| Coordinate | Version | Note |
|---|---|---|
| `org.jsoup:jsoup` | **1.22.2** | HTML parser. Aniyomi uses 1.19.1 — 1.22.x adds improved ad-hoc attribute selectors and faster `selectFirst`. Pure JVM, no Android deps; safe in `:core:html` JVM module. |

### 4.4 Serialization + Coroutines

| Coordinate | Version | Note |
|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.11.0** | JSON (de)serialization. Apply the `org.jetbrains.kotlin.plugin.serialization` Gradle plugin (version = Kotlin version, 2.2.21). |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | **1.11.0** | `Dispatchers.Main`, `Dispatchers.IO`, `CoroutineScope`. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | **1.11.0** | `runTest`, `TestDispatcher` (test scope). |

### 4.5 JavaScript engine — quickjs-kt (replaces dead app.cash.quickjs)

| Coordinate | Version | Note |
|---|---|---|
| `io.github.dokar3:quickjs-kt` | **1.0.5** | KMP QuickJS binding, async/suspend, DSL bindings. Apache-2.0. Latest stable on Maven Central as of mid-2026. |

> **Critical swap:** Aniyomi (and anime-extensions) use `app.cash.quickjs:quickjs-android:0.9.2`, which was last published in **August 2021** and is effectively abandoned. The Cash App team migrated to [Zipline](https://github.com/cashapp/zipline) which is a different beast (Kotlin/JS → QuickJS bytecode, focused on dynamically-loaded modules). For Reverb's "evaluate obfuscated player JS" use case (StreamWish Synchrony, Kwik packed-JS unpacker), `dokar3/quickjs-kt:1.0.5` is the right modern pick.
>
> **API differences vs `app.cash.quickjs`:**
> - `app.cash.quickjs.QuickJs` → `io.github.dokar3.quickjs.QuickJs`
> - `quickJs.evaluate("code")` (sync) → `quickJs.evaluate<T>("code")` (suspend)
> - Old: `QuickJs.create()` returns a synchronous engine. New: `QuickJs.create(Dispatchers.Default)` returns a suspend-capable engine.
> - DSL: `quickJs { define("console") { function("log") { args -> ... } }; evaluate<Int>("1+2") }`
> - The DSL is cleaner; the suspend API matches our coroutine-based scraping pipeline.

### 4.6 DNS-over-HTTPS (DoH)

No extra dependency needed — `com.squareup.okhttp3:okhttp-dnsoverhttps:5.4.0` provides `DnsOverHttps.Builder`. Wire it directly into the OkHttpClient:

```kotlin
private val dohProviders = listOf(
    // Cloudflare 1.1.1.1 (anti-malware + family filter optional)
    "https://cloudflare-dns.com/dns-query" to "1.1.1.1",
    // Google Public DNS
    "https://dns.google/dns-query" to "8.8.8.8",
    // Quad9 (malware-blocking)
    "https://dns.quad9.net/dns-query" to "9.9.9.9",
    // AdGuard DNS (default filter)
    "https://dns.adguard-dns.com/dns-query" to "94.140.14.14",
    // NextDNS (user-configured)
    // "https://dns.nextdns.io/<config-id>" to "<ip>",
    // Cloudflare malware-only
    "https://security.cloudflare-dns.com/dns-query" to "1.1.1.2",
    // Cloudflare family (malware + adult)
    "https://family.cloudflare-dns.com/dns-query" to "1.1.1.3",
    // dns0.eu (European)
    "https://dns0.eu" to "193.110.81.0",
)

fun buildClient(dohUrl: String, dohIp: String): OkHttpClient {
    val doh = DnsOverHttps.Builder()
        .client(OkHttpClient()) // bootstrap client (no DoH yet)
        .url(dohUrl.toHttpUrl())
        .bootstrapDnsHosts(listOf(InetAddress.getByName(dohIp)))
        .includeIPv6(true)
        .build()
    return OkHttpClient.Builder()
        .dns(doh)
        .build()
}
```

Wire user preference (DataStore) → DOH provider selection → OkHttpClient rebuild. The 8 providers above cover the Aniyomi set + AdGuard + dns0.eu.

### 4.7 NewPipeExtractor (the generic Tier-A extractor — from task 2-c)

| Coordinate | Version | Note |
|---|---|---|
| `com.github.TeamNewPipe:NewPipeExtractor` | **v0.26.3** | JitPack-only. Pure JVM library (no native deps). GPL-3.0 — isolate in `:source-newpipe` module (see §16 + PLAN.md §11.2). |

Wiring:
```kotlin
// settings.gradle.kts: ensure maven("https://jitpack.io") is in dependencyResolutionManagement
// :source-newpipe/build.gradle.kts:
dependencies {
    implementation(libs.newpipe.extractor)
    implementation(libs.squareup.okhttp)              // for the Downloader impl
    implementation(projects.core.network)             // for shared OkHttpClient
    implementation(projects.sourceApi)
}
```

The `NewPipe.init(Downloader)` call requires a `Downloader` implementation — that's the seam where we inject Reverb's OkHttp client (with all interceptors: UA, rate-limit, Cloudflare, Brotli, DoH).

---

## 5. Database & storage

### 5.1 Room

| Coordinate | Version | Note |
|---|---|---|
| `androidx.room:room-runtime` | **2.8.4** | Runtime + DAO interfaces. |
| `androidx.room:room-ktx` | **2.8.4** | Coroutines + Flow support for DAO methods (`suspend fun`, `Flow<List<...>>`). |
| `androidx.room:room-compiler` | **2.8.4** | KSP processor — apply via `ksp(...)`. **Room 2.7+ supports KSP2**. |
| `androidx.room:androidx.room.gradle.plugin` | **2.8.4** | Gradle plugin (applied as `androidx.room`). Provides `room { schemaDirectory("$projectDir/schemas") }` for migration testing. |

> Room 2.7+ is KMP-ready (can target iOS/macOS via SQLiteBundled). For Reverb v1 we use Android-only, but the option is there.
>
> Schema export: enable `room.schemaLocation` and commit the JSON schemas. Required for migration tests + CI reproducibility.

### 5.2 DataStore

| Coordinate | Version | Note |
|---|---|---|
| `androidx.datastore:datastore-preferences` | **1.2.1** | Preferences DataStore (typed via `preferencesKey<T>()`). 1.3.0-alpha09 exists but stick with 1.2.1 stable. |
| `androidx.datastore:datastore-core` | **1.2.1** | (Implicitly pulled in.) |

**No SharedPreferences.** This is a deliberate PLAN.md §5.2 decision — Aniyomi's SharedPreferences use is technical debt we avoid.

### 5.3 UniFile (SAF wrapper — the Aniyomi pattern)

| Coordinate | Version | Note |
|---|---|---|
| `com.github.tachiyomiorg:unifile` | **e0def6b3dc** | SAF + Java-File abstraction; writes to user-chosen `DocumentFile` trees and falls back to plain `java.io.File` for legacy paths. Aniyomi uses the same commit-hash pin. |

> UniFile has no release tags — JitPack builds from a commit hash. `e0def6b3dc` is the latest "ok" build per JitPack's API. When updating, query `https://jitpack.io/api/builds/com.github.tachiyomiorg/unifile` and pick the newest "ok" entry.

### 5.4 DocumentFile (AndroidX alternative to UniFile)

| Coordinate | Version | Note |
|---|---|---|
| `androidx.documentfile:documentfile` | **1.1.0** | Lower-level SAF API. UniFile wraps this; if you don't need UniFile's source-path abstraction, you can use this directly. |

**Decision:** Use **UniFile** because Reverb's download module needs to:
1. Write to user-chosen `MediaStore.Downloads` (Android 10+) — UniFile abstracts this.
2. Write to user-picked SAF `DocumentFile` trees (for users who want to save to SD card or a cloud provider) — UniFile abstracts this too.
3. Fall back to plain `java.io.File` for legacy paths on Android 9 and below.
4. Match the Aniyomi/Seal pattern documented in task 2-e.

### 5.5 MediaStore (no dep)
Framework API on Android 10+ (`MediaStore.Downloads.EXTERNAL_CONTENT_URI`). No Gradle dep needed; just `import android.provider.MediaStore`.

---

## 6. Media (player + download)

### 6.1 Media3 — the player + downloader

All Media3 artifacts are released in lockstep. Latest stable: **1.10.1** (1.11.0-beta01 is in beta).

| Coordinate | Version | Note |
|---|---|---|
| `androidx.media3:media3-exoplayer` | **1.10.1** | ExoPlayer core. |
| `androidx.media3:media3-ui` | **1.10.1** | `PlayerView` / `PlayerSurface` Compose-compatible View. For pure Compose, also see `media3-ui-compose` below. |
| `androidx.media3:media3-ui-compose` | **1.10.1** | Compose-native player surface (`PlayerSurface` composable). The 1.10+ release is production-ready. |
| `androidx.media3:media3-common` | **1.10.1** | Common types (`MediaItem`, `Format`, `C`). Pulled in transitively but declare explicitly. |
| `androidx.media3:media3-hls` | **1.10.1** | `HlsMediaSource` — for `.m3u8` playback. Used by 99% of scraped sites (per task 4-a/4-b analysis). |
| `androidx.media3:media3-dash` | **1.10.1** | `DashMediaSource` — for `.mpd` playback (YouTube DASH, rare but supported). |
| `androidx.media3:media3-extractor` | **1.10.1** | Progressive MP4/MKV/WebM extractor. |
| `androidx.media3:media3-session` | **1.10.1** | `MediaSession` — for background playback notification controls + Bluetooth media buttons + Android Auto. |
| `androidx.media3:media3-datasource-okhttp` | **1.10.1** | `OkHttpDataSource` — bridges ExoPlayer's `DataSource` to Reverb's OkHttp client. **Critical:** lets all video segment requests go through the Cloudflare interceptor + DoH + rate-limit chain. |
| `androidx.media3:media3-exoplayer-workmanager` | **1.10.1** | `DownloadService` + WorkManager integration for HLS/DASH segment downloads. |
| `androidx.media3:media3-cast` | **1.10.1** | (Optional) Cast support — skip in v1 unless explicitly needed. |
| `androidx.media3:media3-database` | **1.10.1** | Pulled in by `DownloadService` for the persistent download index. |

**Wiring gotcha — OkHttpDataSourceFactory:**
```kotlin
@Provides @Singleton
fun provideDataSourceFactory(client: OkHttpClient): DataSource.Factory =
    OkHttpDataSource.Factory(client)
        .setUserAgent(...)  // a real Chrome UA

@Provides @Singleton
fun provideDownloadManager(
    context: Context,
    dataSourceFactory: DataSource.Factory,
): DownloadManager {
    val databaseProvider = StandaloneDatabaseProvider(context)
    val cache = SimpleCache(
        File(context.cacheDir, "media"),
        LeastRecentlyUsedCacheEvictor(2L * 1024 * 1024 * 1024),  // 2GB
        databaseProvider,
    )
    return DownloadManager(
        context,
        databaseProvider,
        cache,
        DefaultHttpDataSource.Factory(),  // upstream
        Executors.newFixedThreadPool(3),
    )
}
```

### 6.2 FFmpegKit — **DO NOT USE** (archived April 2025)

**The situation:**
- `com.arthenica:ffmpeg-kit-*` (full, full-gpl, video, audio, https, https-gpl, min, min-gpl) was **archived April 2025** and binaries were removed from Maven Central.
- The official continuation, `arthenica/ffmpeg-kit-next` (FFmpegKitNext), is **source-only** — you must build it locally via Nix (`nix-android.sh`). No Maven coordinates exist. As of July 2026 the latest FFmpegKitNext release is `v8.1.0` (FFmpeg 8.1.2).
- Community-maintained forks exist on Maven Central but are inconsistent; no single canonical fork has emerged.

**Reverb's solution:** Use the FFmpeg binary **bundled inside `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`**. This is the same pattern Seal uses today (verified by reading `JunkFood02/Seal` `gradle/libs.versions.toml`). The `ffmpeg` artifact provides:

| Coordinate | Version | Note |
|---|---|---|
| `io.github.junkfood02.youtubedl-android:library` | **0.18.1** | yt-dlp + CPython bundled as `.so`. Required base for any of the sub-artifacts. |
| `io.github.junkfood02.youtubedl-android:ffmpeg` | **0.18.1** | FFmpeg binary + `FFmpeg.getInstance()` Kotlin API. Process-based (not JNI). |
| `io.github.junkfood02.youtubedl-android:aria2c` | **0.18.1** | aria2c binary + `Aria2c.getInstance()` Kotlin API. Used for `-x16 -s16` HTTP multi-connection downloads. |
| `io.github.junkfood02.youtubedl-android:common` | **0.18.1** | Shared utilities. |

**Wiring:**
```kotlin
class ReverbApp : Application() {
    override fun onCreate() {
        super.onCreate()
        YoutubeDL.getInstance().init(this)    // optional — only if using yt-dlp Tier-D extraction
        FFmpeg.getInstance().init(this)        // required — needed for DASH mux
        Aria2c.getInstance().init(this)        // optional — only if using aria2c for big MP4 downloads
    }
}
```

**For DASH audio+video mux (Reverb's primary FFmpeg use case):**
```kotlin
val request = YoutubeDLRequest().apply {
        // Two inputs: -i video.m4s -i audio.m4s
        addOption("-i", videoUrl)
        addOption("-i", audioUrl)
        // Copy codecs (no transcoding — fast)
        addOption("-c", "copy")
        addOption("-f", "mp4")
        addOption("-movflags", "+faststart")
        addOption("-y", outputFilePath)
    }
    YoutubeDL.getInstance().execute(request, processId)
```

Alternatively, `FFmpeg.getInstance().run(...)` directly invokes the FFmpeg binary (no yt-dlp wrapper).

**APK size impact:** Each of `library`, `ffmpeg`, `aria2c` ships per-ABI native binaries. Total APK impact per ABI:
- `library` (yt-dlp + CPython): ~30-60 MB
- `ffmpeg`: ~30-40 MB (full build)
- `aria2c`: ~5 MB
- **Total: ~65-105 MB per ABI × 4 ABIs (arm64-v8a, armeabi-v7a, x86, x86_64) = ~260-420 MB universal APK**

**Mitigation: dynamic-feature module.** Put youtubedl-android in `:features:yt-dlp` (a dynamic-feature module) so it's downloaded on-demand from the Play Store. Users who only want the built-in extractors never download the ~100MB native payload. See §10 for setup.

**AGP `android:extractNativeLibs="true"`** must be set on the dynamic feature's manifest (youtubedl-android bundles `.so` files that need runtime extraction).

### 6.3 Coil 3 — image loading

| Coordinate | Version | Note |
|---|---|---|
| `io.coil-kt.coil3:coil-bom` | **3.5.0** | BOM that pins all coil3 artifacts. |
| `io.coil-kt.coil3:coil-compose` | via BOM → 3.5.0 | `AsyncImage`, `rememberAsyncImagePainter`, `SubcomposeAsyncImage`. |
| `io.coil-kt.coil3:coil-network-okhttp` | via BOM → 3.5.0 | OkHttp network fetcher — share the scraper's OkHttpClient for thumbnails (so Cloudflare-protected thumbnails work). |
| `io.coil-kt.coil3:coil-gif` | via BOM → 3.5.0 | Animated GIF/WEBP decoding. |

**Wiring:**
```kotlin
// :app module — Application.onCreate
val imageLoader = ImageLoader.Builder(this)
    .components {
        add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
    }
    .crossfade(true)
    .build()
coil3.SingletonImageLoader.setSafe { imageLoader }
```

Gotcha: Coil 3 uses `coil3.SingletonImageLoader` (different from Coil 2's `coil.ImageLoader`). The `setSafe` initializer runs lazily on first image load.

---

## 7. Background work

### 7.1 WorkManager

| Coordinate | Version | Note |
|---|---|---|
| `androidx.work:work-runtime-ktx` | **2.11.2** | `CoroutineWorker`, `PeriodicWorkRequest`, `WorkManager.getInstance(context)`. |

### 7.2 Hilt + WorkManager integration

See §3.3 for the wiring (custom `Configuration.Provider` + disabling `WorkManagerInitializer`).

For each `CoroutineWorker`:
```kotlin
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloader: Downloader,
    private val taskDao: TaskDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong("taskId", -1)
        return try {
            downloader.run(taskId)
            Result.success()
        } catch (e: CancellationException) {
            Result.failure()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
```

### 7.3 Foreground service (player + active downloads)

No Gradle dep — framework API (`ForegroundServiceType`). On Android 14+ you must declare `foregroundServiceType` in the manifest:
```xml
<service
    android:name=".player.PlayerService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="false" />
<service
    android:name=".download.ReverbDownloadService"
    android:foregroundServiceType="dataSync" />
```
(The `dataSync` type for downloads is what task 2-e documented from Seal.)

---

## 8. WebView / JS rendering

No external dep for the WebView itself — uses Android System WebView (framework, always present on Android 7+).

### 8.1 AndroidX WebKit (the WebView helper library)

| Coordinate | Version | Note |
|---|---|---|
| `androidx.webkit:webkit` | **1.16.0** | `WebSettingsCompat.addWebMessageListener` (for WebView↔Kotlin messaging — used by Cloudflare solver + the universal extractor), `WebSettingsCompat.setForceDark` (deprecated in Android 13+, but `WebSettingsCompat.setAlgorithmicDarkening` is the replacement), `WebViewCompat.addDocumentStartJavaScriptScripts`. 1.17.0-alpha03 exists; pin to 1.16.0 stable. |

**Wiring:**
```kotlin
// Add a WebMessageListener so JS in the page can post back to Kotlin (for the Cloudflare solver)
WebViewCompat.addWebMessageListener(
    webView,
    "ReverbBridge",
    setOf("https://*".toOriginPattern(), "http://*".toOriginPattern()),
) { _, message, _, _, responseIsReady ->
    val payload = message.data
    // ... handle the JS message
}
```

The `WebView` itself is in `:source-universal` (the universal extractor's `shouldInterceptRequest` regex match).

---

## 9. Ad-blocking

### 9.1 Phase 0 + Phase 1 — no external dep

Per task 2-d's recommendation, Reverb ships a hand-rolled Kotlin regex matcher (`KotlinRegexMatcher`, ~600 LOC) in `:adblock`. No external dependency for the engine.

Filter lists (EasyList, EasyPrivacy, AdGuard Base, AdGuard Annoyances, Peter Lowe's blocklist, uBO Privacy) are bundled as **unmodified data files** in `assets/adlists/`. No Gradle dep — just files.

### 9.2 Phase 2 — Brave `adblock-rust` via cargo-ndk JNI

This is the **only** part of the stack that requires building a Rust crate. Verified coordinates:

| Crate | Version | Source |
|---|---|---|
| `adblock` (the Brave Rust engine) | **0.13.0** | crates.io — https://crates.io/api/v1/crates/adblock |
| `cargo-ndk` (the Rust→Android cross-compiler) | **4.1.2** | crates.io — https://crates.io/api/v1/crates/cargo-ndk |
| `jni` crate (Rust↔JNI bindings) | 0.21 | crates.io (companion to adblock) |

**Cargo setup (in `:adblock/rust/Cargo.toml`):**
```toml
[package]
name = "reverb-adblock"
version = "0.1.0"
edition = "2021"

[lib]
name = "reverb_adblock"
crate-type = ["cdylib"]

[dependencies]
adblock = "0.13.0"
jni = { version = "0.21", default-features = false, features = ["invocation"] }
serde_json = "1"
```

**Build per-ABI (.so) via cargo-ndk:**
```bash
# One-time install:
cargo install cargo-ndk@4.1.2
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android

# Per-ABI build (run from :adblock/rust/):
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/28.0.13004108"  # NDK r28c
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 -o ../src/main/jniLibs build --release
```

This produces `:adblock/src/main/jniLibs/<abi>/libreverb_adblock.so` for each ABI. AGP automatically picks up `jniLibs/` and bundles them into the AAR/APK.

**JNI bridge Kotlin file** (`:adblock/src/main/kotlin/.../BraveAdblockMatcher.kt`):
```kotlin
class BraveAdblockMatcher : AdMatcher {
    private external fun nativeInit(): Long
    private external fun nativeLoadFilters(enginePtr: Long, filters: String): Boolean
    private external fun nativeCheckNetwork(
        enginePtr: Long,
        url: String,
        sourceUrl: String,
        requestType: String,
    ): Boolean
    private external fun nativeDispose(enginePtr: Long)

    private val enginePtr: Long = nativeInit()

    override fun checkNetwork(url: String, sourceUrl: String, type: RequestType): Boolean {
        // ✅ CRITICAL: extract-before-block contract (task 2-d §16.4)
        // Never block video MIME/extension URLs even if the engine says block.
        if (isMediaUrl(url)) return false
        return nativeCheckNetwork(enginePtr, url, sourceUrl, type.name.lowercase())
    }

    companion object {
        init { System.loadLibrary("reverb_adblock") }
    }
}
```

**APK size impact:** ~3 MB per ABI × 4 ABIs = ~12 MB total for the Brave engine (vs ~600 KB for the Kotlin regex matcher). Acceptable for Phase 2.

**CI vs local build:** Build locally in CI. Set up a GitHub Actions job that:
1. Installs Rust + cargo-ndk + Android NDK r28c
2. Runs `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 build --release`
3. Commits the `.so` files to the `:adblock/src/main/jniLibs/` directory
4. (Alternative) caches the `.so` outputs in CI cache keyed on `Cargo.lock` hash

**JitPack packaging of a Rust library for Android:** possible but fragile (JitPack would need Rust installed in its build env, which it isn't). Recommended to build in CI and vendor the `.so` files OR publish the AAR to Maven Central as `app.reverb:adblock-rust:0.1.0` (Phase 3+).

---

## 10. Native bundling for yt-dlp / aria2 (Phase 2 dynamic feature)

The full ~100 MB native payload (`library` + `ffmpeg` + `aria2c`) is best isolated in a **dynamic feature module** so users download it on-demand.

### 10.1 Module setup (`:features:yt-dlp`)

`settings.gradle.kts`:
```kotlin
include(":features:yt-dlp")
```

`:features:yt-dlp/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.reverb.feature.ytdlp"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // dynamic features have no applicationId
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":app"))  // dynamic-features depend on the base app module
    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)
    implementation(libs.youtubedl.android.aria2c)
    implementation(libs.youtubedl.android.common)
}
```

`:app/build.gradle.kts` must enable dynamic features:
```kotlin
android {
    dynamicFeatures += setOf(":features:yt-dlp")
    bundle {
        // Enable on-demand + instant delivery for dynamic features
        language { enableSplit = true }
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }
}
```

`:app/src/main/AndroidManifest.xml`:
```xml
<dist:module dist:instant="false" />
<module dist:title="@string/title_feature_ytdlp">
    <dist:delivery>
        <dist:on-demand />
    </dist:delivery>
    <dist:fusing dist:include="true" />
</module>
```

### 10.2 APK size impact

Per-ABI sizes (estimated, based on Seal's observed sizes):
- `library` (yt-dlp + CPython 3.11 + libffi + libssl): ~40 MB / ABI
- `ffmpeg` (full build with x264/x265/libvpx): ~30 MB / ABI
- `aria2c` (with c-ares, libxml2, openssl): ~5 MB / ABI
- **Total per ABI: ~75 MB**

With AGP ABI splits enabled, a user on an arm64-v8a device downloads ~75 MB on demand (not in the base APK). Universal APK without splits would be ~300 MB — **always enable ABI splits**.

### 10.3 On-demand install

Use `SplitInstallManager`:
```kotlin
class YtDlpInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val splitInstallManager = SplitInstallManagerFactory.create(context)

    suspend fun installYtDlp(): Boolean = suspendCancellableCoroutine { cont ->
        val request = SplitInstallRequest.newBuilder()
            .addModule("features_yt_dlp")  // module name with underscores
            .build()
        val listener = SplitInstallStateUpdatedListener { state ->
            when (state.status()) {
                SplitInstallSessionStatus.INSTALLED -> cont.resume(true)
                SplitInstallSessionStatus.FAILED -> cont.resume(false)
                else -> {}
            }
        }
        splitInstallManager.registerListener(listener)
        splitInstallManager.startInstall(request)
            .addOnFailureListener { cont.resume(false) }
        cont.invokeOnCancellation { splitInstallManager.unregisterListener(listener) }
    }
}
```

### 10.4 F-Droid consideration

F-Droid doesn't support dynamic feature module delivery. For the F-Droid build flavor, **disable the dynamic feature** and ship youtubedl-android as a normal `implementation` dep in `:download`. The full ~100 MB universal APK will be the F-Droid variant.

---

## 11. LLM (for site analysis)

**Source:** Task 1-b (sibling task) — full report at `/home/z/my-project/research/01-b-on-device-llm-android.md`.

**Recommended engine:** **LiteRT-LM** (Google's new name for MediaPipe LLM Inference).

| Coordinate | Version | Note |
|---|---|---|
| `com.google.ai.edge.litertlm:litertlm-android` | **0.14.0** | On-device LLM runtime. Google Maven (`dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml` — verified). Apache-2.0. Supports Vulkan, OpenCL, Qualcomm QNN, MediaTek NeuroPilot, Google Tensor. |

**Alternative (legacy):**
| Coordinate | Version | Note |
|---|---|---|
| `com.google.mediapipe:tasks-genai` | 0.10.35 | The original `com.google.mediapipe:mediapipe-llm-inference-android` was consolidated here. Maintenance-only — Google directs new code to LiteRT-LM. |

**Alternative (max flexibility):**
| Coordinate | Version | Note |
|---|---|---|
| `org.pytorch:executorch-android` | 1.3.1 | ExecuTorch — BSD-3-Clause. Skip for v1; watch for v2. |

The `.litertlm` model file (e.g. `gemma-3-1b-it-q4.litertlm` at 584 MB) is downloaded on-demand by the user from `huggingface.co/litert-community`. Not bundled in the APK — store in `context.filesDir/models/`.

**Wiring for site analysis (`:learn-mode` module):**
```kotlin
implementation(libs.google.ai.edge.litertlm)
// Plus a on-demand model download service in :download module
```

---

## 12. Translation (for site content i18n)

**Source:** Task 1-c (sibling task) — full report at `/home/z/my-project/research/01-c-translation-i18n.md`.

### 12.1 App-UI i18n (build-time strings)

| Coordinate | Version | Note |
|---|---|---|
| `dev.icerock.moko:resources` | **0.24.5** | KMP string resources; replaces Android `strings.xml` with a generated `MR` object. Apply the Gradle plugin `dev.icerock.moko.resources` (same version). |
| `dev.icerock.moko:resources-compose` | **0.24.5** | `MR.strings.x.stringResource()` Compose accessor. |

> Pinned to 0.24.5 (Aniyomi parity — Aniyomi pins 0.24.5 in its `libs.versions.toml`). Latest is 0.26.4 but it has breaking API changes; upgrade only if needed.

### 12.2 On-device translation (runtime, for scraped site content)

| Coordinate | Version | Note |
|---|---|---|
| `com.google.mlkit:translate` | **17.0.3** | ML Kit on-device translation — 50+ languages, ~30 MB per language model downloaded on-demand. |
| `com.google.mlkit:language-id` | **17.0.6** | ML Kit language identification — detect the language of a string before translating. |

### 12.3 Optional remote translation (DeepL)

| Coordinate | Version | Note |
|---|---|---|
| `com.github.seratch:deepl-jvm` | **0.0.4** | JitPack. DeepL HTTP API client (requires DeepL API key). Only used when ML Kit quality is insufficient. |

**Wiring (in `:source-api` for the `TranslatedString` type, and in `:source-builtin` for the actual translation):**
```kotlin
implementation(libs.google.mlkit.translate)
implementation(libs.google.mlkit.language.id)
```

ML Kit translation models are downloaded on-demand via `RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(lang).build(), conditions)`. Don't bundle models in the APK.

---

## 13. Testing

| Coordinate | Version | Note |
|---|---|---|
| `junit:junit` | **4.13.2** | JUnit 4 — base test runner. |
| `org.robolectric:robolectric` | **4.16.1** | Runs Android tests on the JVM (no emulator). Critical for CI speed — Room DAO tests, OkHttp interceptor tests, Jsoup parsing tests all run on JVM. |
| `androidx.test:core` | **1.7.0** | `androidx.test.core.app.ApplicationProvider`. |
| `androidx.test:runner` | **1.7.0** | AndroidJUnitRunner — instrumented test runner. |
| `androidx.test.ext:junit` | **1.3.0** | `AndroidJUnit4` runner (AndroidX equivalent of `junit` for instrumented tests). |
| `androidx.test.espresso:espresso-core` | **3.7.0** | Espresso UI testing for View-based screens (rarely used in Compose — only for legacy View interactions). |
| `androidx.compose.ui:ui-test-junit4` | via BOM → **1.11.4** | `createComposeRule()`, `onNodeWithText()`. The Compose-native UI test framework. |
| `androidx.compose.ui:ui-test-manifest` | via BOM → **1.11.4** | Required for `createComposeRule()` to launch a host activity. `debugImplementation` only. |
| `app.cash.turbine:turbine` | **1.2.1** | Flow testing — `flow.test { awaitItem() == X }`. The standard for testing `StateFlow`/`SharedFlow`. |
| `io.mockk:mockk` | **1.14.11** | Kotlin-native mocking (no Mockito). `mockk<...>()`, `coEvery { ... } returns ...`. |
| `io.mockk:mockk-android` | **1.14.11** | Android instrumented-test variant. |
| `io.kotest:kotest-assertions-core` | **6.2.2** | `shouldBe`, `shouldContain`, etc. — declarative assertions. Optional but recommended. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | **1.11.0** | `runTest`, `TestScope`, `advanceUntilIdle`. Required for any suspend-function test. |

**Wiring:**
```kotlin
// app/build.gradle.kts
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.assertions.core)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotest.assertions.core)

    debugImplementation(libs.androidx.compose.ui.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.ui.tooling)
}
```

**Robolectric config** (`app/src/test/resources/robolectric.properties`):
```properties
sdk=35
application=app.reverb.ReverbTestApp
```

---

## 14. CI / distribution

### 14.1 GitHub Actions setup

`.github/workflows/android.yml` — build + test + lint on every PR, release on tag push:

```yaml
name: Android CI

on:
  push:
    branches: [ main, develop ]
    tags: [ 'v*' ]
  pull_request:
    branches: [ main, develop ]

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
        with:
          # Need full history for versioning
          fetch-depth: 0

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}

      - name: Set up Rust + cargo-ndk (for adblock-rust build)
        if: matrix.flavor == 'playStore'
        uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-linux-android,armv7-linux-androideabi,x86_64-linux-android,i686-linux-android
      - run: cargo install cargo-ndk@4.1.2
        if: matrix.flavor == 'playStore'
      - name: Set up Android NDK
        uses: nttld/setup-ndk@v1
        with:
          ndk-version: r28c
      - name: Build adblock-rust .so files
        if: matrix.flavor == 'playStore'
        working-directory: adblock/rust
        run: cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 -o ../src/main/jniLibs build --release

      - name: Run unit tests
        run: ./gradlew test

      - name: Run lint
        run: ./gradlew lint

      - name: Build debug APK
        if: github.event_name == 'pull_request'
        run: ./gradlew assembleDebug

      - name: Build release AAB
        if: startsWith(github.ref, 'refs/tags/v')
        env:
          REVERB_KEYSTORE_BASE64: ${{ secrets.REVERB_KEYSTORE_BASE64 }}
          REVERB_KEYSTORE_PASSWORD: ${{ secrets.REVERB_KEYSTORE_PASSWORD }}
          REVERB_KEY_ALIAS: ${{ secrets.REVERB_KEY_ALIAS }}
          REVERB_KEY_PASSWORD: ${{ secrets.REVERB_KEY_PASSWORD }}
        run: |
          echo "$REVERB_KEYSTORE_BASE64" | base64 -d > $HOME/reverb.jks
          ./gradlew bundleRelease -Pandroid.injected.signing.store.file=$HOME/reverb.jks \
                                  -Pandroid.injected.signing.store.password=$REVERB_KEYSTORE_PASSWORD \
                                  -Pandroid.injected.signing.key.alias=$REVERB_KEY_ALIAS \
                                  -Pandroid.injected.signing.key.password=$REVERB_KEY_PASSWORD

      - uses: actions/upload-artifact@v4
        with:
          name: reverb-aab
          path: app/build/outputs/bundle/release/*.aab
```

### 14.2 Signing config (envars-based)

`app/build.gradle.kts`:
```kotlin
android {
    signingConfigs {
        create("release") {
            // Read from either Gradle properties (-P flags from CI) or environment variables
            storeFile = (findProperty("android.injected.signing.store.file") as String?)
                ?.let { file(it) }
                ?: System.getenv("REVERB_KEYSTORE_FILE")?.let { file(it) }
            storePassword = (findProperty("android.injected.signing.store.password") as String?)
                ?: System.getenv("REVERB_KEYSTORE_PASSWORD")
            keyAlias = (findProperty("android.injected.signing.key.alias") as String?)
                ?: System.getenv("REVERB_KEY_ALIAS")
            keyPassword = (findProperty("android.injected.signing.key.password") as String?)
                ?: System.getenv("REVERB_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}
```

**Local development:** use `~/.gradle/gradle.properties` for the keystore path + passwords. Never commit the keystore.

### 14.3 F-Droid metadata structure

F-Droid expects a YAML file at `metadata/<application-id>.yml` in the `fdroiddata` repo (separate from the app repo).

`metadata/app.reverb.yml`:
```yaml
Categories:
  - Internet
  - Multimedia
  - Reading
License: Apache-2.0
AuthorName: Reverb Team
AuthorWebSite: https://github.com/reverb-team
WebSite: https://github.com/reverb-team/reverb
SourceCode: https://github.com/reverb-team/reverb
IssueTracker: https://github.com/reverb-team/reverb/issues
Translation: https://github.com/reverb-team/reverb/tree/main/app/src/main/res
Changelog: https://github.com/reverb-team/reverb/releases
Donate: https://opencollective.com/reverb

AutoName: Reverb
Name: Reverb

Summary: Browser-overlay that rebuilds site UIs, detects videos, downloads them
Description: |
    Reverb is an Android browser-overlay app that:
    - Scrapes websites, rebuilds their UI natively (Material 3 + Compose)
    - Blocks ads (filter-list + Brave adblock-rust engine)
    - Detects videos via universal WebView interceptor + per-site extractors
    - Downloads videos (Media3 + aria2c + yt-dlp for the long tail)

    All scrapers run on-device. No data leaves your phone.

RepoType: git
Repo: https://github.com/reverb-team/reverb.git

Builds:
  - versionName: '1.0.0'
    versionCode: 1
    commit: v1.0.0
    subdir: app
    submodules: true
    gradle:
      - yes
    output: build/outputs/apk/fdroid/release/app-fdroid-release.apk
    srclibs:
      # If using any srclibs (rare in modern projects)
    prebuild:
      # If any prebuild steps needed
    ndk: r28c
    rm:
      # Remove proprietary deps before F-Droid build (e.g. play-services-ads, etc.)
      - adblock/src/main/jniLibs/*  # rebuild from source via cargo-ndk
    build:
      - $$cargo-ndk$$ -C ../adblock/rust -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 -o ../src/main/jniLibs build --release
    buildjni:
      - yes

MaintainerNotes: |
    - F-Droid builds must avoid proprietary deps; this build uses only Apache/MIT/GPL deps.
    - The youtubedl-android dynamic feature module is included directly in the F-Droid
      flavor (no dynamic delivery) — universal APK size ~100 MB.
    - The adblock-rust native libraries are rebuilt from source during the F-Droid build
      (cargo-ndk build step) — no prebuilt .so files accepted.

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: '1.0.0'
CurrentVersionCode: 1
```

**Key F-Droid requirements:**
1. **License** must be on F-Droid's allowed list (Apache-2.0, GPL-3.0, MIT, MPL-2.0, etc.).
2. **All deps** must also be FOSS. Proprietary deps (Google Play Services ads, Firebase Crashlytics, ML Kit closed-source APIs) get rejected.
3. **No binary blobs** unless verified reproducible. Reverb's `adblock-rust` `.so` files are rebuilt from source in the build step.
4. **ML Kit** is borderline — translation uses Google Play Services APIs (FOSS-ish but requires Google Play on device). F-Droid users without Play Services won't have translation. Document this.
5. **Reproducible builds** are encouraged — F-Droid will compare its built APK with the upstream-signed release APK; if they match, the upstream version is also published. Set `signingConfigs` so reproducible builds are possible.

### 14.4 Build flavors (Play Store vs F-Droid)

`app/build.gradle.kts`:
```kotlin
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("playStore") {
            dimension = "distribution"
            // Dynamic feature delivery for youtubedl-android
            // ML Kit via Play Services
            // LiteRT-LM model auto-download via Play Asset Delivery (Phase 3+)
        }
        create("fdroid") {
            dimension = "distribution"
            // youtubedl-android as normal implementation (no dynamic features)
            // ML Kit replaced with a stub or null-object (F-Droid users without Play Services)
            // applicationIdSuffix = ".fdroid"  (optional, to allow parallel install)
        }
    }
}
```

---

## 15. Complete `gradle/libs.versions.toml`

```toml
# Reverb Android — version catalog
# Generated by task 1-a. Verify before upgrading — see /home/z/my-project/research/01-a-tech-stack-spec.md.

[versions]
# === Build tooling ===
agp = "9.2.1"
kotlin = "2.2.21"
ksp = "2.3.10"
gradle = "9.6.1"
desugarJdkLibs = "2.1.5"

# === Compose ===
composeBom = "2026.06.01"
activityCompose = "1.13.0"
lifecycle = "2.11.0"
coreKtx = "1.19.0"
splashscreen = "1.2.0"
appcompat = "1.7.1"
materialComponents = "1.14.0"
browser = "1.10.0"
constraintLayoutCompose = "1.1.1"
kotlinxCollectionsImmutable = "0.5.1"
material3Adaptive = "1.0.0-alpha06"

# === Navigation ===
voyager = "2.2.21-1.10.3"

# === DI ===
hilt = "2.60.1"
hiltExt = "1.4.0"

# === Networking & scraping ===
okhttp = "5.4.0"
okio = "3.17.0"
jsoup = "1.22.2"
conscrypt = "2.6.0"
kotlinxSerialization = "1.11.0"
kotlinxCoroutines = "1.11.0"
quickjsKt = "1.0.5"
newpipeExtractor = "v0.26.3"

# === Database & storage ===
room = "2.8.4"
datastore = "1.2.1"
unifile = "e0def6b3dc"
documentfile = "1.1.0"

# === Media (player + download) ===
media3 = "1.10.1"
coil = "3.5.0"

# === Background work ===
workManager = "2.11.2"

# === WebView ===
webkit = "1.16.0"

# === Native bundling (Phase 2 dynamic feature) ===
youtubedlAndroid = "0.18.1"

# === Adblock (Phase 2) ===
adblockRust = "0.13.0"
cargoNdk = "4.1.2"

# === LLM (Phase 2 — from task 1-b) ===
litertLm = "0.14.0"
mediapipeGenai = "0.10.35"  # legacy, not used by default

# === Translation (from task 1-c) ===
mokoResources = "0.24.5"
mlkitTranslate = "17.0.3"
mlkitLanguageId = "17.0.6"
deeplJvm = "0.0.4"

# === Testing ===
junit4 = "4.13.2"
robolectric = "4.16.1"
androidxTestCore = "1.7.0"
androidxTestRunner = "1.7.0"
androidxTestExtJunit = "1.3.0"
espresso = "3.7.0"
turbine = "1.2.1"
mockk = "1.14.11"
kotest = "6.2.2"

# === Debug ===
leakcanary = "2.14"

# === Misc ===
aboutlibraries = "15.0.3"  # OSS-licenses screen (MPL-2.0/LGPL attribution for filter lists etc.)
shizuku = "13.1.5"        # For system-install of extension APKs (Phase 3+)


[libraries]
# === Compose BOM ===
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
# Inside the BOM (do not pin versions explicitly):
androidx-compose-ui-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-ui-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-compose-ui-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-compose-foundation-foundation = { group = "androidx.compose.foundation", name = "foundation" }
androidx-compose-material3-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material3-material3-adaptive = { group = "androidx.compose.material3", name = "material3-adaptive", version.ref = "material3Adaptive" }
androidx-compose-material3-material3-adaptive-navigation-suite = { group = "androidx.compose.material3", name = "material3-adaptive-navigation-suite" }
androidx-compose-material-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-compose-runtime-runtime = { group = "androidx.compose.runtime", name = "runtime" }

# === Core AndroidX ===
androidx-core-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-core-core-splashscreen = { group = "androidx.core", name = "core-splashscreen", version.ref = "splashscreen" }
androidx-appcompat-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
androidx-activity-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-lifecycle-process = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycle" }
androidx-browser-browser = { group = "androidx.browser", name = "browser", version.ref = "browser" }
androidx-constraintlayout-constraintlayout-compose = { group = "androidx.constraintlayout", name = "constraintlayout-compose", version.ref = "constraintLayoutCompose" }
com-google-android-material-material = { group = "com.google.android.material", name = "material", version.ref = "materialComponents" }

# === Voyager ===
voyager-navigator = { group = "cafe.adriel.voyager", name = "voyager-navigator", version.ref = "voyager" }
voyager-screenmodel = { group = "cafe.adriel.voyager", name = "voyager-screenmodel", version.ref = "voyager" }
voyager-transitions = { group = "cafe.adriel.voyager", name = "voyager-transitions", version.ref = "voyager" }
voyager-hilt = { group = "cafe.adriel.voyager", name = "voyager-hilt", version.ref = "voyager" }

# === Hilt ===
google-dagger-hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
google-dagger-hilt-android-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
androidx-hilt-hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltExt" }
androidx-hilt-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "hiltExt" }
androidx-hilt-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "hiltExt" }

# === OkHttp + networking ===
squareup-okhttp3-okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
squareup-okhttp3-okhttp-brotli = { group = "com.squareup.okhttp3", name = "okhttp-brotli", version.ref = "okhttp" }
squareup-okhttp3-logging-interceptor = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
squareup-okhttp3-okhttp-dnsoverhttps = { group = "com.squareup.okhttp3", name = "okhttp-dnsoverhttps", version.ref = "okhttp" }
squareup-okio-okio = { group = "com.squareup.okio", name = "okio", version.ref = "okio" }
org-conscrypt-conscrypt-android = { group = "org.conscrypt", name = "conscrypt-android", version.ref = "conscrypt" }

# === Jsoup + serialization + coroutines + JS ===
org-jsoup-jsoup = { group = "org.jsoup", name = "jsoup", version.ref = "jsoup" }
org-jetbrains-kotlinx-kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
org-jetbrains-kotlinx-kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
org-jetbrains-kotlinx-kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
org-jetbrains-kotlinx-kotlinx-collections-immutable = { group = "org.jetbrains.kotlinx", name = "kotlinx-collections-immutable", version.ref = "kotlinxCollectionsImmutable" }
io-github-dokar3-quickjs-kt = { group = "io.github.dokar3", name = "quickjs-kt", version.ref = "quickjsKt" }

# === NewPipeExtractor ===
newpipe-extractor = { group = "com.github.TeamNewPipe", name = "NewPipeExtractor", version.ref = "newpipeExtractor" }

# === Room + DataStore + SAF ===
androidx-room-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-datastore-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-documentfile-documentfile = { group = "androidx.documentfile", name = "documentfile", version.ref = "documentfile" }
com-github-tachiyomiorg-unifile = { group = "com.github.tachiyomiorg", name = "unifile", version.ref = "unifile" }

# === Media3 (player + download) ===
androidx-media3-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
androidx-media3-media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }
androidx-media3-media3-ui-compose = { group = "androidx.media3", name = "media3-ui-compose", version.ref = "media3" }
androidx-media3-media3-common = { group = "androidx.media3", name = "media3-common", version.ref = "media3" }
androidx-media3-media3-hls = { group = "androidx.media3", name = "media3-hls", version.ref = "media3" }
androidx-media3-media3-dash = { group = "androidx.media3", name = "media3-dash", version.ref = "media3" }
androidx-media3-media3-extractor = { group = "androidx.media3", name = "media3-extractor", version.ref = "media3" }
androidx-media3-media3-session = { group = "androidx.media3", name = "media3-session", version.ref = "media3" }
androidx-media3-media3-datasource-okhttp = { group = "androidx.media3", name = "media3-datasource-okhttp", version.ref = "media3" }
androidx-media3-media3-exoplayer-workmanager = { group = "androidx.media3", name = "media3-exoplayer-workmanager", version.ref = "media3" }
androidx-media3-media3-database = { group = "androidx.media3", name = "media3-database", version.ref = "media3" }
androidx-media3-media3-cast = { group = "androidx.media3", name = "media3-cast", version.ref = "media3" }

# === Coil 3 ===
io-coil-kt-coil3-coil-bom = { group = "io.coil-kt.coil3", name = "coil-bom", version.ref = "coil" }
io-coil-kt-coil3-coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose" }
io-coil-kt-coil3-coil-network-okhttp = { group = "io.coil-kt.coil3", name = "coil-network-okhttp" }
io-coil-kt-coil3-coil-gif = { group = "io.coil-kt.coil3", name = "coil-gif" }

# === WorkManager + WebKit ===
androidx-work-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
androidx-webkit-webkit = { group = "androidx.webkit", name = "webkit", version.ref = "webkit" }

# === youtubedl-android (Phase 2 dynamic feature) ===
io-github-junkfood02-youtubedl-android-library = { group = "io.github.junkfood02.youtubedl-android", name = "library", version.ref = "youtubedlAndroid" }
io-github-junkfood02-youtubedl-android-ffmpeg = { group = "io.github.junkfood02.youtubedl-android", name = "ffmpeg", version.ref = "youtubedlAndroid" }
io-github-junkfood02-youtubedl-android-aria2c = { group = "io.github.junkfood02.youtubedl-android", name = "aria2c", version.ref = "youtubedlAndroid" }
io-github-junkfood02-youtubedl-android-common = { group = "io.github.junkfood02.youtubedl-android", name = "common", version.ref = "youtubedlAndroid" }

# === LLM (Phase 2) ===
com-google-ai-edge-litertlm-litertlm-android = { group = "com.google.ai.edge.litertlm", name = "litertlm-android", version.ref = "litertLm" }
com-google-mediapipe-tasks-genai = { group = "com.google.mediapipe", name = "tasks-genai", version.ref = "mediapipeGenai" }

# === Translation ===
com-google-mlkit-translate = { group = "com.google.mlkit", name = "translate", version.ref = "mlkitTranslate" }
com-google-mlkit-language-id = { group = "com.google.mlkit", name = "language-id", version.ref = "mlkitLanguageId" }
dev-icerock-moko-resources = { group = "dev.icerock.moko", name = "resources", version.ref = "mokoResources" }
dev-icerock-moko-resources-compose = { group = "dev.icerock.moko", name = "resources-compose", version.ref = "mokoResources" }
com-github-seratch-deepl-jvm = { group = "com.github.seratch", name = "deepl-jvm", version.ref = "deeplJvm" }

# === Testing ===
junit-junit = { group = "junit", name = "junit", version.ref = "junit4" }
org-robolectric-robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }
androidx-test-espresso-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
app-cash-turbine-turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
io-mockk-mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
io-mockk-mockk-android = { group = "io.mockk", name = "mockk-android", version.ref = "mockk" }
io-kotest-kotest-assertions-core = { group = "io.kotest", name = "kotest-assertions-core", version.ref = "kotest" }

# === Debug ===
com-squareup-leakcanary-leakcanary-android = { group = "com.squareup.leakcanary", name = "leakcanary-android", version.ref = "leakcanary" }

# === Misc ===
com-mikepenz-aboutlibraries-compose-m3 = { group = "com.mikepenz", name = "aboutlibraries-compose-m3", version.ref = "aboutlibraries" }
dev-rikka-shizuku-api = { group = "dev.rikka.shizuku", name = "api", version.ref = "shizuku" }
com-android-tools-desugar-jdk-libs = { group = "com.android.tools", name = "desugar_jdk_libs", version.ref = "desugarJdkLibs" }


[plugins]
# === Build tooling plugins ===
com-android-application = { id = "com.android.application", version.ref = "agp" }
com-android-library = { id = "com.android.library", version.ref = "agp" }
com-android-dynamic-feature = { id = "com.android.dynamic-feature", version.ref = "agp" }
org-jetbrains-kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
org-jetbrains-kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
org-jetbrains-kotlin-plugin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
org-jetbrains-kotlin-plugin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
com-google-devtools-ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
com-google-dagger-hilt-android = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
androidx-room = { id = "androidx.room", version.ref = "room" }
dev-icerock-moko-resources = { id = "dev.icerock.moko.resources", version.ref = "mokoResources" }


[bundles]
# Compose (use BOM + bundle — modules just pull in the bundle)
compose = [
    "androidx-compose-ui-ui",
    "androidx-compose-ui-ui-graphics",
    "androidx-compose-ui-ui-tooling-preview",
    "androidx-compose-foundation-foundation",
    "androidx-compose-material3-material3",
    "androidx-compose-material3-material3-adaptive",
    "androidx-compose-material3-material3-adaptive-navigation-suite",
    "androidx-compose-material-material-icons-extended",
    "androidx-compose-runtime-runtime",
]
compose-debug = [
    "androidx-compose-ui-ui-tooling",
    "androidx-compose-ui-ui-test-manifest",
]
# Lifecycle
lifecycle = [
    "androidx-lifecycle-lifecycle-runtime-ktx",
    "androidx-lifecycle-lifecycle-runtime-compose",
    "androidx-lifecycle-lifecycle-viewmodel-compose",
    "androidx-lifecycle-lifecycle-process",
]
# Voyager (apply per-module)
voyager = [
    "voyager-navigator",
    "voyager-screenmodel",
    "voyager-transitions",
    "voyager-hilt",
]
# Hilt (apply per-module; processor goes via ksp())
hilt = [
    "google-dagger-hilt-android",
    "androidx-hilt-hilt-navigation-compose",
    "androidx-hilt-hilt-work",
]
# OkHttp + scraping
okhttp = [
    "squareup-okhttp3-okhttp",
    "squareup-okhttp3-okhttp-brotli",
    "squareup-okhttp3-okhttp-dnsoverhttps",
]
# Media3 (player + downloader)
media3 = [
    "androidx-media3-media3-exoplayer",
    "androidx-media3-media3-ui",
    "androidx-media3-media3-ui-compose",
    "androidx-media3-media3-common",
    "androidx-media3-media3-hls",
    "androidx-media3-media3-dash",
    "androidx-media3-media3-extractor",
    "androidx-media3-media3-session",
    "androidx-media3-media3-datasource-okhttp",
    "androidx-media3-media3-exoplayer-workmanager",
    "androidx-media3-media3-database",
]
# Coil 3 (BOM-pinned; module just pulls in this bundle)
coil = [
    "io-coil-kt-coil3-coil-compose",
    "io-coil-kt-coil3-coil-network-okhttp",
    "io-coil-kt-coil3-coil-gif",
]
# youtubedl-android (the dynamic feature module pulls in this bundle)
youtubedl-android = [
    "io-github-junkfood02-youtubedl-android-library",
    "io-github-junkfood02-youtubedl-android-ffmpeg",
    "io-github-junkfood02-youtubedl-android-aria2c",
    "io-github-junkfood02-youtubedl-android-common",
]
# Testing — unit
test-unit = [
    "junit-junit",
    "org-robolectric-robolectric",
    "androidx-test-core",
    "kotlinx-coroutines-test",
    "app-cash-turbine-turbine",
    "io-mockk-mockk",
    "io-kotest-kotest-assertions-core",
]
# Testing — instrumented
test-instrumented = [
    "androidx-test-ext-junit",
    "androidx-test-espresso-espresso-core",
    "androidx-test-runner",
    "androidx-compose-ui-ui-test-junit4",
    "kotlinx-coroutines-test",
    "app-cash-turbine-turbine",
    "io-mockk-mockk-android",
    "io-kotest-kotest-assertions-core",
]
```

---

## 16. `settings.gradle.kts` skeleton (final form)

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Reverb"

// === Core modules ===
include(":core:common")         // JVM lib — utils, URL helpers, Result wrappers
include(":core:network")        // Android lib — OkHttp client + interceptor chain (UA, rate-limit, CF, DoH, Brotli)
include(":core:html")           // JVM lib — Jsoup + QuickJS bridge + Dean-Edwards unpacker
include(":core:video")          // JVM lib — HLS master parser, DASH manifest parser, PlaylistUtils

// === Source API ===
include(":source-api")          // KMP — the Site interface + DTOs (zero Android deps)

// === Source implementations ===
include(":source-universal")    // Android lib — universal WebView interceptor (Reverb's moat)
include(":source-newpipe")      // Android lib — NewPipeExtractor adapter (GPL-3.0 isolation)
include(":source-builtin")      // Android lib — 5–10 hand-written SiteModules
include(":source-loader")       // Android lib (Phase 2+) — external APK extension loading

// === Theme/extractor libraries ===
include(":lib:animestreamtheme")              // multi-source theme for animekhor/animexin/donghuastream
include(":lib:animestreammirrordecoder")      // ~20-LOC base64 mirror decoder
include(":lib:securepipeclient")              // miruro.to secure-pipe client
include(":lib:anilistcatalog")                // AniList GraphQL catalog fallback

// === App layers ===
include(":player")              // Android lib — Media3 wrapper
include(":download")            // Android lib — WorkManager + Media3 DownloadService + aria2c + queue
include(":data")                // Android lib — Room DB + DataStore + repositories
include(":ui")                  // Android lib (Compose) — all screens, design system, Voyager nav
include(":adblock")             // Android lib — AdMatcher + OkHttp interceptor + WebView blocker
include(":learn-mode")          // Android lib (Compose, Phase 2) — "Teach Reverb this site" UI

// === App + dynamic features ===
include(":app")                 // Android app — Application, Hilt wiring, single-activity shell
include(":features:yt-dlp")     // Dynamic feature (Phase 2) — youtubedl-android bundled native
```

---

## 17. `app/build.gradle.kts` skeleton

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.com.android.application)
    alias(libs.plugins.org.jetbrains.kotlin.android)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.compose)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.com.google.devtools.ksp)
    alias(libs.plugins.com.google.dagger.hilt.android)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "app.reverb"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.reverb"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // ABI splits — keep APKs small for the base app (without youtubedl-android)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    // === Build types ===
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signing config — see §14.2 (envar-driven)
            signingConfig = signingConfigs.create("release").apply {
                storeFile = (findProperty("android.injected.signing.store.file") as String?)?.let { file(it) }
                    ?: System.getenv("REVERB_KEYSTORE_FILE")?.let { file(it) }
                storePassword = (findProperty("android.injected.signing.store.password") as String?)
                    ?: System.getenv("REVERB_KEYSTORE_PASSWORD")
                keyAlias = (findProperty("android.injected.signing.key.alias") as String?)
                    ?: System.getenv("REVERB_KEY_ALIAS")
                keyPassword = (findProperty("android.injected.signing.key.password") as String?)
                    ?: System.getenv("REVERB_KEY_PASSWORD")
            }
        }
    }

    // === Flavors — Play Store vs F-Droid ===
    flavorDimensions += "distribution"
    productFlavors {
        create("playStore") {
            dimension = "distribution"
            // Dynamic feature delivery for youtubedl-android
            // ML Kit via Play Services
        }
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            versionNameSuffix = "-fdroid"
            // No dynamic features (F-Droid doesn't support them)
            // No proprietary deps
        }
    }

    // === Dynamic features ===
    dynamicFeatures += setOf(":features:yt-dlp")

    // === Bundle (AAB) splits ===
    bundle {
        language { enableSplit = true }
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }

    // === Compile options ===
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
            freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
            freeCompilerArgs.add("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true  // required for AGP 9.x
        aidl = false
        renderScript = false
        shaders = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
        // youtubedl-android needs native libs extracted at runtime
        jniLibs {
            useLegacyPackaging = true  // required for youtubedl-android's runtime .so extraction
        }
    }

    // === Room schema export ===
    room {
        schemaDirectory("$projectDir/schemas")
    }

    // === Test options ===
    testOptions {
        unitTests {
            isIncludeAndroidResources = true  // Robolectric
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // === Core desugaring (java.time on API 24-25) ===
    coreLibraryDesugaring(libs.com.android.tools.desugar.jdk.libs)

    // === Module deps ===
    implementation(project(":ui"))
    implementation(project(":data"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":player"))
    implementation(project(":download"))
    implementation(project(":adblock"))
    implementation(project(":source-api"))
    implementation(project(":source-universal"))
    implementation(project(":source-builtin"))
    // NewPipe is GPL-3.0 — isolated in its own module
    playStoreImplementation(project(":source-newpipe"))

    // === Core AndroidX ===
    implementation(libs.androidx.core.core.ktx)
    implementation(libs.androidx.core.core.splashscreen)
    implementation(libs.androidx.appcompat.appcompat)
    implementation(libs.androidx.activity.activity.compose)
    implementation(libs.androidx.browser.browser)
    implementation(libs.com.google.android.material.material)
    implementation(libs.org.jetbrains.kotlinx.kotlinx.collections.immutable)
    implementation(libs.androidx.constraintlayout.constraintlayout.compose)

    // === Lifecycle bundle ===
    implementation(libs.bundles.lifecycle)

    // === Compose (via BOM) ===
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.bundles.compose.debug)

    // === Voyager ===
    implementation(libs.bundles.voyager)

    // === Hilt ===
    implementation(libs.bundles.hilt)
    ksp(libs.google.dagger.hilt.android.compiler)
    ksp(libs.androidx.hilt.hilt.compiler)

    // === Networking + scraping ===
    implementation(libs.bundles.okhttp)
    implementation(libs.squareup.okio.okio)
    implementation(libs.squareup.okhttp3.logging.interceptor)  // debug only — exclude in release
    implementation(libs.org.conscrypt.conscrypt.android)
    implementation(libs.org.jsoup.jsoup)
    implementation(libs.org.jetbrains.kotlinx.kotlinx.serialization.json)
    implementation(libs.org.jetbrains.kotlinx.kotlinx.coroutines.android)
    implementation(libs.io.github.dokar3.quickjs.kt)

    // === Storage ===
    implementation(libs.androidx.room.room.runtime)
    implementation(libs.androidx.room.room.ktx)
    ksp(libs.androidx.room.room.compiler)
    implementation(libs.androidx.datastore.datastore.preferences)
    implementation(libs.androidx.documentfile.documentfile)
    implementation(libs.com.github.tachiyomiorg.unifile)

    // === WorkManager + WebKit ===
    implementation(libs.androidx.work.work.runtime.ktx)
    implementation(libs.androidx.webkit.webkit)

    // === Media ===
    implementation(libs.bundles.media3)
    implementation(platform(libs.io.coil.kt.coil3.coil.bom))
    implementation(libs.bundles.coil)

    // === Translation (Phase 2) ===
    implementation(libs.com.google.mlkit.translate)
    implementation(libs.com.google.mlkit.language.id)
    implementation(libs.dev.icerock.moko.resources)
    implementation(libs.dev.icerock.moko.resources.compose)

    // === AboutLibraries (OSS attributions) ===
    implementation(libs.com.mikepenz.aboutlibraries.compose.m3)

    // === Debug ===
    debugImplementation(libs.com.squareup.leakcanary.leakcanary.android)

    // === Testing — unit ===
    testImplementation(libs.bundles.test.unit)
    testImplementation(project(":core:common"))  // for shared test fixtures

    // === Testing — instrumented ===
    androidTestImplementation(libs.bundles.test.instrumented)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
```

---

## 18. Module dependency graph

```
                                ┌─────────────────────────────┐
                                │       :core:common          │  (JVM lib)
                                │  utils, URL helpers, Result │
                                └─────────────┬───────────────┘
                                              │ (depended on by everyone)
                ┌─────────────────────────────┼─────────────────────────────┐
                │                             │                             │
                ▼                             ▼                             ▼
   ┌──────────────────────┐      ┌──────────────────────┐      ┌──────────────────────┐
   │   :core:network      │      │     :core:html       │      │    :core:video       │
   │ OkHttp + interceptors│      │ Jsoup + QuickJS      │      │ HLS/DASH parser      │
   │ (UA, RL, CF, DoH)    │      │ Dean-Edwards unpacker│      │ PlaylistUtils        │
   └──────────┬───────────┘      └──────────┬───────────┘      └──────────┬───────────┘
              │                             │                             │
              └──────────────┬──────────────┴──────────────┬──────────────┘
                             │                             │
                             ▼                             ▼
                ┌─────────────────────────────────────────────────────┐
                │              :source-api (KMP)                       │
                │  Site interface + CataloguePage/MediaItem/VideoRef   │
                └────────────────────────┬────────────────────────────┘
                                         │
        ┌────────────────────────────────┼────────────────────────────────┐
        │                                │                                │
        ▼                                ▼                                ▼
┌──────────────────┐          ┌──────────────────┐              ┌──────────────────────┐
│ :source-universal│          │  :source-builtin │              │   :source-newpipe    │
│ WebView univ.    │          │ 5–10 hand-written│ (GPL-3.0     │ NewPipeExtractor     │
│ interceptor      │          │ SiteModules      │  isolation)  │ adapter              │
└────────┬─────────┘          └────────┬─────────┘              └──────────┬───────────┘
         │                             │                                   │
         │   ┌─────────────────────────┘                                   │
         │   │  (source-builtin depends on theme/extractor libs)            │
         │   ▼                                                              │
         │  ┌────────────────────────────────────────────────┐              │
         │  │ :lib:animestreamtheme  (depends on :lib:        │              │
         │  │                          animestreammirrordecoder)│             │
         │  │ :lib:securepipeclient                          │              │
         │  │ :lib:anilistcatalog (depends on :core:network)  │              │
         │  └────────────────────────────────────────────────┘              │
         │                                                                  │
         ▼                                                                  ▼
┌──────────────────┐                                              ┌──────────────────┐
│   :source-loader  │  (Phase 2 — external APK extensions)         │                  │
│  PathClassLoader  │                                              │                  │
└──────────────────┘                                              │                  │
                                                                  │                  │
┌──────────────────┐                                              │                  │
│    :adblock       │ ←──── depends on :core:network              │                  │
│ AdMatcher + Rust  │       (shares the OkHttp interceptor slot)  │                  │
└─────────┬────────┘                                              │                  │
          │                                                       │                  │
          ▼                                                       ▼                  ▼
┌──────────────────┐         ┌──────────────────┐         ┌──────────────────────────────┐
│   :player         │         │   :download      │         │        :data                 │
│ Media3 wrapper    │         │ Task state       │         │ Room + DataStore + repos     │
└─────────┬────────┘         │ machine +        │         └──────────────┬───────────────┘
          │                  │ OkHttp/aria2c/   │                        │
          │                  │ Media3 backends  │                        │
          │                  └─────────┬────────┘                        │
          │                            │                                 │
          └──────────┬─────────────────┘                                 │
                     │                                                   │
                     ▼                                                   │
         ┌──────────────────────────────────┐                            │
         │         :ui (Compose)            │ ←──── depends on everything│
         │ Voyager nav, screens, design sys │                            │
         └─────────────┬────────────────────┘                            │
                       │                                                 │
                       ▼                                                 │
         ┌──────────────────────────────────┐                            │
         │      :learn-mode (Phase 2)       │ ──── :litert-lm model      │
         │ "Teach Reverb this site" UI      │                            │
         └─────────────┬────────────────────┘                            │
                       │                                                 │
                       ▼                                                 ▼
         ┌──────────────────────────────────────────────────────────────────┐
         │                            :app                                  │
         │  Application, Hilt wiring, single-activity shell, manifest        │
         │  Pulls in: :ui + :data + :core:* + :player + :download + :adblock│
         │           + :source-* + :learn-mode                               │
         └──────────────────────────────────────────────────────────────────┘
                                          ▲
                                          │ (dynamic feature, on-demand)
                                          │
         ┌──────────────────────────────────────────────────────────────────┐
         │              :features:yt-dlp (dynamic feature, Phase 2)         │
         │  youtubedl-android (library + ffmpeg + aria2c + common)          │
         │  ~75 MB / ABI native payload                                     │
         └──────────────────────────────────────────────────────────────────┘
```

**Dependency rules:**
1. `:core:common` depends on **nothing** (except Kotlin stdlib + kotlinx.coroutines).
2. `:source-api` (KMP) depends on **only** `:core:common`. Zero Android deps — this is the contract.
3. `:source-*` modules depend on `:source-api` + `:core:network` + `:core:html` + `:core:video`.
4. `:adblock` depends on `:core:network` (shares the OkHttpClient interceptor slot) and `:source-universal` (decorates the WebViewClient).
5. `:player` depends on `:core:video` only.
6. `:download` depends on `:core:video` + `:source-api` (uses `VideoRef`/`DetectedStream`).
7. `:data` depends on `:core:common` (Room entities are simple data classes).
8. `:ui` depends on **everything** above.
9. `:app` depends on `:ui` + `:data` + the players + downloaders + ad-blocker + all sources + `:learn-mode`.
10. `:features:yt-dlp` depends on `:app` (required by AGP for dynamic-feature modules — it has access to the base app's classes via `implementation(project(":app"))`).

---

## 19. `gradle.properties` (root)

```properties
# === Gradle / JVM ===
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -XX:+UseParallelGC
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.daemon=true

# === AndroidX ===
android.useAndroidX=true
android.nonTransitiveRClass=true

# === Kotlin / KSP ===
kotlin.code.style=official
ksp.useKSP2=true

# === Compose ===
android.defaults.buildfeatures.buildconfig=true
android.nonFinalResIds=true

# === Misc ===
android.suppressUnsupportedCompileSdk=36
```

---

## 20. Day-one scaffold checklist

For the lead developer on day one:

1. **Set up JDK 21** (`java -version` → 21.x.x).
2. **Install Android SDK 36 + NDK r28c** + Android Studio preview (Koala++ or later that supports AGP 9.x).
3. **Clone repo, create the 20 modules** per §16. Empty `build.gradle.kts` for each, just enough to compile.
4. **Drop `gradle/libs.versions.toml`** from §15 into `gradle/`.
5. **Drop `settings.gradle.kts`** from §16 at root.
6. **Drop `gradle.properties`** from §19 at root.
7. **Drop `app/build.gradle.kts`** from §17.
8. **Run `./gradlew wrapper --gradle-version=9.6.1`** to pin the wrapper.
9. **Run `./gradlew assembleDebug`** — should succeed with zero source files (modules are empty stubs).
10. **Add a `MainActivity` + `Application` + `AndroidManifest.xml`** to `:app` — minimal "Hello Reverb" Compose activity.
11. **Run on emulator** — verify Hilt + Voyager + Compose boot.
12. **Set up CI** per §14.1 — green build on first PR.
13. **Begin Phase 0 spike** (PLAN.md §8) — universal extractor in `:source-universal`.

---

## Appendix A — version-discovery cheatsheet

When upgrading a library in the future, here's where to look:

| Library family | Maven metadata URL |
|---|---|
| AndroidX (all) | `https://dl.google.com/dl/android/maven2/<group-as-path>/<artifact>/maven-metadata.xml` |
| Maven Central (Kotlin, OkHttp, Jsoup, Conscrypt, Coil, Voyager, Room post-2.7, Hilt, kotlinx libs, youtubedl-android, dokar3 quickjs) | `https://repo1.maven.org/maven2/<group-as-path>/<artifact>/maven-metadata.xml` |
| JitPack (UniFile, NewPipeExtractor, deepl-jvm) | `https://jitpack.io/api/builds/<group>/<artifact>` |
| Gradle current | `https://services.gradle.org/versions/current` |
| AGP releases | `https://developer.android.com/build/releases/about-agp` (HTML, JS-rendered) |
| Kotlin releases | `https://kotlinlang.org/docs/releases.html` (HTML) |
| KSP releases | `https://github.com/google/ksp/releases` (HTML) |
| Compose BOM release notes | `https://developer.android.com/jetpack/compose/bom/bom-mapping` (HTML) |
| Cargo crates (adblock-rust, cargo-ndk) | `https://crates.io/api/v1/crates/<name>` |
| Compose Multiplatform releases (for Voyager versioning) | `https://github.com/JetBrains/compose-multiplatform/releases` |
| Material 3 release notes | `https://developer.android.com/jetpack/androidx/releases/compose-material3` |

For a quick "what's the latest stable" check, fetch the maven-metadata.xml and:
- If `<release>` is non-alpha/non-beta/non-rc, that's your stable version.
- If `<release>` is alpha/beta/rc, look at the `<versions>` list and pick the latest non-alpha/beta/rc.

---

## Appendix B — what's deliberately NOT in the stack (and why)

| Considered | Rejected because |
|---|---|
| `androidx.navigation:navigation-compose` | Voyager matches Aniyomi; simpler for deep screen graphs; less boilerplate. |
| `com.google.dagger.hilt:hilt-android-gradle-plugin` + KAPT | Hilt 2.60.1 + KSP2 replaces KAPT — faster builds, no deprecated plugin. |
| `com.arthenica:ffmpeg-kit-full` | Archived April 2025; binaries removed from Maven Central. Use youtubedl-android's bundled ffmpeg instead. |
| `com.arthenica:ffmpeg-kit-next` | Source-only (Nix-built). Too much CI complexity for v1. |
| `app.cash.quickjs:quickjs-android` | Last published 2021-08; effectively dead. Use `io.github.dokar3:quickjs-kt` instead. |
| `com.squareup.retrofit2:retrofit` | Overkill — Reverb's scrapers don't have a fixed REST surface; raw OkHttp + kotlinx.serialization is more flexible. |
| `io.insert-koin:koin-android` | Hilt's compile-time safety wins over Koin's runtime DSL for a 100+ binding app. |
| `org.jetbrains.kotlinx.experimental:` (any) | Experimental APIs — wait for stable. |
| `androidx.paging:paging-compose` | Overkill for ~50-item catalog pages; manual `LazyColumn` + paging is fine. Add if/when needed. |
| `io.coil-kt:coil` (Coil 2.x) | Coil 3 is stable, KMP-ready, OkHttp-backed. Use 3.x. |
| `com.google.firebase:*` | Proprietary; F-Droid rejects. Use self-hosted Sentry (or skip crash reporting in v1). |
| `androidx.glance:*` | App widgets — defer to Phase 3. |
| `com.airbnb.android:lottie-compose` | No Lottie animations in v1; Compose-native animations suffice. |

---

## Appendix C — things that might break and how to fix them

| Symptom | Likely cause | Fix |
|---|---|---|
| `KSP2: error: cannot find symbol` for Hilt-generated classes | KSP not running before Kotlin compile | Add `ksp(libs.google.dagger.hilt.android.compiler)` to ALL modules that use `@HiltAndroidApp`, `@HiltViewModel`, `@HiltWorker`, or `@AndroidEntryPoint` (not just `:app`). |
| `compose-bom: not found` | `google()` repo missing from `dependencyResolutionManagement.repositories` | Add it. The BOM is on Google Maven only. |
| `cafe.adriel.voyager:*: not found` | Maven Central missing OR version mismatch with Kotlin | Verify Kotlin version = 2.2.21 (matches Voyager's `2.2.21-1.10.3`). |
| `com.github.tachiyomiorg:unifile: not found` | JitPack repo missing OR commit hash wrong | Add `maven("https://jitpack.io")` to `dependencyResolutionManagement.repositories`. Verify the commit hash is valid: `curl https://jitpack.io/api/builds/com.github.tachiyomiorg/unifile` |
| `app.cash.quickjs:quickjs-android:0.9.2: not found` | (Won't happen — but if you mistakenly added it) | Use `io.github.dokar3:quickjs-kt:1.0.5` instead. |
| `org.conscrypt:conscrypt-android:*: not loaded at runtime` | Provider not inserted | Call `Security.insertProviderAt(Conscrypt.newProvider(), 1)` in `Application.onCreate` before building OkHttp client. |
| `libreverb_adblock.so: not found` | cargo-ndk build not run / .so files not in `jniLibs/` | Run `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 -o ../src/main/jniLibs build --release` from `:adblock/rust/`. |
| youtubedl-android: `YoutubeDL.getInstance().init()` throws `YoutubeDLException` | `android:extractNativeLibs="true"` not set in manifest | Add to `:features:yt-dlp/src/main/AndroidManifest.xml`: `<application android:extractNativeLibs="true" ...>`. Also set `useLegacyPackaging = true` in `packaging.jniLibs`. |
| Media3 DownloadService: `foregroundServiceType` not allowed | Missing manifest declaration | Add `android:foregroundServiceType="dataSync"` to the service. Also request permission: `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` (Android 14+). |
| Room: `Cannot find implementation for XxxDao. XxxDao_Impl does not exist` | KSP not applied to the module | Add `id("com.google.devtools.ksp")` to `:data` module's `build.gradle.kts` AND `ksp(libs.androidx.room.room.compiler)` to deps. |
| F-Droid build fails: "proprietary dep detected" | `com.google.mlkit:translate` requires Play Services | Use the `fdroid` flavor: replace `libs.com.google.mlkit.translate` with a stub. Or skip translation in the F-Droid build. |

---

*End of task 1-a report. Cross-references: PLAN.md §5 (tech stack, original), §16 (ad-blocking contract from task 2-d), §17 (site analysis from tasks 4-a/4-b), §19 (20-module grid from ORCHESTRATOR-v2). Sibling research files: 01-b-on-device-llm-android.md, 01-c-translation-i18n.md.*
