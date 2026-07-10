# Anatomy of an Aniyomi/Anikku Anime Extension — `yuzono/anime-extensions`

> Research task **2-b**. Goal: understand the concrete structure of a single site‑scraper
> "extension" well enough to design a homegrown scraping browser.

---

## 1. Repository overview

| Item | Value |
|---|---|
| URL | https://github.com/yuzono/anime-extensions |
| Host app(s) | [Anikku](https://github.com/komikku-app/anikku) and [Aniyomi](https://github.com/aniyomiorg/aniyomi) (community forks of the original Tachiyomi anime branch) |
| License | Apache-2.0 (copyright 2015 Javier Tomás — inherited from Tachiyomi) |
| Stars / Forks | ~295 / ~130 |
| Commits | 5,396 |
| Contributors | 341 + 327 |
| Languages | **Kotlin 98 %**, JavaScript 2 % (the JS is only the bundled [Synchrony](https://github.com/relative/synchrony) deobfuscator shipped inside `lib/synchrony/assets/`) |
| Activity | Very active — multiple commits per day, latest commit `Alpha-782 MonosChinos [ES]: spotlessApply (#616)` (2026‑07‑10) |
| Extension count | ≈ 60+ across 17 language folders (`all`, `ar`, `de`, `en`, `es`, `fr`, `hi`, `id`, `it`, `ko`, `pl`, `pt`, `ru`, `sr`, `tr`, `uk`, `zh`). The English (`src/en/`) folder alone has ≈ 55 sites (animepahe, aniwave, kisskh, allanime, wcostream, hanime, etc.) |

What it actually is: a **monorepo of independent Android-Application modules** that the host anime-watcher app downloads as APKs, installs, and reflectively loads as `AnimeSource` plugins. Architecturally identical to the older `tachiyomi-extensions` / `aniyomi-extensions` repos, but maintained by the Yūzōnō group for the Anikku/Aniyomi forks. (Not affiliated with upstream — the README explicitly disclaims it.)

---

## 2. Top-level directory layout

```
anime-extensions/
├── .github/            # CI workflows + Python scripts that build & publish the APK repo
├── common/             # Shared AndroidManifest.xml (empty stub) + ProGuard rules
├── core/               # Kotlin helpers available to ALL extensions without a Gradle dep
│                       # (keiyoushi.utils.*: parseAs, toJsonBody, tryParse, useAsJsoup,
│                       #  NextJs extraction, UrlUtils, GraphQL helpers, etc.)
├── gradle/
│   ├── build-logic/    # Convention plugins: PluginExtensionLegacy, PluginLibrary,
│   │                   # PluginMultiSrc, PluginAndroidBase, PluginSpotless
│   ├── libs.versions.toml
│   └── kei.versions.toml
├── lib/                # ~70 reusable Gradle library modules (one per video host
│                       # extractor + generic utilities). Examples:
│                       #   streamtapeextractor, doodextractor, gogostreamextractor,
│                       #   mp4uploadextractor, streamwishextractor, vidmolyextractor,
│                       #   universalextractor, playlistutils, m3u8server,
│                       #   cloudflareinterceptor, cookieinterceptor, randomua,
│                       #   synchrony, unpacker, cryptoaes, lzstring, …
├── lib-multisrc/       # ~11 "theme" libraries for sites that share a CMS backend:
│                       #   anikototheme, animekaitheme, zorotheme, dooplay, dopeflix,
│                       #   pelisplus, wcotheme, yflixtheme, datalifeengine, animestream,
│                       #   anilist
├── src/<lang>/<site>/  # ONE Gradle module per site scraper
├── template/           # Markdown README templates for new extensions
├── settings.gradle.kts # Auto-includes every src/<lang>/<site> + every lib/* + lib-multisrc/*
└── build.gradle.kts    # Root: applies spotless, build-logic
```

### How modules are wired up — `settings.gradle.kts`

```kotlin
// Load all modules under /lib
File(rootDir, "lib").eachDir { include("lib:${it.name}") }

// Load all modules under /lib-multisrc
File(rootDir, "lib-multisrc").eachDir { include("lib-multisrc:${it.name}") }

// Load all individual extensions under src/<lang>/<site>
fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->           // each language
        dir.eachDir { subdir ->                      // each site
            loadIndividualExtension(dir.name, subdir.name)
        }
    }
}
fun loadIndividualExtension(lang: String, name: String) {
    include("src:$lang:$name")
}
```

So **one folder = one Gradle module = one site = one APK**.

---

## 3. Extension file structure (canonical)

From `CONTRIBUTING.md`:

```
src/<lang>/<mysourcename>/
├── AndroidManifest.xml            (optional — only for deep-link UrlActivity)
├── build.gradle                   (Groovy — NOT kts — for historical reasons)
├── res/
│   ├── mipmap-{m,h,xh,xxh,xxxh}dpi/
│   │   └── ic_launcher.png         (square rounded-corner icon, 5 sizes)
└── src/
    └── eu/kanade/tachiyomi/animeextension/<lang>/<mysourcename>/
        ├── <MySourceName>.kt       (main class — must extend AnimeHttpSource
        │                            or AnimeSourceFactory, registered in build.gradle)
        ├── <Dto>.kt                (optional — kotlinx.serialization models)
        ├── <Filters>.kt            (optional — AnimeFilterList for search)
        ├── <UrlActivity>.kt        (optional — receives deep-link intents)
        └── extractors/             (optional — site-specific server extractors)
```

The package `eu.kanade.tachiyomi.animeextension.<lang>.<mysourcename>` is **mandatory** — the host app loads the class by its fully-qualified name.

### Minimal `build.gradle`

```groovy
ext {
    extName        = 'KissKH'
    extClass       = '.KissKH'      // resolved relative to the package above
    extVersionCode = 5              // MUST be bumped on every user-visible change
    isNsfw         = true           // mandatory explicit true|false
}

apply plugin: "kei.plugins.extension.legacy"

// optional — pull in shared extractor / utility libs:
dependencies {
    implementation(project(':lib:playlistutils'))
    implementation(project(':lib:doodextractor'))
    // …
}
```

### `build.gradle` for an extension that uses a multi-source theme

```groovy
ext {
    extName           = 'AniWave (Unoriginal)'
    extClass          = '.AniWave'
    themePkg          = 'anikototheme'      // lib-multisrc module to inherit from
    baseUrl           = 'https://animewave.to'
    overrideVersionCode = 2                 // final code = theme.baseVersionCode + this
}
apply plugin: "kei.plugins.extension.legacy"
```

The actual `AniWave.kt` is then trivial — it just calls the theme's constructor:

```kotlin
package eu.kanade.tachiyomi.animeextension.en.aniwave

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme

class AniWave :
    AnikotoTheme(
        "en",
        "AniWave (Unoriginal)",
        domainEntries = listOf("animewave.to", "aniwave.cz"),
        hosterNames   = listOf("HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream", "VidPlay-1"),
    )
```

---

## 4. Metadata declaration format

There are **two layers** of metadata:

### 4a. Build-time metadata (in `build.gradle` `ext { … }`)

| Field              | Purpose                                                                                       |
|--------------------|-----------------------------------------------------------------------------------------------|
| `extName`          | Display name (romanized if non-English). Must be < 0x180 code-points.                          |
| `extClass`         | Class implementing `AnimeSource`. **Must start with `.`** (resolved against the module namespace `eu.kanade.tachiyomi.animeextension`). |
| `extVersionCode`   | Positive int, bumped per code change. Final `versionName` is auto-generated as `"14.$extVersionCode"`. |
| `isNsfw`           | `true`/`false`. Falls back to `false`. Surfaces in the APK manifest as `tachiyomi.animeextension.nsfw`. |
| `themePkg`         | (optional) Name of `lib-multisrc/<x>` module to inherit from.                                 |
| `baseUrl`          | (optional, theme only) Hardcoded base URL — written into the APK manifest as `SOURCEHOST` + `SOURCESCHEME` for deep-linking. |
| `overrideVersionCode` | (theme only) Final `versionCode = theme.baseVersionCode + overrideVersionCode`.            |

### 4b. Runtime metadata (class fields)

Implemented by overriding `AnimeHttpSource` properties:

```kotlin
override val name          = "KissKH"
override val lang          = "en"        // ISO-639-1 (use "all" for multi-language)
override val baseUrl       = "https://kisskh.ovh"
override val supportsLatest = true
override val id : Long     = …           // optional — only override to preserve ID across renames
override val versionId     = …           // optional — bump only when URL structure changes
```

### 4c. How the metadata reaches the host app

`PluginExtensionLegacy.kt` (the `kei.plugins.extension.legacy` convention plugin) wires everything:

```kotlin
plugins {
    alias(libs.plugins.android.application)   // ← every extension is an ANDROID APP, not a library
    alias(libs.plugins.kotlin.serialization)
    alias(kei.plugins.android.base)
    alias(kei.plugins.spotless)
}

android {
    namespace = "eu.kanade.tachiyomi.animeextension"

    defaultConfig {
        applicationIdSuffix = project.parent?.name + "." + project.name
                           //  e.g. "en.kisskh"  → full applicationId:
                           //       eu.kanade.tachiyomi.animeextension.en.kisskh
        versionCode = if (theme == null) extVersionCode
                      else theme.baseVersionCode + overrideVersionCode
        versionName = "14.$versionCode"
        base { archivesName.set("aniyomi-$applicationIdSuffix-v$versionName") }

        manifestPlaceholders += mapOf(
            "appName" to "Aniyomi: $extName",
            "extClass" to extClass,
            "nsfw"     to if (isNsfw) 1 else 0,
        )
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = true                              // R8/ProGuard ON
            proguardFiles(rootProject.file("common/proguard-rules.pro"))
        }
        configureEach {
            buildConfigField("String", "KISSKH_API",     "\"https://…\"") // per-ext BuildConfig constants
        }
    }
}
```

The manifest comes from `core/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="tachiyomi.animeextension" />   <!-- ← discovery marker -->

    <application android:icon="@mipmap/ic_launcher" android:label="${appName}">
        <meta-data android:name="tachiyomi.animeextension.class" android:value="${extClass}" />
        <meta-data android:name="tachiyomi.animeextension.nsfw"  android:value="${nsfw}" />
    </application>
</manifest>
```

So at install time the APK carries:
- `<uses-feature android:name="tachiyomi.animeextension" />` — feature flag for `PackageManager.queryIntentServices` style discovery.
- `<meta-data android:name="tachiyomi.animeextension.class" value=".KissKH" />` — entry-point class name (resolved against the APK's package id).
- `<meta-data android:name="tachiyomi.animeextension.nsfw" value="1" />` — NSFW flag.

---

## 5. Anatomy of one extension in detail — **KissKH** (`src/en/kisskh`)

KissKH is the cleanest example in the repo because the site ships a public JSON API — so the extension is essentially "build URL → GET → JSON parse → return `Video`". Two files only.

### 5a. File tree

```
src/en/kisskh/
├── build.gradle
├── res/mipmap-*/ic_launcher.png   (5 densities)
└── src/eu/kanade/tachiyomi/animeextension/en/kisskh/
    ├── KissKH.kt          ← main class, ~250 lines
    └── SubDecryptor.kt    ← AES-CBC subtitle blob decryptor, ~120 lines
```

### 5b. `build.gradle`

```groovy
ext {
    extName        = 'KissKH'
    extClass       = '.KissKH'
    extVersionCode = 5
    isNsfw         = true
}
apply plugin: "kei.plugins.extension.legacy"
```

### 5c. `KissKH.kt` — full walkthrough

**Imports / base class:**

```kotlin
package eu.kanade.tachiyomi.animeextension.en.kisskh

import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.model.*         // SAnime, SEpisode, Video, Track, AnimesPage, AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.*                                // parseAs, getPreferencesLazy, parallelCatchingMapNotNull, …
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*                     // Json, JsonObject, JsonArray, jsonPrimitive, …
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

class KissKH :
    AnimeHttpSource(),            // ← THE base class. Provides: client (OkHttpClient),
                                  //   headers, network, GET()/POST() helpers, etc.
    ConfigurableAnimeSource {     // ← opt-in: lets the user pick a mirror in Settings

    override val name = "KissKH"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val preferences by getPreferencesLazy()

    // baseUrl is CONFIGURABLE — mirrors selectable in prefs
    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
    // → "https://kisskh.ovh" by default
```

**Key observations about the base class `AnimeHttpSource`:**
- Provides `client: OkHttpClient` (pre-wired with the app's network stack including the default Cloudflare-solving interceptor)
- Provides `headers: Headers` (default User-Agent + Accept headers via `headersBuilder()`)
- Provides `network: NetworkHelper` (access to `network.client` which auto-solves Cloudflare)
- Provides helpers `GET(url, headers, cache)` / `POST(url, headers, body, cache)`
- Provides `response.asJsoup()` / `response.useAsJsoup()` for Jsoup parsing
- Declares abstract methods you must implement (see "call flow" below)

**Popular anime — pagination via JSON:**

```kotlin
override fun popularAnimeRequest(page: Int): Request =
    GET("$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=1&pageSize=40")

override fun popularAnimeParse(response: Response): AnimesPage {
    val jObject = json.decodeFromString<JsonObject>(response.body.string())
    val lastPage = jObject["totalCount"]?.jsonPrimitive?.int
    val page     = jObject["page"]?.jsonPrimitive?.int
    val hasNextPage = if (lastPage != null && page != null) page < lastPage else false

    val animeList = jObject["data"]?.jsonArray?.mapNotNull { item ->
        SAnime.create().apply {
            title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val animeId   = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val titleURI  = title.replace(titleUriRegex, "-")
            url           = "/Drama/$titleURI?id=$animeId"     // relative — setUrlWithoutDomain convention
            thumbnail_url = item.jsonObject["thumbnail"]?.jsonPrimitive?.content
        }
    } ?: emptyList()
    return AnimesPage(animeList, hasNextPage)
}
```

**Latest** (mirrors popular with a different `order` query param):

```kotlin
override fun latestUpdatesRequest(page: Int) =
    GET("$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=2&pageSize=40")
override fun latestUpdatesParse(response: Response) = parsePopularAnimeJson(response.body.string())
```

**Search:**

```kotlin
override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
    GET("$baseUrl/api/DramaList/Search?q=$query&type=0")
override fun searchAnimeParse(response: Response): AnimesPage { /* JSON array → list */ }
```

**Anime details:**

```kotlin
override fun animeDetailsRequest(anime: SAnime): Request {
    val id = anime.url.substringAfter("id=").substringBefore("&")
    return GET("$baseUrl/api/DramaList/Drama/$id?isq=false", headers)
}
override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
    val j = json.decodeFromString<JsonObject>(response.body.string())
    j["title"]?.jsonPrimitive?.content?.let { title = it }
    j["status"]?.jsonPrimitive?.content?.let { status = parseStatus(it) }   // Ongoing/Completed → enum
    j["description"]?.jsonPrimitive?.content?.let { description = it }
    j["thumbnail"]?.jsonPrimitive?.content?.let { thumbnail_url = it }
}
```

**Episode list:**

```kotlin
override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)   // same endpoint
override fun episodeListParse(response: Response): List<SEpisode> {
    val j = json.decodeFromString<JsonObject>(response.body.string())
    val type = j["type"]?.jsonPrimitive?.content
    val episodesCount = j["episodesCount"]?.jsonPrimitive?.int ?: 1
    return j["episodes"]?.jsonArray?.mapNotNull { item ->
        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val num = item.jsonObject["number"]?.jsonPrimitive?.content?.replace(".0", "") ?: "1"
        SEpisode.create().apply {
            url = id
            episode_number = item.jsonObject["number"]?.jsonPrimitive?.float ?: 0F
            name = when {
                type.isNullOrBlank()                            -> "Video $num"
                (type.contains("Hollywood") && episodesCount==1) || type.contains("Movie") -> "Movie"
                else                                            -> "Episode $num"
            }
        }
    } ?: emptyList()
}
```

**Videos — this is the interesting part for us.** KissKH gates the video URL behind two one-time keys fetched from a Google Apps Script proxy:

```kotlin
override suspend fun getVideoList(episode: SEpisode): List<Video> {
    // 1) Get the per-episode "kkey" from a Google Apps Script proxy
    val kkey = requestVideoKey(episode.url)

    // 2) Hit the real KissKH API with that key
    val url = "$baseUrl/api/DramaList/Episode/${episode.url}.png?err=false&ts=&time=&kkey=$kkey"
    val response = client.newCall(GET(url, headers)).awaitSuccess()

    return response.use { resp ->
        val id = resp.request.url.toString().substringAfter("Episode/").substringBefore(".png")
        videosFromElement(resp, id)
    }
}

// Old template methods are explicitly disabled:
override fun videoListRequest(episode: SEpisode) = throw UnsupportedOperationException()
override fun videoListParse(response: Response)   = throw UnsupportedOperationException()
```

The CONVENTION here is important: there are two API styles in `AnimeHttpSource`:

| Sync template style (`*Request` + `*Parse`) | Modern suspend style (`getVideoList`) |
|---|---|
| `popularAnimeRequest(page)` → `popularAnimeParse(response)` | `getPopularAnime(page)` |
| `searchAnimeRequest(page, query, filters)` → `searchAnimeParse(response)` | `getSearchAnime(page, query, filters)` |
| `animeDetailsRequest(anime)` → `animeDetailsParse(response)` | `getAnimeDetails(anime)` |
| `episodeListRequest(anime)` → `episodeListParse(response)` | `getEpisodeList(anime)` |
| `videoListRequest(episode)` → `videoListParse(response)` | `getVideoList(episode)` |

Modern extensions often use the sync style for everything except `getVideoList`, where they need coroutines/parallelism. When they do that, they throw `UnsupportedOperationException()` from the unused `videoListRequest`/`videoListParse` to satisfy the abstract base.

**Final video URL extraction (`videosFromElement`):**

```kotlin
private suspend fun videosFromElement(response: Response, id: String): List<Video> {
    val jObject = json.decodeFromString<JsonObject>(response.body.string())
    val videoUrl = jObject["Video"]?.jsonPrimitive?.content ?: return emptyList()  // ← direct mp4/m3u8

    val kkey    = requestSubKey(id)
    val subData = client.newCall(GET("$baseUrl/api/Sub/$id?kkey=$kkey")).awaitSuccess().bodyString()
    val subList = json.decodeFromString<JsonArray>(subData).parallelCatchingMapNotNull { item ->
        val suburl = item.jsonObject["src"]?.jsonPrimitive?.content ?: return@parallelCatchingMapNotNull null
        val lang   = item.jsonObject["label"]?.jsonPrimitive?.content ?: "Unknown"
        if (suburl.contains(".txt")) subDecryptor.getSubtitles(suburl, lang)   // encrypted SRT
        else                         Track(suburl, lang)
    }

    return UrlUtils.fixUrl(videoUrl)?.let { v ->
        Video(
            url            = v,
            quality        = "FirstParty",
            videoUrl       = v,
            subtitleTracks = subList,
            headers        = Headers.headersOf("referer", "$baseUrl/", "origin", baseUrl),
        ).let(::listOf)
    } ?: emptyList()
}

@Serializable
data class Key(val id: String, val version: String, val key: String)
```

> **Key takeaway:** the playable URL here is just a JSON field `"Video"` returned by the site's own API. The "scraping" consists of:
> 1. Discovering the right API endpoints (here: `/api/DramaList/List`, `/api/DramaList/Drama/{id}`, `/api/DramaList/Episode/{id}.png?kkey=…`, `/api/Sub/{id}?kkey=…`).
> 2. Calling them with the right headers (`Referer: $baseUrl/`).
> 3. Optionally solving a key/obfuscation step (here: a Google Apps Script proxy returns the per-request `kkey`).
> 4. Handing the final mp4/m3u8 URL to the host app's `Video(url, quality, videoUrl, headers, subtitleTracks, audioTracks)` constructor.

### 5d. `SubDecryptor.kt` — AES-CBC blob decryption

KissKH ships some subtitles as `.txt` blobs that are Base64 + AES-CBC encrypted with two hardcoded keys. The decryptor iterates both key/IV pairs:

```kotlin
class SubDecryptor(private val client: OkHttpClient, private val headers: Headers, private val baseurl: String) {
    suspend fun getSubtitles(subUrl: String, subLang: String): Track {
        val subHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/plain, */*")
            add("Origin", baseurl)
            add("Referer", "$baseurl/")
        }.build()

        val subtitleData = client.newCall(GET(subUrl, subHeaders)).awaitSuccess().bodyString()
        val chunks = subtitleData.split(CHUNK_REGEX).filter(String::isNotBlank).map(String::trim)

        val decrypted = chunks.mapIndexed { index, chunk ->
            val parts = chunk.split("\n")
            val text  = parts.slice(1 until parts.size)
            val d = text.joinToString("\n") { runCatching { decrypt(it) }.getOrDefault("") }
            listOf(index + 1, parts.first(), d).joinToString("\n")
        }.joinToString("\n\n")

        val file = File.createTempFile("subs", "srt").also(File::deleteOnExit)
        file.writeText(decrypted)
        return Track(Uri.fromFile(file).toString(), subLang)
    }

    companion object {
        private val CHUNK_REGEX by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }
        private const val KEY  = "AmSmZVcH93UQUezi"
        private const val KEY2 = "8056483646328763"
        private val IV  = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
        private val IV2 = intArrayOf(909653298, 909193779, 925905208, 892483379)
    }

    private fun decrypt(encryptedB64: String): String {
        val encryptedBytes = Base64.decode(encryptedB64)
        for ((keyBytes, ivBytes) in keyIvPairs) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
            } catch (_: Exception) {}
        }
        throw IOException("Decryption failed: All keys/IVs failed")
    }
}
```

---

## 6. The video-extraction patterns (the heart of the matter)

Once you leave the easy case (site's own JSON API returns the URL directly), you have to extract playable URLs from "video server" pages. The repo has three recurring patterns.

### Pattern A — direct `<iframe>` HTML page with a regex-able m3u8/mp4 URL

Used by the `lib-multisrc/anikototheme/AnikotoExtractor.kt` "extractFromPlayer" function (anikoto, anikototheme-derived sites like AniWave, AnimeKai, etc.). The extractor fetches the embed page HTML, then tries **five regex strategies in order**:

```kotlin
private val DATA_ID_REGEX       = Regex("""data-id="([^"]+)"""")
private val IFRAME_SRC_REGEX    = Regex("""<iframe[^>]+src="([^"]+)"""")
private val M3U8_REGEX          = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
private val SOURCE_TAG_REGEX    = Regex("""<source[^>]+src="([^"]+\.m3u8[^"]*)"""")
private val JS_VAR_M3U8_REGEX   = Regex(
    """(?:var|let|const)\s+\w+\s*=\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']""" +
    """|(?:file|source|url|src)\s*[:=]\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']""",
)

private suspend fun extractFromPlayer(embedUrl: String, server: VideoData, pageReferer: String = "${theme.baseUrl}/"): ExtractionResult {
    val pageBody = theme.client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().use { it.body.string() }

    // Strategy 1: page has a data-id attribute → call the site's AJAX endpoint
    val dataId = DATA_ID_REGEX.find(pageBody)?.groupValues?.get(1)
    if (dataId != null) return fetchSourcesFromApi(dataId, host, embedUrl, server)

    // Strategy 2: page embeds an iframe → recurse into the iframe's URL
    val iframeSrc = IFRAME_SRC_REGEX.find(pageBody)?.groupValues?.get(1)
    if (iframeSrc != null) {
        val resolvedSrc = resolveUrl(iframeSrc, embedUrl)
        return extractFromPlayer(resolvedSrc, server, pageReferer = embedUrl)
    }

    // Strategy 3: page literally contains an m3u8 URL
    val directM3u8 = M3U8_REGEX.find(pageBody)?.groupValues?.get(0)
    if (directM3u8 != null) return extractDirectM3u8(directM3u8, server, "https://$host/")

    // Strategy 4: <source src="…m3u8">
    val sourceSrc = SOURCE_TAG_REGEX.find(pageBody)?.groupValues?.get(1)
    if (sourceSrc != null) return extractDirectM3u8(resolveUrl(sourceSrc, embedUrl), server, "https://$host/")

    // Strategy 5: m3u8 hidden inside a JS variable
    val jsVarUrl = JS_VAR_M3U8_REGEX.find(pageBody)?.let { m ->
        m.groupValues.getOrNull(1)?.takeIf(String::isNotEmpty)
            ?: m.groupValues.getOrNull(2)?.takeIf(String::isNotEmpty)
    }
    if (jsVarUrl != null) { /* try API then direct */ }
    return ExtractionResult(emptyList(), false)
}
```

The same site's AJAX endpoint (`/ajax/server?get=$serverId`) returns JSON `{ "result": { "url": "…" } }` containing the player iframe URL. The extractor then recurses into that iframe URL.

### Pattern B — Streamtape: pull the URL out of an inline `<script>` tag

`lib/streamtapeextractor/StreamTapeExtractor.kt` is the cleanest example of "split a JS string concatenation to defeat naive scraping":

```kotlin
class StreamTapeExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String = "Streamtape", subtitleList: List<Track> = emptyList()): Video? {
        // Normalise URL → https://streamtape.com/e/<id>
        val baseUrl = "https://streamtape.com/e/"
        val newUrl = if (!url.startsWith(baseUrl)) {
            val id = url.split("/").getOrNull(4) ?: return null
            baseUrl + id
        } else url

        val document = client.newCall(GET(newUrl)).execute().asJsoup()
        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?.substringAfter("$targetLine.innerHTML = '")
            ?: return null

        // The page contains:
        //   document.getElementById('robotlink').innerHTML =
        //     '/get_videoabcdef' + ('xcd' + '3' + '7' + …) + …
        // We reconstruct the full URL by:
        val videoUrl = "https:" + script.substringBefore("'") +
            script.substringAfter("+ ('xcd").substringBefore("'")
        //                                  ↑ the obfuscated suffix pieces

        return Video(videoUrl, quality, videoUrl, subtitleTracks = subtitleList)
    }
}
```

The trick: Streamtape obfuscates the video path by splitting it into pieces like `'abcd' + ('xcd' + '37' + '8a' + …) + 'token=…'`. The extractor slices around the `+ ('xcd` to concatenate the prefix and suffix into a final URL.

### Pattern C — Doodstream: server-side MD5 + token + random suffix

`lib/doodextractor/DoodExtractor.kt`:

```kotlin
class DoodExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, prefix: String? = null, redirect: Boolean = true): Video? = runCatching {
        val response = client.newCall(GET(url)).execute()
        val newUrl   = if (redirect) response.request.url.toString() else url
        val doodHost = getBaseUrl(newUrl)
        val content  = response.body.string()
        if (!content.contains("'/pass_md5/")) return null

        // 1) Parse the page title for quality
        val extractedQuality = Regex("\\d{3,4}p")
            .find(content.substringAfter("<title>").substringBefore("</title>"))?.value

        // 2) Pull the /pass_md5/<id> URL out of the JS
        val md5   = doodHost + (Regex("/pass_md5/[^']*").find(content)?.value ?: return null)
        val token = md5.substringAfterLast("/")

        // 3) Generate a random 10-char suffix — Dood requires this
        val randomString = createHashTable(10)
        val expiry       = System.currentTimeMillis()

        // 4) GET /pass_md5/… to receive the first half of the URL (a hashed path)
        val videoUrlStart = client.newCall(
            GET(md5, Headers.headersOf("referer", newUrl))
        ).execute().body.string()

        // 5) Final URL = <hashedStart> + <randomString> + ?token=…&expiry=…
        val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"
        Video(videoUrl, "Doodstream $extractedQuality", videoUrl,
              headers = doodHeaders(doodHost))
    }.getOrNull()

    private fun doodHeaders(host: String) = Headers.Builder().apply {
        add("User-Agent", "Aniyomi")
        add("Referer", "https://$host/")
    }.build()
}
```

### Pattern D — Gogo-stream (VidStreaming): AES-CBC encrypted AJAX response

`lib/gogostreamextractor/GogoStreamExtractor.kt` — gogoanime's player uses a custom encryption layer on top of its AJAX endpoint. The encryption parameters are leaked in the page's CSS class names:

```kotlin
class GogoStreamExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(serverUrl: String): List<Video> = runCatching {
        val document = client.newCall(GET(serverUrl)).execute().asJsoup()

        // The page's HTML leaks the AES key/IV in CSS class names:
        //   <div class="wrapper container-<IV_BYTES>">,
        //   <body class="container-<KEY_BYTES>">,
        //   <div class="videocontent videocontent-<DECRYPT_KEY_BYTES>">
        val iv           = document.selectFirst("div.wrapper")!!.getBytesAfter("container-")
        val secretKey    = document.selectFirst("body[class]")!!.getBytesAfter("container-")
        val decryptionKey= document.selectFirst("div.videocontent")!!.getBytesAfter("videocontent-")

        // Decrypt the data-value attribute of the script tag
        val decryptedAjaxParams = cryptoHandler(
            document.selectFirst("script[data-value]")!!.attr("data-value"),
            iv, secretKey, encrypt = false,
        ).substringAfter("&")

        val httpUrl = serverUrl.toHttpUrl()
        val host    = "https://" + httpUrl.host
        val id      = httpUrl.queryParameter("id") ?: throw Exception("error getting id")
        val encryptedId = cryptoHandler(id, iv, secretKey)         // re-encrypt the id
        val token       = httpUrl.queryParameter("token")
        val qualityPrefix = if (token != null) "Gogostream - " else "Vidstreaming - "

        // Hit the encrypted AJAX endpoint
        val jsonResponse = client.newCall(
            GET("$host/encrypt-ajax.php?id=$encryptedId&$decryptedAjaxParams&alias=$id",
                Headers.headersOf("X-Requested-With", "XMLHttpRequest"))
        ).execute().body.string()

        // Decrypt the response body and parse the sources list
        val data = json.decodeFromString<EncryptedDataDto>(jsonResponse).data
        val sourceList = cryptoHandler(data, iv, decryptionKey, encrypt = false)
            .let { json.decodeFromString<DecryptedDataDto>(it) }.source

        when {
            sourceList.size == 1 && sourceList.first().type == "hls" -> {
                val playlistUrl = sourceList.first().file
                playlistUtils.extractFromHls(playlistUrl, serverUrl,
                                             videoNameGen = { qualityPrefix + it })
            }
            else -> sourceList.map { Video(it.file, qualityPrefix + it.label, it.file,
                                           Headers.headersOf("Referer", serverUrl)) }
        }
    }.getOrElse { emptyList() }

    private fun cryptoHandler(string: String, iv: ByteArray, secretKey: ByteArray, encrypt: Boolean): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        return if (!encrypt) {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(Base64.decode(string, Base64.DEFAULT)))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey, "AES"), IvParameterSpec(iv))
            Base64.encodeToString(cipher.doFinal(string.toByteArray()), Base64.NO_WRAP)
        }
    }
}
```

### Pattern E — Kwik (used by AnimePahe): JS packer + custom string-decoding cipher + POST token + redirect chain

`src/en/animepahe/extractor/KwikExtractor.kt` is the most sophisticated single-host extractor in the repo. It has two flows.

**HLS flow** — the kwik player page contains a Dean-Edwards-packed `<script>` tag that, once unpacked, contains `const source=\'<m3u8-url>\';`:

```kotlin
private val kwikParamsRegex by lazy { Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""") }
private val kwikDUrl   by lazy { Regex("action=\"([^\"]+)\"") }
private val kwikDToken by lazy { Regex("value=\"([^\"]+)\"") }

private suspend fun getHlsStream(kwikUrl: String, referer: String): HlsStream =
    client.newCall(GET(kwikUrl, headers.newBuilder().set("Referer", referer).build()))
        .awaitSuccess().use { response ->
            val finalUrl = response.request.url.toString()
            val eContent = response.useAsJsoup()
            // Find the packed eval(function(p,a,c,k,e,d) script and unpack it
            val script = eContent.selectFirst("script:containsData(eval\\(function)")?.data()
                ?.substringAfterLast("eval(function(")
                ?: throw KwikException.ExtractionException("JsUnpacker not found.")
            val unpacked = JsUnpacker.unpackAndCombine("eval(function($script")
                ?: throw KwikException.ExtractionException("JsUnpacker failed.")
            // Pull the m3u8 out of `const source='<url>';`
            HlsStream(
                url     = unpacked.substringAfter("const source=\\'").substringBefore("\\';"),
                referer = finalUrl,
            )
        }
```

The `JsUnpacker` is the in-repo `lib/unpacker` library (a Kotlin port of [Dean Edwards' `packer`](http://dean.edwards.name/packer/)).

**MP4 flow** — kwik's HTML embeds a custom obfuscated JS that decodes to an HTML `<form>` whose `action` and hidden `_token` are POSTed to get a 302 redirect to the final mp4:

```kotlin
suspend fun getStreamUrlFromKwik(paheUrl: String): String {
    // Step 1: paheUrl/i → 302 redirect to kwik.cx/e/<id>
    val kwikUrl = noRedirectClient.newCall(GET("$paheUrl/i", headers)).await().use { response ->
        val location = response.header("location")
            ?: throw KwikException.ExtractionException("No location header.")
        "https://" + location.substringAfterLast("https://")
    }

    // Step 2: fetch the kwik page HTML (handle Cloudflare if 403/419)
    var (fContentCookies, fContentString, fContentUrl) = fetchKwikHtml(kwikUrl)

    // Step 3: find the obfuscated JS params: ("FffULLSTRING",N,"KEY",v1,v2,N)
    val match = kwikParamsRegex.find(fContentString) ?: throw …
    val (fullString, key, v1, v2) = match.destructured

    // Step 4: decode the obfuscated string with this hand-rolled cipher:
    val decrypted = decrypt(fullString, key, v1.toInt(), v2.toInt())
    //   → "<form action=\"https://kwik.cx/f/abc123\" method=\"post\">" +
    //     "<input type=\"hidden\" name=\"_token\" value=\"XYZ\">"

    val uri = kwikDUrl.find(decrypted)?.groupValues?.get(1)   // form action URL
    val tok = kwikDToken.find(decrypted)?.groupValues?.get(1) // _token value

    // Step 5: POST the form, follow the 302 to get the final mp4 URL
    var kwikLocation: String? = null
    var code = 419
    while (code != 302) {
        noRedirectClient.newCall(
            POST(uri, kwikHeaders.newBuilder()
                .set("Referer", fContentUrl)
                .set("Cookie", fContentCookies)
                .build(),
                FormBody.Builder().add("_token", tok).build())
        ).await().use { response ->
            code = response.code
            kwikLocation = response.header("location")  // ← the final mp4 URL
        }
    }
    return kwikLocation!!
}

// The Kwik "decrypt" cipher — basically a base-N string decoder keyed by an alphabet
private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
    val keyIndexMap = key.withIndex().associate { it.value to it.index }
    val sb = StringBuilder()
    var i = 0
    val toFind = key[v2]
    while (i < fullString.length) {
        val nextIndex = fullString.indexOf(toFind, i)
        if (nextIndex == -1) break
        val decodedCharStr = buildString {
            for (j in i until nextIndex) append(keyIndexMap[fullString[j]] ?: -1)
        }
        i = nextIndex + 1
        try {
            val decodedChar = (decodedCharStr.toInt(v2) - v1).toChar()
            sb.append(decodedChar)
        } catch (_: NumberFormatException) { break }
    }
    return sb.toString()
}
```

### Pattern F — MP4Upload: also packed JS, but a simpler regex after unpacking

`lib/mp4uploadextractor/Mp4uploadExtractor.kt`:

```kotlin
class Mp4uploadExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers, prefix: String = "", suffix: String = ""): List<Video> {
        val newHeaders = headers.newBuilder().set("referer", REFERER).build()
        val doc = client.newCall(GET(url, newHeaders)).execute().asJsoup()

        val script = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: doc.selectFirst("script:containsData(player.src)")?.data()
            ?: return emptyList()

        // After unpacking, the source URL is right after `player.src({ src: "..." })`
        val videoUrl = script.substringAfter(".src(").substringBefore(")")
            .substringAfter("src:").substringAfter('"').substringBefore('"')
        val resolution = QUALITY_REGEX.find(script)?.groupValues?.let { "${it[1]}p" } ?: "Unknown resolution"
        val quality = "${prefix}Mp4Upload - $resolution$suffix"
        return listOf(Video(videoUrl, quality, videoUrl, newHeaders))
    }
    companion object {
        private val QUALITY_REGEX by lazy { """\WHEIGHT=(\d+)""".toRegex() }
        private const val REFERER = "https://mp4upload.com/"
    }
}
```

### Pattern G — StreamWish: deobfuscate `main.js` with Synchrony + JsUnpacker, then regex the m3u8

`lib/streamwishextractor/StreamWishExtractor.kt` (the most advanced extractor in the repo):

```kotlin
class StreamWishExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val dmcaServersRegex  = """dmca\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val mainServersRegex  = """main\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val rulesServersRegex = """rules\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)

    suspend fun videosFromUrl(url: String, videoNameGen: (String) -> String = { "StreamWish - $it" }): List<Video> {
        val embedUrl = getEmbedUrl(url).toHttpUrl()
        val id       = getEmbedId(url)
        val domainsToTry = if (id.startsWith("http")) listOf("") else DOMAINS

        for (domain in domainsToTry) {
            val fullUrl = UrlUtils.fixUrl(id, "https://$domain") ?: continue
            val response = client.newCall(GET(fullUrl, headers)).await()
            if (!response.isSuccessful) { response.close(); continue }
            val body = response.bodyString()
            if (body.isBlank()) continue
            var doc = Jsoup.parse(body)

            // 1. If the page references /main.js, fetch & deobfuscate it with Synchrony (QuickJS sandbox)
            val scriptElement = doc.selectFirst("body > script[src*=/main.js]")
            if (scriptElement != null) {
                val scriptUrl      = scriptElement.absUrl("src")
                val scriptContent  = client.newCall(GET(scriptUrl, headers)).awaitSuccess().bodyString()
                val deobfuscated   = runCatching { Deobfuscator.deobfuscateScript(scriptContent) }.getOrNull() ?: continue

                // 2. Extract server lists from the deobfuscated JS
                val dmcaServers  = extractServerList(dmcaServersRegex,  deobfuscated)
                val mainServers  = extractServerList(mainServersRegex,  deobfuscated)
                val rulesServers = extractServerList(rulesServersRegex, deobfuscated)

                // 3. Pick a destination server (DMCA vs main depending on host rules)
                val destination = if (embedUrl.host in rulesServers) mainServers.randomOrNull()
                                  else dmcaServers.randomOrNull() ?: continue

                // 4. Re-fetch the player page on the new server
                val redirectedUrl = embedUrl.newBuilder().host(destination).build().toString()
                doc = client.newCall(GET(getEmbedUrl(redirectedUrl), headers)).awaitSuccess().useAsJsoup()
            }

            // 5. Find the script tag containing 'm3u8', unpack it if it's eval(p,a,c,k,e,d)
            val scriptBody = doc.selectFirst("script:containsData(m3u8)")?.data()?.let { script ->
                if (script.contains("eval(function(p,a,c")) JsUnpacker.unpackAndCombine(script) else script
            }

            // 6. Regex the m3u8 URL out of the unpacked source
            val masterUrl = scriptBody?.let { M3U8_REGEX.find(it)?.value }
            if (masterUrl != null) {
                val subtitleList = extractSubtitles(scriptBody)
                return playlistUtils.extractFromHls(
                    playlistUrl  = masterUrl,
                    referer      = masterUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: "https://${url.toHttpUrl().host}/",
                    videoNameGen = videoNameGen,
                    subtitleList = playlistUtils.fixSubtitles(subtitleList),
                )
            }
        }
        return emptyList()
    }

    companion object {
        private val M3U8_REGEX by lazy { Regex("""https[^"]*m3u8[^"]*""") }
    }
}
```

The `Deobfuscator` is from `lib/synchrony`:

```kotlin
object Deobfuscator {
    fun deobfuscateScript(source: String): String? {
        val originalScript = javaClass.getResource("/assets/$SCRIPT_NAME")?.readText() ?: return null
        // Patch the `export{…}` line so QuickJS can run it as plain script
        val regex = """export\{(.*) as Deobfuscator,(.*) as Transformer\};""".toRegex()
        val synchronyScript = regex.find(originalScript)?.let { match ->
            val (deob, trans) = match.destructured
            originalScript.replace(match.value, "const Deobfuscator = $deob, Transformer = $trans;")
        } ?: return null

        val sourceLiteral = jsonInstance.encodeToString(String.serializer(), source)
        return QuickJs.create().use { engine ->
            engine.evaluate("globalThis.console = { log: ()=>{}, warn: ()=>{}, error: ()=>{}, trace: ()=>{} };")
            engine.evaluate(synchronyScript)
            engine.evaluate("new Deobfuscator().deobfuscateSource($sourceLiteral)") as? String
        }
    }
}
private const val SCRIPT_NAME = "synchrony-v2.4.5.1.js"
```

So StreamWish ships the entire Synchrony JS deobfuscator (the 2 % JS in the repo's language stats) bundled as an asset and runs it through [QuickJS](https://github.com/cashapp/quickjs-java) to clean up obfuscated player JS before regexing the m3u8 URL out.

### Pattern H — "Universal" fallback: spin up a WebView, intercept every network request, grab the first one that ends in `.mp4|.m3u8|.mpd`

`lib/universalextractor/UniversalExtractor.kt`:

```kotlin
class UniversalExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers,
                      customQuality: String? = null, prefix: String = ""): List<Video> {
        val host = origRequestUrl.toHttpUrl().host.substringBefore(".").proper()
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var resultUrl = ""
        val playlistUtils by lazy { PlaylistUtils(client, origRequestHeader) }
        val headers = origRequestHeader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
            val newView = WebView(context).also { webView = it }
            with(newView.settings) {
                javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
                userAgentString = origRequestHeader["User-Agent"]
            }
            newView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (VIDEO_REGEX.containsMatchIn(url)) {    // ← mp4|m3u8|mpd
                        resultUrl = url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            webView?.loadUrl(origRequestUrl, headers)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)   // 10 s
        handler.post { webView?.stopLoading(); webView?.destroy(); webView = null }

        return when {
            "m3u8" in resultUrl -> playlistUtils.extractFromHls(resultUrl, origRequestUrl, videoNameGen = { "$prefix - $host: $it" })
            "mpd"  in resultUrl -> playlistUtils.extractFromDash(resultUrl, { "$prefix - $host: $it" }, referer = origRequestUrl)
            "mp4"  in resultUrl -> listOf(Video(resultUrl, "$prefix - $host: ${customQuality ?: "Mirror"}", resultUrl,
                origRequestHeader.newBuilder().add("referer", origRequestUrl).build()))
            else -> emptyList()
        }
    }
    companion object {
        const val TIMEOUT_SEC: Long = 10
        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$") }
    }
}
```

This is the **single most reusable trick in the whole repo** for anyone building a scraping browser: open the page in a headless WebView, intercept every outbound request via `shouldInterceptRequest`, and snap up the first one matching a media-file regex.

### Pattern I — AllAnime: GraphQL + AES-GCM-encrypted response + XOR-obfuscated source URLs

`src/en/allanime/AllAnime.kt` (a long but very illustrative file) shows a hybrid:
1. The site has a GraphQL endpoint. Queries are sent as POST with `variables` + `query` JSON.
2. The episode-info response sometimes comes back as `{ "data": { "tobeparsed": "<base64>" } }` — a Base64 blob containing `versionByte + IV(12 bytes) + ciphertext`. The key is `SHA-256("Xot36i3lK3:v<versionByte>")` and the cipher is `AES/GCM/NoPadding`.
3. After decryption, you get `{ "episode": { "sourceUrls": [{ "sourceName":"…", "sourceUrl":"#<hex>", "type":"player"|"", "priority":1.0 }] } }`.
4. Each `sourceUrl` starts with a 1-2 char prefix (`#`, `##`, `#-`, `--`, `-#`) indicating which XOR key was used; the rest is hex bytes XORed with a per-prefix mask. Decode and you get the real URL.
5. Then the extension dispatches to one of seven host-specific extractors based on URL pattern matching.

```kotlin
private fun String.decryptSource(): String {
    val (hexPayload, keyType) = when {
        startsWith("--") -> substring(2) to 3
        startsWith("#-") -> substring(2) to 2
        startsWith("##") -> substring(2) to 1
        startsWith("-#") -> substring(2) to 4
        startsWith("#")  -> substring(1) to 0
        else             -> this to null
    }
    val parsedChunks = try { hexPayload.chunked(2).map { it.toInt(16) } }
                      catch (_: NumberFormatException) { return this }
    if (keyType == null) {
        // Try every XOR mask, return the first that yields a sensible URL
        XOR_MASKS.forEach { mask ->
            val decrypted = String(CharArray(parsedChunks.size) { i ->
                ((parsedChunks[i] xor mask) and 0xFF).toChar()
            })
            if (decrypted.contains("/clock") || decrypted.contains("http")) return decrypted
        }
        return this
    }
    val mask = XOR_MASKS[keyType]
    return String(CharArray(parsedChunks.size) { i -> ((parsedChunks[i] xor mask) and 0xFF).toChar() })
}

private fun decryptTobeparsed(base64Payload: String): String {
    val blob = Base64.decode(base64Payload, Base64.DEFAULT)
    if (blob.size < 13) return ""
    val versionByte   = blob[0].toInt() and 0xFF
    val iv            = blob.sliceArray(1 until 13)
    val encryptedData = blob.sliceArray(13 until blob.size)

    val keyBytes = MessageDigest.getInstance("SHA-256")
        .digest("${DECRYPT_SECRET}:v$versionByte".toByteArray(Charsets.UTF_8))

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
    return String(cipher.doFinal(encryptedData), Charsets.UTF_8)
}
```

### Pattern J — `PlaylistUtils.extractFromHls` — turning a master m3u8 URL into a list of `Video`s

Once any of the above patterns yields an m3u8 URL, almost every extractor hands it off to `lib/playlistutils/PlaylistUtils.kt`. This:

1. GETs the master playlist with proper Referer.
2. If it's actually a media playlist (no `#EXT-X-STREAM-INF`), returns a single `Video`.
3. Otherwise splits on `#EXT-X-STREAM-INF:` and parses each variant stream for `RESOLUTION=`, `BANDWIDTH=`, `CODECS=`.
4. Also extracts `#EXT-X-MEDIA:TYPE=SUBTITLES` (subs) and `#EXT-X-MEDIA:TYPE=AUDIO` (audio tracks) and attaches them as `Track` lists to every `Video`.
5. Skips audio-only variants (codecs all start with `mp4a`).
6. Standardises quality strings (e.g. `"416x234"` → `"240p (416x234)"`).

The regexes used (defined at the bottom of `PlaylistUtils.kt`):

```kotlin
private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+x\d+)""")
private val BANDWIDTH_REGEX  = Regex("""BANDWIDTH=(\d+)""")
private val CODECS_REGEX     = Regex("""CODECS="([^"]+)"""")
private val SUBTITLE_REGEX   = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^>]*NAME="([^"]+)"[^>]*URI="([^"]+)"""")
private val AUDIO_REGEX      = Regex("""#EXT-X-MEDIA:TYPE=AUDIO[^>]*NAME="([^"]+)"[^>]*URI="([^"]+)"""")
```

---

## 7. Headers, Referer, User-Agent & Cloudflare

### Default behaviour from the base class

`AnimeHttpSource.headersBuilder()` returns a `Headers.Builder` already pre-populated with the host app's default User-Agent and `Accept` headers. Every `GET()`/`POST()` should pass `headers`. Extensions usually only add a `Referer`:

```kotlin
override fun headersBuilder() = super.headersBuilder()
    .set("Referer", "$baseUrl/")     // trailing slash is mandatory — see CONTRIBUTING.md
```

Per-request headers are built from `headers.newBuilder()…build()` and passed to the request.

### Why Referer matters

Every video-host extractor sets a `Referer` matching the embed page's origin, otherwise the host returns 403:
- Dood: `Referer: https://<dood-host>/`
- Kwik: `Origin: https://kwik.cx`, `Referer: https://kwik.cx/`
- StreamWish: `Referer: <scheme>://<host>/`
- AllAnime internal player: `Referer: <iframeEndpoint>/`

The final `Video` constructor also accepts a `headers` argument, which the host player then uses when fetching the actual stream bytes — so the `Referer` follows the URL all the way to the byte-stream fetch.

### User-Agent handling — three layers

1. **Default**: the host app's UA from `super.headersBuilder()`. Don't override unless necessary (per CONTRIBUTING.md).
2. **Custom per-extension**: `lib/randomua` (`keiyoushi.lib.randomua.setRandomUserAgent`) fetches a real-world UA list from `https://keiyoushi.github.io/user-agents/user-agents.json` (cached 24 h), picks a random desktop/mobile UA, and sets it. Adds prefs UI via `addRandomUAPreference()`.
3. **Cloudflare bypass UA**: some extensions (e.g. `AnimePahe`) let the user paste a UA that matches the one their WebView solved the Cloudflare challenge with — see `cfBypassUserAgent` in `AnimePahe.kt`.

### Cloudflare handling — three layers

**Layer 1 — automatic, app-provided.** The default `client` (`network.client`) already includes a Cloudflare-solving interceptor in modern Aniyomi/Anikku builds. CONTRIBUTING.md says:
> The default `client` now handles Cloudflare challenges automatically. Do **not** use `network.cloudflareClient`, as it is deprecated.

So most extensions do nothing — they just call `client.newCall(…)` and the host app solves challenges transparently.

**Layer 2 — `lib/cloudflareinterceptor`.** A reusable OkHttp `Interceptor` for older host apps. Spins up an Android `WebView`, loads the blocked URL, runs a JS sniffer that auto-clicks the Cloudflare challenge form / Turnstile checkbox, polls `CookieManager.getCookie(url)` for `cf_clearance=`, then copies the WebView's cookies into OkHttp's `CookieJar` and retries the original request:

```kotlin
class CloudflareInterceptor(private val client: OkHttpClient) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalResponse = chain.proceed(chain.request())
        if (!(originalResponse.code in ERROR_CODES && originalResponse.header("Server") in SERVER_CHECK))
            return originalResponse
        originalResponse.close()
        return chain.proceed(resolveWithWebView(originalRequest, client))
    }

    private val CHECK_SCRIPT by lazy { """
        setInterval(() => {
            if (document.querySelector("#challenge-form") != null) {
                const simple = document.querySelector("#challenge-stage > div > input[type='button']")
                if (simple != null) simple.click()
                const turnstile = document.querySelector("div.hcaptcha-box > iframe")
                if (turnstile != null) {
                    const button = turnstile.contentWindow.document.querySelector("input[type='checkbox']")
                    if (button != null) button.click()
                }
            } else {
                CloudflareJSI.leave()    // ← JS interface counts down the latch
            }
        }, 2500)
    """.trimIndent() }
}
```

**Layer 3 — site-specific bypasses.** E.g. `AnimePahe` ships its own `DdosGuardInterceptor` (for the ddos-guard.net layer AnimePahe sits behind) and `CloudflareBypass` (a more robust WebView solver that polls every 500 ms for `cf_clearance=` in cookies, 30 s timeout, can be invoked from inside the Kwik extractor when kwik.cx returns 403/419):

```kotlin
class CloudflareBypass {
    @Synchronized
    fun getCookies(pageUrl: String, customUserAgent: String? = null): CloudFlareBypassResult? {
        clearCookiesForUrl(pageUrl)
        val latch = CountDownLatch(1)
        var result: CloudFlareBypassResult? = null
        var webView: WebView? = null

        Handler(Looper.getMainLooper()).post {
            webView = WebView(applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = customUserAgent ?: UA
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) =
                        pollForClearance(pageUrl, userAgentToUse, cancelled) { result = it; latch.countDown() }
                }
                loadUrl(pageUrl)
            }
        }
        latch.await(30, TimeUnit.SECONDS)
        // … cleanup …
        return result
    }
}
```

### Cookie injection — `lib/cookieinterceptor`

For sites that need a non-Cloudflare cookie set on every request:

```kotlin
class CookieInterceptor(
    private val domain: String,
    private val cookies: List<Pair<String, String>>,
) : Interceptor {
    init { cookies.forEach { setCookie("https://$domain/", "${it.first}=${it.second}; Domain=$domain; Path=/") } }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.endsWith(domain)) return chain.proceed(request)
        // merge existing Cookie header with our injected cookies
        // …
    }
}
```

CONTRIBUTING.md warns explicitly against manually setting the `Cookie` header — it overrides all cookies (including Cloudflare's) and breaks challenge-solving.

---

## 8. Packaging & loading model — end-to-end

### Build: each extension is an Android **Application** module → APK

`PluginExtensionLegacy.kt` applies `com.android.application` (NOT `com.android.library`). Each `src/<lang>/<site>` module produces a separate APK:

- `applicationId  = eu.kanade.tachiyomi.animeextension.<lang>.<site>` (via `applicationIdSuffix`)
- `versionCode    = extVersionCode` (or `theme.baseVersionCode + overrideVersionCode` for theme extensions)
- `versionName    = "14.$versionCode"`
- APK archive name: `aniyomi-<lang>.<site>-v14.<versionCode>.apk`
- Release build: R8/ProGuard minification ON, signed with `signingkey.jks` (CI provides keystore via env vars)
- `BuildConfig` constants like `KISSKH_API` are injected per build type

### Publish: CI builds APKs and writes `index.min.json` to the `repo` branch

`.github/workflows/build_push.yml` runs on every push to master. It:
1. Generates a build matrix of changed modules (chunk size 15).
2. Runs `./gradlew src:<lang>:<source>:assembleRelease` for each.
3. Pushes the APKs to a separate repo `yuzono/anime-repo`'s `repo` branch under `apk/`.
4. Runs `.github/scripts/create-repo.py` which:
   - For each APK, runs `aapt dump --include-meta-data badging` to extract package name, versionCode, versionName, NSFW flag, app label, icon.
   - Reads `output.json` produced by an "inspector" job that loaded each APK via `DexClassLoader` and instantiated the `AnimeSource` to query `name`, `lang`, `id`, `baseUrl`, `versionId`.
   - Emits `index.min.json` (compact) and `index.json` (pretty) at the repo root, plus an icon PNG per package.

Sample entry from `index.min.json`:

```json
{
  "name": "Aniyomi: KissKH",
  "pkg": "eu.kanade.tachiyomi.animeextension.en.kisskh",
  "apk": "aniyomi-en.kisskh-v14.5.apk",
  "lang": "en",
  "code": 5,
  "version": "14.5",
  "nsfw": 1,
  "sources": [
    { "name": "KissKH", "lang": "en", "id": 6543269871234567890,
      "baseUrl": "https://kisskh.ovh", "versionId": 1 }
  ]
}
```

### Discover & install: host app fetches the index, downloads APKs

The host app (Aniyomi/Anikku) ships with a "repos" list. Default repo URL: `https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json`. The user can add custom repos via the `aniyomi://add-repo?url=…` deep link.

For each entry in `index.min.json`, the app:
1. Shows it in the extension catalog UI.
2. When the user taps "Install", downloads `https://raw.githubusercontent.com/yuzono/anime-repo/repo/apk/<apk-file>` and installs it via `PackageInstaller` (the APK declares no activities, no permissions beyond the host app's, so install is silent with the user's prior one-time consent).
3. On update checks, compares installed `versionCode` with the repo's `code` and offers updates.

### Runtime loading: PackageManager + reflection

When the host app starts (and whenever an extension is installed/updated), it does roughly:

```kotlin
val extFeature = "tachiyomi.animeextension"
// Scan all installed packages for the <uses-feature> tag matching extFeature
for (info in packageManager.getInstalledApplications(PackageManager.GET_META_DATA)) {
    val appInfo = packageManager.getApplicationInfo(info.packageName, PackageManager.GET_META_DATA)
    val className = appInfo.metaData.getString("tachiyomi.animeextension.class") ?: continue
    val isNsfw    = appInfo.metaData.getInt("tachiyomi.animeextension.nsfw", 0) == 1
    val fullClassName = "eu.kanade.tachiyomi.animeextension" + className  // ".KissKH" → full
    // Load the APK's dex via DexClassLoader/PathClassLoader
    val loader = PathClassLoader(appInfo.sourceDir, appInfo.nativeLibraryDir, classLoader)
    val clazz  = loader.loadClass(fullClassName)
    val instance = clazz.getDeclaredConstructor().newInstance() as AnimeSource
    // register instance under its generated id
}
```

So extensions are **pure-dex plugins** loaded reflectively at runtime. They share the host app's process and class loader chain (so they can call `network.client`, `headers`, etc. from the app), but each lives in its own APK with its own versioning.

---

## 9. Patterns for adding a NEW site quickly

The CONTRIBUTING.md "Quickest way to get started" recipe:

1. **Copy an existing extension's folder** (`cp -r src/en/kisskh src/en/mynewsite`) and rename.
2. Edit `build.gradle`:
   ```groovy
   ext {
       extName        = 'MyNewSite'
       extClass       = '.MyNewSite'
       extVersionCode = 1
       isNsfw         = false
   }
   apply plugin: "kei.plugins.extension.legacy"
   ```
3. Move the `.kt` file to `src/eu/kanade/tachiyomi/animeextension/en/mynewsite/MyNewSite.kt`, change the `package` line and class name.
4. Override the four mandatory fields and the popular/search/details/episodes/videos methods:
   ```kotlin
   class MyNewSite : AnimeHttpSource() {
       override val name = "MyNewSite"
       override val baseUrl = "https://mynewsite.example"
       override val lang = "en"
       override val supportsLatest = true

       override fun popularAnimeRequest(page: Int) = GET("$baseUrl/popular?page=$page", headers)
       override fun popularAnimeParse(response: Response): AnimesPage { /* Jsoup parse */ }
       override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = …
       override fun searchAnimeParse(response: Response): AnimesPage = …
       override fun animeDetailsParse(response: Response): SAnime = …
       override fun episodeListParse(response: Response): List<SEpisode> = …
       override fun videoListParse(response: Response): List<Video> = …
   }
   ```
5. If the site embeds video iframes to known hosts (Doodstream, Streamtape, MP4Upload, etc.), add the relevant lib deps and call the extractors:
   ```groovy
   dependencies {
       implementation(project(':lib:doodextractor'))
       implementation(project(':lib:streamtapeextractor'))
       implementation(project(':lib:playlistutils'))   // for HLS handling
   }
   ```
   Then in `videoListParse`:
   ```kotlin
   val iframeUrl = document.selectFirst("iframe").absUrl("src")
   return when {
       iframeUrl.contains("dood")     -> DoodExtractor(client).videosFromUrl(iframeUrl)
       iframeUrl.contains("streamtape") -> StreamTapeExtractor(client).videosFromUrl(iframeUrl)
       else -> UniversalExtractor(client).videosFromUrl(iframeUrl, headers)
   }
   ```
6. Drop icon PNGs into `res/mipmap-*/ic_launcher.png` (5 densities).
7. Run `./gradlew src:en:mynewsite:assembleDebug` to test-build.

For sites that share a CMS with existing sites, prefer **creating a theme in `lib-multisrc/`** and the new extension becomes a 5-line class file (see `AniWave.kt` above).

---

## 10. Key takeaways for building your own scraping browser

1. **The architecture is a plugin system, not a scraper.** The "extension" model is: a tiny APK containing one Kotlin class, loaded reflectively by a host app that already has networking (OkHttp), HTML parsing (Jsoup), JSON (kotlinx.serialization), a media player (ExoPlayer), and a Cloudflare solver (WebView). The extension is just glue that knows the URLs and selectors of one site. If you're building a scraping browser, the analogue is: define a `Site` interface with ~8 methods (`popularAnimeRequest/Parse`, `searchAnimeRequest/Parse`, `animeDetailsParse`, `episodeListParse`, `videoListParse`/`getVideoList`) and ship one implementation per site as a self-contained module.

2. **Two distinct scraping sub-problems:**
   - **Catalog scraping** (popular / search / details / episodes) — usually easy. Mostly Jsoup CSS selectors against HTML, or `parseAs<MyDto>()` against a JSON API. Pagination via `AnimesPage(animeList, hasNextPage)`.
   - **Video URL extraction** — usually hard. The repo's library directory is essentially a 70-file catalogue of every trick sites use to hide playable URLs:
     | Trick | Real example in repo |
     |---|---|
     | Direct JSON field | KissKH (`response.json["Video"]`) |
     | Inline `<script>` string concatenation | StreamTape (`getElementById('robotlink').innerHTML = … + ('xcd' + …)`) |
     | Server-issued MD5 path + token + random suffix | Doodstream |
     | AES-CBC encrypted AJAX response (key in CSS classes) | GogoStream / VidStreaming |
     | Dean Edwards packed JS → unpack → regex m3u8 | MP4Upload, Kwik HLS |
     | Packed JS → custom base-N cipher → POST form → 302 redirect | Kwik MP4 |
     | Synchrony-deobfuscated main.js + DMCA server rotation | StreamWish |
     | GraphQL + AES-GCM encrypted response + XOR-obfuscated URLs | AllAnime |
     | Last-resort: spin up a WebView, intercept requests matching `.(mp4\|m3u8\|mpd)$` | UniversalExtractor |

3. **The single most reusable trick: WebView request interception.** `lib/universalextractor` shows the universal fallback. For a homegrown scraping browser, this is essentially mandatory — many modern sites can only be cracked by letting their player JS run in a real browser engine and watching what URLs it fetches. ExoPlayer's `WebViewClient.shouldInterceptRequest` + a `Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$")` gets you 80 % of the way there.

4. **Headers are non-negotiable.** `Referer` matching the embed origin is the difference between 200 OK and 403 on every video CDN. Always send `Referer: <scheme>://<host>/` with a trailing slash. Pass headers all the way through to the byte-stream fetch (ExoPlayer's `DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers.toMultimap())`).

5. **Cloudflare: don't fight it, WebView it.** The repo has three Cloudflare solvers (the app's built-in one, `lib/cloudflareinterceptor`, and `animepahe/CloudflareBypass`). They all do the same thing: detect 403/503 with `Server: cloudflare*`, spin up a WebView, load the URL, poll `CookieManager.getCookie(url)` for `cf_clearance=`, copy that cookie into OkHttp's jar, retry. The auto-click Turnstile/checkbox script is identical across implementations.

6. **`m3u8` master playlist parsing is a solved problem.** Steal `lib/playlistutils/PlaylistUtils.kt` almost verbatim. Its job: GET master playlist, split on `#EXT-X-STREAM-INF:`, parse `RESOLUTION`/`BANDWIDTH`/`CODECS`, extract `#EXT-X-MEDIA:TYPE=SUBTITLES` and `TYPE=AUDIO` tracks, return `List<Video>` with all variants + subtitle/audio tracks attached.

7. **The "extension" base class is small enough to re-implement.** A from-scratch `AnimeHttpSource` equivalent needs: `name`, `baseUrl`, `lang`, an `OkHttpClient`, a default `Headers`, helpers `GET/POST`, and ~6 abstract methods (`popularAnimeRequest/Parse`, `searchAnimeRequest/Parse`, `animeDetailsParse`, `episodeListParse`, `videoListParse`). That's maybe 200 lines of Kotlin/TS/Python. The hard part is the extractor ecosystem — the 70 libs — which represent years of community reverse-engineering per video host.

8. **Configuration via `SharedPreferences`** (`ConfigurableAnimeSource`) lets each extension expose mirror selection, server preferences, quality preferences, custom UA, etc. through `setupPreferenceScreen(screen: PreferenceScreen)`. For a homegrown browser, the analogue is per-site settings pages (mirror list, preferred server, preferred quality, custom UA, enable/disable individual host extractors).

9. **Theme inheritance (`lib-multisrc/`) is huge for code reuse.** Sites that share a backend (e.g. all the "Anikoto theme" sites, all the "Dooplay" CMS sites, all the "Zoro theme" sites) get a 5-line class instead of 500 lines. The theme provides the request/parse logic; the leaf extension provides `name`, `lang`, `baseUrl`, list of hosters. If you're targeting many similar sites, design your base class with `open` methods from day one.

10. **The packaging model (standalone APK + PackageManager discovery + DexClassLoader loading) is interesting but overkill for a new project.** A simpler model is a flat folder of `.kt`/`.js`/`.py` files implementing a `Site` interface, loaded at startup via a language-native plugin registry. You only need the APK model if you want users to install/update individual scrapers without updating the app.

11. **Hardcoded keys are part of the territory.** KissKH has its AES subtitle keys baked in. AllAnime has `DECRYPT_SECRET = "Xot36i3lK3"`. GogoStream extracts its AES key from CSS class names. A scraping browser needs to make it easy to ship and update these secrets (env vars, asset files, remote config) without app releases.

12. **Code style conventions to steal from `CONTRIBUTING.md`:**
    - Always pass `headers` to `GET()`/`POST()`.
    - `Referer` with trailing slash.
    - Use `response.asJsoup()` / `response.useAsJsoup()` not `Jsoup.parse(response.body.string())`.
    - Cache `Regex` instances at class level (in `companion object`).
    - Use `setUrlWithoutDomain(relPath)` so anime/episode URLs are domain-agnostic.
    - Don't manually check for Cloudflare ("Just a moment..." text) in parse methods — let the interceptor do it.
    - Throw `UnsupportedOperationException()` from unused inherited methods instead of returning empty values.
    - Group methods in order: Popular → Latest → Search → Details → Episodes → Videos → Filters → Utilities.

13. **Concrete files to study next, in order:**
    - `src/en/kisskh/KissKH.kt` (cleanest, ~250 lines, JSON-only) — start here.
    - `src/en/animepahe/AnimePahe.kt` + `extractor/KwikExtractor.kt` + `DdosGuardInterceptor.kt` (HTML + packed JS + Cloudflare bypass + ddos-guard, all in one extension).
    - `lib/streamtapeextractor/StreamTapeExtractor.kt` (10-line string-split extraction).
    - `lib/doodextractor/DoodExtractor.kt` (multi-step token dance).
    - `lib/gogostreamextractor/GogoStreamExtractor.kt` (AES-CBC encrypted AJAX).
    - `lib/streamwishextractor/StreamWishExtractor.kt` (Synchrony + JsUnpacker + DMCA rotation — the most advanced extractor in the repo).
    - `lib/universalextractor/UniversalExtractor.kt` (the WebView fallback you'll want to copy).
    - `lib/playlistutils/PlaylistUtils.kt` (m3u8 master playlist parsing).
    - `lib/cloudflareinterceptor/CloudflareInterceptor.kt` (WebView Cloudflare solver).
    - `lib-multisrc/anikototheme/AnikotoTheme.kt` + `AnikotoExtractor.kt` (theme pattern + the 5-strategy iframe extraction pipeline).
    - `CONTRIBUTING.md` (1321 lines, very thorough — read in full once).

---

## Appendix A — `AnimeHttpSource` call flow (from CONTRIBUTING.md)

```
                  ┌─────────────────────────────────────────────┐
                  │  User taps source in "Browse" tab           │
                  └────────────────────┬────────────────────────┘
                                       ▼
        getPopularAnime(page=1)  ───►  popularAnimeRequest(1) ──► HTTP GET
                                       ▼
                                       response ──► popularAnimeParse(response) → AnimesPage
                                       │                            │
                                       │ hasNextPage && !empty?     │
                                       ▼                            ▼
                                  (scroll → page=2)            UI renders SAnime list
                                                                  │
                                       ┌──────────────────────────┘
                                       ▼  (user taps an anime)
        getAnimeDetails(anime) ──► animeDetailsRequest(anime) ──► GET
                                  └► animeDetailsParse(response) → SAnime (title, desc, genre, status, thumb)
        getEpisodeList(anime)   ──► episodeListRequest(anime)  ──► GET
                                  └► episodeListParse(response) → List<SEpisode>  (sorted descending!)
                                       │
                                       ▼  (user taps an episode)
        getVideoList(episode)   ──► videoListRequest(episode)  ──► GET
                                  └► videoListParse(response) → List<Video>
                                       │
                                       ▼  (user picks a Video)
                              ExoPlayer plays video.url with video.headers
                              (and downloads video.subtitleTracks / audioTracks)
```

## Appendix B — Directories of `src/en/` (≈55 English extensions)

`allanime`, `anikage`, `anikoto`, `anilist`, `animegg`, `animekhor`, `animenosub`, `animepahe`, `animeparadise`, `animesogo`, `animetake`, `animeverse`, `aniwave`, `asiaflix`, `av1encodes`, `blzone`, `cineby`, `donghuastream`, `hahomoe`, `hanime`, `hentaimama`, `hexawatch`, `hstream`, `jpfilms`, `kayoanime`, `kickassanime`, `kimoitv`, `kissanime`, `kisskh`, `kotokai`, `luciferdonghua`, `mapple`, `miruro`, `moviesmod`, `myanime`, `myrunningman`, `noobsubs`, `onetwothreeanime`, `oppaistream`, `pinoymoviepedia`, `rule34video`, `superstream`, `tokuzilla`, `uhdmovies`, `uniquestream`, `wcoanimedub`, `wcoanimesub`, `wcoforever`, `wcofun`, `wcostream`, `wcotv`.

## Appendix C — `lib/` modules (≈70)

Extractors (one per video host): `amazonextractor`, `bloggerextractor`, `burstcloudextractor`, `buzzheavierextractor`, `cdaextractor`, `chillxextractor`, `dailymotionextractor`, `doodextractor`, `dopeflixextractor`, `fastreamextractor`, `filemoonextractor`, `fireplayerextractor`, `fusevideoextractor`, `gdriveplayerextractor`, `gogostreamextractor`, `goodstreamextractor`, `googledriveepisodes`, `googledriveextractor`, `googledriveplayerextractor`, `luluextractor`, `lycorisextractor`, `megacloudextractor`, `megamaxmultiserver`, `megaupextractor`, `mixdropextractor`, `mp4uploadextractor`, `okruextractor`, `pixeldrainextractor`, `rapidcloudextractor`, `rapidshareextractor`, `rumbleextractor`, `savefileextractor`, `sendvidextractor`, `sibnetextractor`, `streamdavextractor`, `streamhubextractor`, `streamlareextractor`, `streamplayextractor`, `streamsilkextractor`, `streamtapeextractor`, `streamupextractor`, `streamwishextractor`, `upstreamextractor`, `uqloadextractor`, `vidbomextractor`, `vidguardextractor`, `vidhideextractor`, `vidlandextractor`, `vidmolyextractor`, `vidoextractor`, `vidsrcextractor`, `vkextractor`, `voeextractor`, `vudeoextractor`, `youruploadextractor`, `universalextractor`.

Utilities: `bangumiscraper`, `cloudflareinterceptor`, `cookieinterceptor`, `cryptoaes`, `dataimage`, `i18n`, `javcoverfetcher`, `lzstring`, `m3u8server`, `playlistutils`, `randomua`, `seedrandom`, `synchrony`, `textinterceptor`, `unpacker`.

## Appendix D — `lib-multisrc/` themes (≈11)

`anikototheme`, `anilist`, `animekaitheme`, `animestream`, `datalifeengine`, `dooplay`, `dopeflix`, `pelisplus`, `wcotheme`, `yflixtheme`, `zorotheme`.

---

*End of report. Compiled from a sparse checkout of `yuzono/anime-extensions` (master, commit 5661123, 2026-07-10), the in-repo `CONTRIBUTING.md` (1321 lines), and the source of 4 representative extensions (`kisskh`, `allanime`, `animepahe`, `aniwave`) plus 9 lib modules (`streamtapeextractor`, `doodextractor`, `gogostreamextractor`, `mp4uploadextractor`, `streamwishextractor`, `universalextractor`, `playlistutils`, `cloudflareinterceptor`, `cookieinterceptor`, `randomua`, `synchrony`, `unpacker`, `m3u8server`) and 1 theme (`anikototheme`).*
