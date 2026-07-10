# Translation & i18n Layer for Reverb — Task 1-c

**Researcher:** general-purpose (translation/i18n)
**Scope:** (1) app-UI i18n strategy for the Reverb Android browser-overlay app, (2) on-device translation of scraped site content (Japanese/Chinese/Korean/Spanish/etc. anime titles, synopses, episode titles), (3) concrete Gradle coordinates, Kotlin interfaces, Room schema, Compose UI sketch.

Cross-references: task 2-a (aniyomi stack — uses Moko Resources), task 2-b (anime-extensions `src/<lang>/` has 17 language folders: `all, ar, de, en, es, fr, hi, id, it, ko, pl, pt, ru, sr, tr, uk, zh` — this is the v1 language set to match), task 4-a/4-b (scraped sites' content fields — AnikotoTheme exposes `data-jp` Japanese alt-title; reanime embeds `title_english/title_romaji/title_native`; miruro proxies AniList which has `title.english/romaji/native`; mkissa/allanime returns the same AniList triple), task 1-b (on-device LLM — Gemma 2 2B, mentioned by user; this report adds TranslateGemma 2B/2.6B as the translation-specific sibling).

---

## Part 1 — App UI i18n

### 1.1 The standard Android approach (and why it's the right baseline)

Android's resource system already does i18n natively. You ship one default `res/values/strings.xml` (the source-of-truth English) plus one `res/values-<lang>/strings.xml` per target language (optionally with region suffix: `values-zh-rCN/`, `values-pt-rBR/`). At runtime, Android picks the best-matching folder based on the device/app locale. In Compose you read a string with one function:

```kotlin
val title: String = stringResource(R.string.settings_auto_translate)
```

Plurals (`<plurals>` element in `strings.xml`) are read with `pluralStringResource(id, quantity, …formatArgs)`:

```kotlin
val s = pluralStringResource(R.plurals.downloads_remaining, count, count)
```

This system is:
- **zero-dependency** (ships with the platform + AndroidX core),
- **tooling-perfect** (Android Studio's translations editor, lint missing-translation checks, `app:lint` warns on untranslated keys),
- **Weblate-ready** (Weblate's "Android strings" file format parses `strings.xml` natively — same workflow Aniyomi uses via hosted.weblate.org),
- **free** of any KMP plumbing.

### 1.2 Compose-only apps: just `stringResource()` — no `CompositionLocal` ceremony

For a Compose-only app, the answer is unambiguous: **just call `stringResource(R.string.x)`**. Do NOT wrap the context in a `CompositionLocalProvider(LocalContext)` indirection. Reasons:

1. `stringResource()` is already a `@Composable` that reads `LocalContext.current.resources` under the hood and recomposes when the `Configuration` changes (which is exactly what happens when you call `AppCompatDelegate.setApplicationLocales(...)` — Android rebuilds the Configuration and every `stringResource` call re-resolves).
2. A `CompositionLocal<StringProvider>` abstraction (the "StringRepository" pattern) only earns its keep when (a) you need to swap implementations for tests, or (b) you're sharing strings across KMP targets. Reverb is single-platform (Android) and the strings never change at runtime except via the standard locale-change path, so `stringResource()` is sufficient.
3. Aniyomi — the closest reference app — uses Moko Resources' `stringResource(MR.strings.x)` instead of `stringResource(R.strings.x)`, but only because Aniyomi is being incrementally ported to KMP and shares string tables with iOS/desktop targets in the future. Reverb has no such plan; the KMP tax is pure overhead.

**Verdict:** plain `res/values/strings.xml` + `stringResource(R.string.x)`. No `CompositionLocal`. No Moko.

### 1.3 Moko Resources — coordinates, version, verdict

Moko Resources (by IceRock) is a KMP library + Gradle plugin that replaces Android's resource system with a generated `MR` object accessible from common code. Coordinates:

```kotlin
// Root build.gradle.kts
plugins {
    id("dev.icerock.mobile.multiplatform-resources") version "0.24.5"  // latest stable; 0.25.2 also released
}

// Module build.gradle.kts
dependencies {
    implementation("dev.icerock.moko:resources:0.24.5")
    implementation("dev.icerock.moko:resources-compose:0.24.5")  // provides MR.stringResource(MR.strings.x) for Compose
}
```

(Aniyomi pins `moko-resources` in its `gradle/libs.versions.toml`; we mirror its 0.24.x line. The plugin portal also lists a 0.26.x line — pin to 0.24.x for parity with Aniyomi until you need 0.25's breaking API changes.)

Usage in Compose:

```kotlin
import dev.icerock.moko.resources.compose.stringResource
val title = stringResource(MR.strings.settings_auto_translate)
```

**Should Reverb use it?** — **No, for v1.** Justification:

| Factor | Plain `strings.xml` | Moko Resources |
|---|---|---|
| KMP share with iOS/desktop | ❌ not possible | ✅ main reason to use it |
| Compose-only Android | ✅ idiomatic | ⚠️ adds plugin + codegen layer |
| Lint missing-translation warnings | ✅ built-in | ❌ lint doesn't see `MR.strings.*` refs |
| Weblate integration | ✅ "Android strings" format | ✅ same format (just generated) |
| Plurals | ✅ `<plurals>` + `pluralStringResource` | ✅ `MR.plurals.x` |
| RTL | ✅ handled by framework | ✅ same |
| Per-app language API | ✅ `AppCompatDelegate.setApplicationLocales` works | ⚠️ requires extra `LocaleManager` hookup; Moko has its own `LanguageType`/`createFileLocator` plumbing |
| Build time | ✅ minimal | ⚠️ +one Gradle plugin, +codegen task |
| Code navigation (Cmd-Click on R.string.x → strings.xml) | ✅ built into AS | ❌ loses this; `MR.strings.x` is generated |

Aniyomi uses Moko *because it's KMP-destined*. Reverb is single-platform for the foreseeable future, so Moko buys nothing but plugin maintenance. **Defer Moko to "v2.0 if we ever go KMP"** — a one-week migration once needed.

### 1.4 Plurals — what we actually need

Reverb has at least these count-bearing UI strings that must agree grammatically with the count:

```xml
<!-- res/values/strings.xml -->
<plurals name="downloads_count">
    <item quantity="one">%d download</item>
    <item quantity="other">%d downloads</item>
</plurals>

<plurals name="episodes_count">
    <item quantity="one">%d episode</item>
    <item quantity="other">%d episodes</item>
</plurals>

<plurals name="sites_supported">
    <item quantity="one">%d site</item>
    <item quantity="other">%d sites</item>
</plurals>

<plurals name="language_packs_downloaded">
    <item quantity="one">%d translation model (~30 MB)</item>
    <item quantity="other">%d translation models (~%2$d MB)</item>
</plurals>
```

Compose call:

```kotlin
Text(pluralStringResource(R.plurals.episodes_count, anime.episodeCount, anime.episodeCount))
```

Caveats:
- Slavic languages (Russian, Serbian, Ukrainian, Polish) have **three** plural forms (`one`, `few`, `many`) — Android's CLDR-driven `<plurals>` handles this correctly if the translator fills all three. Don't try to write your own count logic.
- Arabic has **six** plural forms (`zero`, `one`, `two`, `few`, `many`, `other`) — same story.
- Never `if (count == 1) "episode" else "episodes"` — it's wrong for ~⅓ of the languages we ship.

### 1.5 RTL support (Arabic / Hebrew)

Compose flips the entire UI automatically when `LocalLayoutDirection.current == LayoutDirection.Rtl`. This happens automatically when the app locale is `ar` or `he` — no manual wiring. Specifically:
- `Row` lays out children right-to-left.
- `Text` is right-aligned by default and bidi-aware (mixed LTR/RTL strings render correctly thanks to ICU).
- Icons that have a directional meaning (back arrow, forward arrow) must be mirrored manually: `Icons.Default.ArrowBack` → use `Icons.Default.ArrowForward` semantics, or wrap with `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)` to keep player transport controls LTR (Arabic users expect ⏪⏯⏩ to remain left-to-right even in RTL UI).

Gotchas:
1. **`Modifier.padding(start = 16.dp, end = 8.dp)`** is RTL-aware (start = right in RTL). Never use `padding(left = ..., right = ...)` — that breaks RTL.
2. **Custom `Canvas` drawing** is NOT auto-mirrored — if you draw a custom progress bar with `drawLine` from `(0, y)` to `(width, y)`, you must mirror manually for RTL.
3. **InlineContent / `AnnotatedString` with `InlineTextContent`** has a known bug (issuetracker.google.com/issues/230250288) where RTL inline content renders only when `LocalLayoutDirection == Ltr`. Avoid inline composables in RTL strings; use placeholders + post-processing instead.
4. **Player transport / seek bar**: keep LTR even in Arabic — users expect the timeline to read left-to-right. Wrap the player in `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)`.
5. **Numbers in RTL text**: digits 0-9 are always rendered LTR by the bidi algorithm; `Text("حلقة 5")` correctly shows "حلقة" right-to-left then "5" left-to-right. No work needed.
6. **Manifest**: add `android:supportsRtl="true"` to `<application>` (default true on API 17+; verify in case an inherited manifest disables it).

### 1.6 Per-app language API — runtime switching without restart

**The right API (since AndroidX AppCompat 1.6.0, Nov 2022):**

```kotlin
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

// Set the app to Japanese only (overrides system)
AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ja"))

// Set to "follow system" (clear override)
AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

// Read current
val current: LocaleListCompat = AppCompatDelegate.getApplicationLocales()
```

AppCompat 1.6+ backports Android 13's `LocaleManager` API all the way down to API 14. On API 33+ it calls `LocaleManager.setApplicationLocales()` directly (and the chosen language shows up in system Settings → Per-app language). On API 14–32 it does a `Configuration` override + `Activity.recreate()` under the hood. **There is no need to manually call `recreate()`** — AppCompat handles it.

**Does it work in a Compose-only app?** — **Yes, with one caveat.** You must use `androidx.appcompat:appcompat:1.6.1` (or 1.7.x) as a dependency, and your root activity must extend `AppCompatActivity` (NOT `ComponentActivity`). For Compose-only apps this is fine: `AppCompatActivity` extends `FragmentActivity` extends `ComponentActivity`, so the Compose `setContent { … }` call works identically. The cost: you pull in AppCompat (already a transitive dep of Material Components). The benefit: free per-app language support with no manual `Configuration`/`recreate()` plumbing.

**Manifest setup** (required for the API to know which locales your app supports):

```xml
<!-- AndroidManifest.xml -->
<application
    android:localeConfig="@xml/locales_config"
    ...>

<!-- res/xml/locales_config.xml -->
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en"/>    <!-- default; also declared via <locale android:name="en-US"/> if region-specific -->
    <locale android:name="ja"/>
    <locale android:name="es"/>
    <locale android:name="pt"/>
    <locale android:name="fr"/>
    <locale android:name="de"/>
    <locale android:name="ru"/>
    <locale android:name="zh-CN"/>
    <locale android:name="ko"/>
    <locale android:name="id"/>
    <locale android:name="ar"/>
    <!-- ... and any others we ship -->
</locale-config>
```

**Behavior summary:**

| Action | API 14–32 (AppCompat backport) | API 33+ (native LocaleManager) |
|---|---|---|
| User picks "日本語" in Settings | AppCompat rebuilds Configuration + calls `Activity.recreate()` on every activity | System reconfigures + persists to `LocaleManager` |
| User opens system Settings → Reverb → Language | n/a (system doesn't know) | Sees `locales_config.xml`, shows picker |
| Compose recomposes after switch | ✅ (every `stringResource` re-resolves) | ✅ |
| Persists across app restarts | ✅ (AppCompat persists to `SharedPreferences`) | ✅ (system persists) |

**Wrinkle:** if you want system Settings to expose the language picker on API 33+, the `locales_config.xml` must list every language you ship — even the default English. If you skip English, the system won't show "English" as a re-selectable option.

**Compose code wiring:**

```kotlin
class SettingsLanguageScreen : Screen {
    @Composable
    override fun Content() {
        val current = AppCompatDelegate.getApplicationLocales()
        val supported = listOf("en","ja","es","pt","fr","de","ru","zh-CN","ko","id","ar")
        Column {
            supported.forEach { tag ->
                val isSelected = current.toLanguageTags().split(',').any { it.startsWith(tag.substringBefore('-')) }
                RadioButton(selected = isSelected, onClick = {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                })
                Text(stringResource("language_name_$tag".asResId())) // or just display tag
            }
            // "Follow system" option
            RadioButton(selected = current.isEmpty, onClick = {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            })
            Text(stringResource(R.string.language_system_default))
        }
    }
}
```

After `setApplicationLocales()`, the activity recreates and all `stringResource` calls re-resolve. **No need to manually walk a CompositionLocal or invalidate anything.**

### 1.7 v1 languages to ship

Match the 17 anime-extensions `src/<lang>/` folders so that a user scraping a Japanese site can read Reverb's UI in Japanese (the catalog comes in Japanese anyway, and now the chrome matches). Drop `all` (not a real locale) and add English as the default. Final v1 set = **16 languages**:

| Code | Language | RTL? | Source-folder parity |
|---|---|---|---|
| en | English (default) | no | en |
| ja | Japanese | no | ja (well, natively `src/ja/` exists) |
| es | Spanish | no | es |
| pt | Portuguese (PT-BR + PT-PT in one file with regional overrides) | no | pt |
| fr | French | no | fr |
| de | German | no | de |
| ru | Russian | no | ru |
| zh-CN | Simplified Chinese | no | zh |
| ko | Korean | no | ko |
| id | Indonesian | no | id |
| ar | Arabic | **yes** | ar |
| hi | Hindi | no | hi |
| it | Italian | no | it |
| pl | Polish | no | pl |
| sr | Serbian (Cyrillic + Latin) | no | sr |
| tr | Turkish | no | tr |
| uk | Ukrainian | no | uk |

(That's 17 entries including English. Matches the user's suggested list minus Indonesian already-included; we add hi, it, pl, sr, tr, uk for parity with anime-extensions.)

**Translator workflow:** set up a free Weblate project (hosted.weblate.org, same as Aniyomi) pointing at the GitHub repo's `app/src/main/res/values-<lang>/strings.xml`. Weblate's "Android strings" file format gives a web translator UI, propagates new keys automatically, and submits PRs. This is the same workflow Aniyomi uses — proven, free, no per-seat licensing.

**Translator v1 priority:** ship English + Japanese + Spanish + Portuguese + Simplified Chinese + Arabic at minimum (these cover the top user geographies for the app's target content — Japan, LATAM, Brazil, China, MENA). French/German/Russian/Korean/Indonesian as v1.1. The remaining 5 (hi, it, pl, sr, tr, uk) as community-contributed on Weblate.

---

## Part 2 — Content translation

### 2.1 Google ML Kit Translate (the primary choice)

**Coordinate:**

```kotlin
implementation("com.google.mlkit:translate:17.0.3")   // latest stable, Aug 2024
```

**What it is:** on-device neural translation, 58 supported languages, models downloaded on demand (~30 MB each, stored under app private storage), **free, offline after download, no API key, no quota**.

**Mental model — models are to/from English:** each downloaded model is for ONE direction pair, but Google ships them as bilingual EN-X models, so you download one model per non-English language and can translate EN↔X. For X→Y (e.g., ja→zh), ML Kit routes through English internally: ja→en→zh. So for our 17 supported languages, the user effectively downloads N models where N = the set of distinct non-English languages they touch (source OR target). For an English-speaking user wanting to read a Japanese site, that's **1 model = ~30 MB** (Japanese). For a Japanese user wanting to read a Chinese donghua site, that's **2 models = ~60 MB** (Chinese + Japanese).

**API surface (Kotlin):**

```kotlin
// 1. Build a translator for a language pair
val options = TranslatorOptions.Builder()
    .setSourceLanguage(TranslateLanguage.JAPANESE)   // or .fromLanguageTag("ja")
    .setTargetLanguage(TranslateLanguage.ENGLISH)
    .build()
val translator = Translation.getClient(options)

// 2. Download the model (async, returns Task)
val conditions = RemoteModelDownloadConditions.Builder()
    .requireWifi()           // optional
    .build()
translator.downloadModelIfNeeded(conditions)
    .addOnSuccessListener { /* ready */ }
    .addOnFailureListener { e -> /* disk full / network */ }

// 3. Translate (must be called AFTER model download succeeds)
translator.translate("鋼の錬金術師")
    .addOnSuccessListener { english -> /* "Fullmetal Alchemist" */ }

// 4. Check whether a model is already downloaded (avoid re-downloading)
val modelMgr = RemoteModelManager.getInstance(RemoteTranslateModel::class.java)
modelMgr.isModelDownloaded(RemoteTranslateModel.Builder(TranslateLanguage.JAPANESE).build())

// 5. Delete a model to free space
modelMgr.deleteDownloadedModel(RemoteTranslateModel.Builder(TranslateLanguage.JAPANESE).build())

// 6. Clean up
translator.close()
```

**Coroutines adapter** (Reverb is coroutine-only per task 2-a):

```kotlin
suspend fun Translator.translateAwait(text: String): String = suspendCancellableCoroutine { cont ->
    translate(text)
        .addOnSuccessListener { cont.resume(it) }
        .addOnFailureListener { cont.resumeWithException(it) }
    cont.invokeOnCancellation { close() }
}

suspend fun Translator.downloadModelAwait(cond: RemoteModelDownloadConditions): Unit =
    suspendCancellableCoroutine { cont ->
        downloadModelIfNeeded(cond)
            .addOnSuccessListener { cont.resume(Unit) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
```

**Quality assessment (per the ML Kit docs + community testing):**

| Pair | Quality | Notes |
|---|---|---|
| ja → en | Good | Anime title conventions are well-represented in the training corpus; "鋼の錬金術師" → "Fullmetal Alchemist", "進撃の巨人" → "Attack on Titan" (the official English titles are in the corpus). Synopses sometimes render slightly literal but readable. |
| zh → en | Good | Especially strong for Simplified. Traditional (zh-TW) routes through the same model with minor quality drop. |
| ko → en | Good | K-drama / K-pop corpus coverage is solid. |
| es → en / en → es | Excellent | Latin-script pairs are the strongest. |
| ar → en | Good | MSA only — dialects (Gulf, Levantine, Egyptian) suffer. Acceptable for anime titles which are usually MSA translations of Japanese romaji. |
| hi → en | Fair | Fewer training examples; long synopses can drift. |
| ru ↔ en | Excellent | |
| ja → zh (through en) | Fair-to-Good | The two-hop route occasionally loses nuance (e.g., onomatopoeia like "ドン" → "thud" → "咚" instead of the conventional manga sound-effect). For titles only, acceptable. |
| uk ↔ ru | Good | |
| sr ↔ en | Fair | Serbian (Cyrillic + Latin) sometimes switches scripts inconsistently. |

**Verdict:** ML Kit is the right primary. Its main weaknesses are (a) the two-hop English-pivot for X→Y pairs, and (b) weaker quality on the long tail (hi, sr). Both are mitigated by the fallback chain in Part 3.

### 2.2 ML Kit Language ID (auto-detect the source language)

**Coordinate:**

```kotlin
implementation("com.google.mlkit:language-id:17.0.6")   // latest, newer than translate
```

**What it does:** identifies the language of a string of text. Returns BCP-47 language tags (`ja`, `zh-Hant`, `ar`, etc.) with a confidence score. On-device, ~5 KB model baked into the library (no separate download needed for the basic identifier; the "bundled" version uses ~200 KB).

**API:**

```kotlin
val langId = LanguageIdentification.getClient(
    LanguageIdentificationOptions.Builder()
        .setConfidenceThreshold(0.5f)   // default 0.5
        .build()
)

langId.identifyLanguage("鋼の錬金術師")
    .addOnSuccessListener { tag ->  // "ja"
        if (tag == "und") { /* couldn't identify (text too short or mixed) */ }
    }

// Or get top-N candidates:
langId.identifyPossibleLanguages("鋼の錬金術師")
    .addOnSuccessListener { tags ->  // [{ja, 0.99}, {zh, 0.005}, ...] }
```

**Why we need it:** scraped site content arrives with no language hint in some cases — e.g., an AniList description field can be in any of 30+ languages, and the `title.native` field is in Japanese for an anime but in Chinese for a donghua. ML Kit Language ID lets us pick the right source language before constructing the `Translator`. Cost: ~5–10 ms per call, fully offline.

**Caveat:** for strings < 4 characters, Language ID returns `"und"` (undetermined). For anime titles this is rare but happens for things like "Eden" or "No.6" — fall back to the site's declared language (each SiteModule exposes `Site.language: String`).

### 2.3 Cloud translation APIs — optional paid fallback

| Service | Library | Free tier | Quality (ja→en) | Notes |
|---|---|---|---|---|
| **DeepL** | `seratch/deepl-jvm` (Kotlin, Android-compatible) or DIY OkHttp | 500K chars/month free | Excellent — best ja/en/zh/de | Pro: $5.49/mo + $25/M chars; needs API key |
| **Google Cloud Translation — Basic (v2)** | REST/OkHttp | $20 per 1M chars (no free tier since 2024) | Excellent | Same quality as the Google Translate web UI |
| **Google Cloud Translation — Advanced (v3)** | REST/gax-android | $20 per 1M chars | Excellent + Adaptive (LLM-backed) | Adds glossaries, auto-source-detect |
| **Yandex Translate** | REST | free up to ~1M chars/day, capped rate | Good for ru | Russian users' common fallback |
| **Microsoft Azure Translator** | REST, `azure-ai-translation-text` Java SDK | 2M chars/month free | Excellent | Strong for less-common pairs (uk, sr) |

**Recommendation:** ship DeepL as the optional paid fallback because (a) its free tier (500K chars/month) comfortably covers even a heavy user translating ~5000 anime synopses/month at ~100 chars each, (b) it's the gold standard for ja↔en and zh↔en, (c) the `seratch/deepl-jvm` client is pure-JVM and works on Android out of the box. User configures their API key in Settings → Translation → DeepL. If absent, falls through to the LLM tier.

### 2.4 LLM-based translation — on-device Gemma / TranslateGemma

Two relevant models:

**(a) Gemma 2 2B-IT (general instruct model)** — task 1-b's chosen on-device LLM, runs via MediaPipe LLM Inference API on Android (requires ~6 GB RAM phone; quantized model ~1.7 GB). Translation is one of its capabilities via a prompt like:

```
Translate the following {source_lang} text to {target_lang}. Preserve names of characters and places. Output only the translation, no commentary.

Text: {input}
```

Quality: **good but inconsistent** — Gemma 2 2B is a generalist; anime-specific terms (e.g., "異世界" → "isekai" or "different world" depending on context) sometimes get the wrong register. Slower than ML Kit (~1–5 s/short title vs ML Kit's ~50 ms).

**(b) TranslateGemma 2B / 2.6B** — released by Google in 2025, built on Gemma 3 architecture, **explicitly trained for translation across 55 languages**. Two lightweight variants: 2B (more compact, fits more phones) and 2.6B (slightly better quality). Same LiteRT-LM runtime as Gemma 2 2B-IT. This is the **translation-specialized sibling** of task 1-b's general LLM.

Quality vs ML Kit: TranslateGemma 2B beats ML Kit on nuance, idiom, and contextual register (e.g., correctly chooses between "isekai" loanword vs "transported-to-another-world" gloss based on surrounding text). It loses on raw latency (1–3 s vs 50 ms) and storage (1.5 GB vs 30 MB). **Best used for synopses (long-form) where ML Kit's two-hop loses coherence; not worth it for titles.**

**Recommendation:** treat the on-device LLM (Gemma 2 2B or TranslateGemma 2B if task 1-b makes it available) as the **second-tier fallback** after ML Kit, used automatically only when (a) the user has the LLM enabled AND (b) the source-target pair isn't well-served by ML Kit OR the text is > 500 chars (where ML Kit's sentence-by-sentence translation loses coherence). User sees a "Translating with on-device AI…" spinner.

### 2.5 Aniyomi's approach — does Aniyomi translate content?

**No.** Confirmed via GitHub issue [aniyomiorg/aniyomi#2020](https://github.com/aniyomiorg/aniyomi/issues/2020) titled "Automatic translation" — a community feature request asking for it; it's still open. Aniyomi shows the original-language title as returned by the extension (the extension author chooses whether to ship the English or native title in `animeDetailsParse` — most do English when available, fall back to romaji or native otherwise). Aniyomi's app UI is i18n'd (via Moko Resources + Weblate), but the **catalog content** is never translated at runtime. The user gets whatever the site returned.

**Implication for Reverb:** this is a real differentiator. Aniyomi users on Reddit/issue tracker have asked for content translation; nobody has shipped it. Reverb doing on-device content translation (with per-field toggle, caching, and override) is a feature lead.

---

## Part 3 — Translation strategy for Reverb

### 3.1 What gets translated (and what doesn't)

| Field | Translate? | Why |
|---|---|---|
| Anime title (catalog card) | ✅ if non-target-language | first thing the user scans; a Japanese-only catalog is unreadable to a Western user |
| Anime title (details page header) | ✅ + show original | persistent context |
| Synopsis / description | ✅ + show original | the user's #1 "should I watch this" signal |
| Episode title | ✅ (toggle) | useful but lower priority — many users don't read episode titles |
| Episode number | ❌ | numeric, language-agnostic |
| Genre tags | ❌ | translate via a hardcoded `genres.xml` lookup (50 fixed genres) — cheaper than calling ML Kit for "Action" |
| Studio / character names | ❌ | proper nouns; ML Kit mangles them ("bones" studio → "骨" reverse). Show romanized + original. |
| Catalog UI chrome (filters, "Popular", "Latest") | ❌ | that's the **app's** i18n, not content translation |
| Player controls, settings, errors | ❌ | app's own i18n |
| Comments / reviews | ⚠️ optional v2 | risky — slang, spoilers, quality varies |

### 3.2 When to translate

**Two complementary modes:**

1. **On-demand (always available):** every translated-capable field on the details screen has a small "あ/A" toggle button. Tap → translate just this field; tap again → show original. Loading state shows a small circular progress indicator inline. No network call needed if cached. **This is the v1 default.**

2. **Auto-translate (opt-in setting, off by default):** in Settings → Translation, "Auto-translate content when source language ≠ app language" toggle. When ON: the moment a details screen loads, fire off the translation for title + synopsis + first 5 episode titles (lazy-translate the rest on scroll). Show original under the translated text in a smaller, dimmer style (so the user always sees what was machine-translated).

**Why off by default:** ML Kit model download is a 30 MB surprise; if the user opens their first anime and we silently try to download a model, that's bad UX. The on-demand flow makes the model-download permission explicit ("Translating to English requires a one-time 30 MB download. Continue?") the first time, then auto-translates subsequently.

### 3.3 Caching — Room `translations` table

```sql
CREATE TABLE translations (
    -- Composite key: text + source + target uniquely identifies a translation
    -- hash is SHA-256 of (lowercase-trimmed-text|source|target), hex, 64 chars
    hash              TEXT PRIMARY KEY,

    original_text     TEXT NOT NULL,
    source_lang       TEXT NOT NULL,        -- BCP-47, e.g. "ja", "zh-Hant", "und"
    target_lang       TEXT NOT NULL,        -- BCP-47, e.g. "en", "pt-BR"

    translated_text   TEXT NOT NULL,
    translator        TEXT NOT NULL,        -- "mlkit" | "deepl" | "gemma" | "translategemma" | "user"

    -- Quality control
    user_override     INTEGER NOT NULL DEFAULT 0,   -- 1 if user edited; pin this row, never re-translate
    user_rating       INTEGER,                      -- nullable: -1 bad, 0 meh, 1 good (for future ranking)

    -- Lifecycle
    created_at        INTEGER NOT NULL,     -- epoch ms
    last_used_at      INTEGER NOT NULL,     -- epoch ms, updated on cache hit (for LRU eviction)
    app_version_code  INTEGER NOT NULL,     -- invalidate on app major update

    -- Optional context (helps LLM tier; ignored by ML Kit)
    context_kind      TEXT,                 -- "anime_title" | "synopsis" | "episode_title" | "genre"
    context_ref       TEXT                  -- e.g., animeId — useful for bulk re-translation after model swap
);

CREATE INDEX idx_translations_lookup ON translations(source_lang, target_lang, last_used_at DESC);
CREATE INDEX idx_translations_anime  ON translations(context_kind, context_ref);
```

**Hash function** (Kotlin):

```kotlin
fun translationKey(text: String, source: String, target: String): String {
    val normalized = text.trim().lowercase()
    val input = "$normalized|$source|$target"
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
```

**TTL / invalidation:**
- **No time-based TTL** — translations don't expire; an anime title doesn't change.
- **Invalidate on app major version bump:** when `appVersionCode / 100` increments (major), mark all `translator = "mlkit"` rows as stale (delete or `user_override = 0` re-translate flag). Cheap to do because the cache is hit lazily.
- **Invalidate when user switches translation engine:** Settings → Translation has a "Clear cache" button. Also auto-invalidates when user upgrades from ML Kit-only to ML Kit + TranslateGemma, to let the better engine re-translate on next access.
- **LRU eviction when storage > 50 MB:** query `ORDER BY last_used_at ASC LIMIT N` and delete until under threshold. Run as a `WorkManager` coroutine job during idle.

### 3.4 Batch translation

ML Kit's `Translator.translate(text)` is one-shot per call. For a details page (1 title + 1 synopsis + N episode titles), N+2 sequential calls would be too slow. Two strategies:

**(a) Concatenate with a separator** (ML Kit-recommended):

```kotlin
val SEP = "\n@@@\n"
val joined = listOf(title, synopsis, *episodeTitles).joinToString(SEP)
val translated = translator.translateAwait(joined)
val parts = translated.split(SEP)   // ML Kit preserves newlines reasonably; if a synopsis contained the SEP, fall back to (b)
```

Caveat: ML Kit sometimes reorders or strips the separator if the synopsis contains it. Mitigation: use a GUID separator and pre-flight scan the input to ensure uniqueness.

**(b) Sequential with early UI updates** (safer, slightly slower):

```kotlin
val titleDeferred = async { translator.translateAwait(title) }       // show first
val synopsisDeferred = async { translator.translateAwait(synopsis) } // show second
val episodesDeferred = episodeTitles.map { async { translator.translateAwait(it) } } // show on scroll

titleText.value = titleDeferred.await()
synopsisText.value = synopsisDeferred.await()
episodesDeferred.forEachIndexed { i, d -> episodeTexts[i] = d.await() }
```

ML Kit internally queues calls on a single background thread per `Translator` instance, so `async` calls don't actually run in parallel — but the UI updates incrementally, which feels faster to the user.

**Recommendation:** use (b) for v1 (safer; ML Kit may mangle (a) on long synopses with embedded newlines). Revisit (a) as an optimization in v1.1 once we have production telemetry on separator collisions.

### 3.5 Fallback chain

```
translate(text, sourceHint?, target):
  1. cache lookup (Room)
     ├── HIT (user_override=1) → return user-edited text immediately
     ├── HIT (any translator) → return cached text, update last_used_at
     └── MISS → continue

  2. detect source language (ML Kit Language ID, ~10ms)
     - If source == target → return original (no translation needed; cache as identity)
     - If sourceHint provided and confident → use hint instead of detection (saves 10ms)

  3. ML Kit Translate (primary)
     ├── model not downloaded → prompt user (one-time, with size) or auto-download if "Auto-download language packs" is on
     ├── model download fails (network/disk) → fall through to 4
     ├── translate succeeds → cache as translator="mlkit", return
     └── translate fails (rare; model corruption) → fall through to 4

  4. Remote LLM (if user enabled: DeepL or Google Cloud Translation)
     ├── no API key configured → fall through to 5
     ├── network down → fall through to 5
     ├── translate succeeds → cache as translator="deepl", return
     └── API error → fall through to 5

  5. On-device LLM (if user enabled: Gemma 2 2B or TranslateGemma 2B from task 1-b)
     ├── LLM not loaded (insufficient RAM / disabled) → fall through to 6
     ├── translate succeeds → cache as translator="translategemma", return
     └── LLM timeout (5s) → fall through to 6

  6. Failure
     - Cache the original text with translator="unavailable" (TTL 24h to retry later)
     - Return original with a subtle "translation unavailable" badge in UI
     - Log to crashlytics (non-fatal) for monitoring quality
```

**Why this order:** ML Kit first because it's free, on-device, fastest (~50 ms), and most predictable. DeepL second because it's the highest quality but online + metered. On-device LLM third because it's free but slow (~1–3 s) and only available on capable phones. Failure last because the user can still read the original — better than a blank.

### 3.6 Quality control — user override

On every translated field, a long-press shows a bottom sheet:

```
┌──────────────────────────────────────┐
│  Translation feedback                │
├──────────────────────────────────────┤
│  Original:  鋼の錬金術師               │
│  Translated: Fullmetal Alchemist     │
│                                      │
│  [Edit translation]                  │
│  [Rate: 👍 / 👎]                       │
│  [Report issue]                      │
└──────────────────────────────────────┘
```

- **Edit translation:** opens a text field; on save, upserts the row with `user_override = 1, translator = "user"`. Subsequent cache hits return this row forever (until user clears cache).
- **Rate 👍/👎:** writes `user_rating`; aggregated per-source-language stats shown in Settings → Translation → Quality (so the user can see "ML Kit is 92% positive on ja→en, 71% on hi→en — consider enabling DeepL for hi").
- **Report issue:** optional v2 — bundles original + translated + target + screenshot to a GitHub issue template (the Reverb repo).

---

## Part 4 — Concrete implementation

### 4.1 Gradle coordinates

`gradle/libs.versions.toml`:

```toml
[versions]
mlkit-translate = "17.0.3"
mlkit-language-id = "17.0.6"
appcompat = "1.7.0"            # for AppCompatDelegate per-app language API
room = "2.6.1"

[libraries]
mlkit-translate = { group = "com.google.mlkit", name = "translate", version.ref = "mlkit-translate" }
mlkit-language-id = { group = "com.google.mlkit", name = "language-id", version.ref = "mlkit-language-id" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.androidx.appcompat)        // for AppCompatDelegate.setApplicationLocales
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Optional DeepL fallback (Phase 2)
    implementation("com.github.seratch:deepl-jvm:0.0.4")

    // Optional on-device LLM (depends on task 1-b)
    // implementation("ai.google.android:mediapipe-llm-inference:0.10.x")
}
```

**Note on AppCompat 1.7 vs 1.6:** 1.7.0 (May 2024) is the current stable; 1.6.1 is the minimum that has the per-app language API. Use 1.7.x — it's a drop-in and has bug fixes for the language API on Android 14.

### 4.2 `TranslationService` interface sketch

```kotlin
package reverb.translation

import kotlinx.coroutines.flow.Flow

/** BCP-47 language tag, e.g. "ja", "zh-Hant", "en", "pt-BR". "und" = unknown. */
typealias LangTag = String

data class TranslationResult(
    val text: String,
    val translator: Translator,        // which engine produced this
    val fromCache: Boolean,
    val sourceLang: LangTag,
    val targetLang: LangTag,
)

enum class Translator { MLKIT, DEEPL, GEMMA, TRANSLATEGEMMA, USER, UNAVAILABLE }

interface TranslationService {

    /**
     * Translate [text] from [sourceHint] (or auto-detect if null) to [target].
     * Hits cache first; otherwise runs the fallback chain.
     * Never throws — on failure returns [TranslationResult] with translator=UNAVAILABLE and text=original.
     */
    suspend fun translate(
        text: String,
        target: LangTag,
        sourceHint: LangTag? = null,
        contextKind: TranslationContext? = null,
        contextRef: String? = null,
    ): TranslationResult

    /** Batch convenience — translates each text in parallel (subject to engine's queue). */
    suspend fun translateAll(
        texts: List<String>,
        target: LangTag,
        sourceHint: LangTag? = null,
        contextKind: TranslationContext? = null,
        contextRef: String? = null,
    ): List<TranslationResult>

    /** Detect the dominant language of [text]. Returns "und" if low-confidence. ~10ms. */
    suspend fun detect(text: String): LangTag

    /** User overrides a translation. Persists to Room with translator=USER. */
    suspend fun setUserOverride(
        originalText: String,
        sourceLang: LangTag,
        targetLang: LangTag,
        userText: String,
    )

    /** True if the ML Kit model for [lang] is downloaded on this device. */
    suspend fun isModelDownloaded(lang: LangTag): Boolean

    /** Download the ML Kit model for [lang]. Emits progress 0..1. */
    fun downloadModel(lang: LangTag, requireWifi: Boolean): Flow<Float>

    /** Delete a model to reclaim space. */
    suspend fun deleteModel(lang: LangTag)

    /** List all available source/target language pairs ML Kit supports. */
    fun supportedLanguages(): List<LangTag>     // 58 entries

    /** Clear all cached translations (Settings → Translation → Clear cache). */
    suspend fun clearCache()
}

enum class TranslationContext { ANIME_TITLE, SYNOPSIS, EPISODE_TITLE, GENRE }
```

**Default implementation `TranslationServiceImpl`** orchestrates the chain from §3.5. Injects:
- `MlKitTranslator` (wraps `com.google.mlkit:translate`)
- `MlKitLanguageIdentifier` (wraps `language-id`)
- `TranslationDao` (Room)
- `DeepLClient` (lazy, only constructed if API key present)
- `OnDeviceLlmClient` (lazy, only constructed if task 1-b LLM is loaded)

### 4.3 Room schema

`TranslationEntity.kt`:

```kotlin
@Entity(
    tableName = "translations",
    indices = [
        Index(value = ["source_lang", "target_lang", "last_used_at"]),
        Index(value = ["context_kind", "context_ref"]),
    ]
)
data class TranslationEntity(
    @PrimaryKey val hash: String,
    @ColumnInfo(name = "original_text") val originalText: String,
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    @ColumnInfo(name = "target_lang") val targetLang: String,
    @ColumnInfo(name = "translated_text") val translatedText: String,
    @ColumnInfo(name = "translator") val translator: String,        // "MLKIT" | "DEEPL" | "GEMMA" | "TRANSLATEGEMMA" | "USER" | "UNAVAILABLE"
    @ColumnInfo(name = "user_override") val userOverride: Boolean = false,
    @ColumnInfo(name = "user_rating") val userRating: Int? = null,  // -1, 0, 1
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
    @ColumnInfo(name = "app_version_code") val appVersionCode: Int,
    @ColumnInfo(name = "context_kind") val contextKind: String? = null,
    @ColumnInfo(name = "context_ref") val contextRef: String? = null,
)
```

`TranslationDao.kt`:

```kotlin
@Dao
interface TranslationDao {
    @Query("SELECT * FROM translations WHERE hash = :hash LIMIT 1")
    suspend fun get(hash: String): TranslationEntity?

    @Query("""
        SELECT * FROM translations
        WHERE original_text = :text AND source_lang = :source AND target_lang = :target
        LIMIT 1
    """)
    suspend fun find(text: String, source: String, target: String): TranslationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TranslationEntity)

    @Query("UPDATE translations SET last_used_at = :ts WHERE hash = :hash")
    suspend fun touch(hash: String, ts: Long)

    @Query("UPDATE translations SET translated_text = :text, translator = 'USER', user_override = 1 WHERE hash = :hash")
    suspend fun setUserOverride(hash: String, text: String)

    @Query("UPDATE translations SET user_rating = :rating WHERE hash = :hash")
    suspend fun setRating(hash: String, rating: Int?)

    @Query("DELETE FROM translations WHERE hash = :hash")
    suspend fun delete(hash: String)

    @Query("DELETE FROM translations WHERE translator != 'USER'")  // keep user overrides
    suspend fun clearNonUserOverrides()

    @Query("DELETE FROM translations WHERE translator = 'MLKIT' AND app_version_code < :minVersion")
    suspend fun invalidateOldMlKit(minVersion: Int)

    @Query("SELECT COUNT(*) as count, COALESCE(SUM(LENGTH(translated_text)), 0) as bytes FROM translations")
    suspend fun stats(): CacheStats

    @Query("DELETE FROM translations WHERE hash IN (SELECT hash FROM translations ORDER BY last_used_at ASC LIMIT :n)")
    suspend fun evictOldest(n: Int)
}

data class CacheStats(val count: Int, val bytes: Long)
```

### 4.4 Compose UI — details screen

```kotlin
@Composable
fun AnimeDetailsScreen(
    animeId: String,
    viewModel: AnimeDetailsViewModel = hiltViewModel(),
    translationService: TranslationService = hiltViewModel<TranslationViewModel>().service,
) {
    val uiState by viewModel.uiState.collectAsState()
    val appLocale = LocalContext.current.resources.configuration.locales[0].language
    val autoTranslate by translationService.autoTranslateEnabled.collectAsState()

    // Lazy Column with header
    LazyColumn {
        item { PosterAndTitle(uiState.anime, appLocale, autoTranslate, translationService) }
        item { Synopsis(uiState.anime, appLocale, autoTranslate, translationService) }
        items(uiState.episodes) { ep ->
            EpisodeRow(ep, appLocale, autoTranslate, translationService)
        }
    }
}

@Composable
private fun PosterAndTitle(
    anime: Anime,
    appLocale: LangTag,
    autoTranslate: Boolean,
    ts: TranslationService,
) {
    val scope = rememberCoroutineScope()
    var translatedTitle by remember(anime.id) { mutableStateOf<String?>(null) }
    var showOriginal by remember(anime.id) { mutableStateOf(false) }
    var translating by remember(anime.id) { mutableStateOf(false) }

    // Auto-translate: fire on first composition if source != target
    LaunchedEffect(anime.title, appLocale, autoTranslate) {
        if (autoTranslate && anime.title.isNotEmpty()) {
            translating = true
            val result = ts.translate(
                anime.title, target = appLocale,
                sourceHint = anime.titleLanguage,        // site-provided hint
                contextKind = TranslationContext.ANIME_TITLE,
                contextRef = anime.id,
            )
            translatedTitle = result.text
            translating = false
        }
    }

    Column {
        AsyncImage(anime.posterUrl, contentDescription = null)
        if (translatedTitle != null && !showOriginal) {
            Text(translatedTitle!!, style = MaterialTheme.typography.headlineSmall)
            Text(
                anime.title,                                            // original under
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(anime.title, style = MaterialTheme.typography.headlineSmall)
        }

        // The translate toggle
        Row {
            if (translating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else if (translatedTitle != null) {
                TextButton(onClick = { showOriginal = !showOriginal }) {
                    Text(if (showOriginal) stringResource(R.string.show_translation)
                         else stringResource(R.string.show_original))
                }
            } else {
                TextButton(onClick = {
                    scope.launch {
                        translating = true
                        val r = ts.translate(
                            anime.title, target = appLocale,
                            sourceHint = anime.titleLanguage,
                            contextKind = TranslationContext.ANIME_TITLE,
                            contextRef = anime.id,
                        )
                        translatedTitle = r.text
                        translating = false
                    }
                }) { Text(stringResource(R.string.translate)) }
            }
        }
    }
}

// Synopsis and EpisodeRow follow the same pattern with contextKind = SYNOPSIS / EPISODE_TITLE

@Composable
private fun TranslationFeedbackSheet(
    original: String,
    translated: String,
    onEdit: (String) -> Unit,
    onRate: (Int?) -> Unit,
) { /* BottomSheet with original/translated fields + Edit + 👍/👎 buttons */ }
```

### 4.5 Settings screen

```kotlin
@Composable
fun TranslationSettingsScreen(vm: TranslationSettingsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsState()

    LazyColumn {
        // ─── Auto-translate toggle ───
        item {
            SwitchSetting(
                title = stringResource(R.string.settings_auto_translate_title),         // "Auto-translate content"
                subtitle = stringResource(R.string.settings_auto_translate_subtitle),   // "Translate titles and synopses when source ≠ app language"
                checked = state.autoTranslate,
                onChange = vm::setAutoTranslate,
            )
        }

        // ─── Auto-download language packs ───
        item {
            SwitchSetting(
                title = stringResource(R.string.settings_auto_download_models),
                subtitle = stringResource(R.string.settings_auto_download_models_subtitle),
                checked = state.autoDownloadModels,
                onChange = vm::setAutoDownloadModels,
            )
        }

        // ─── Downloaded language packs ───
        item {
            Text(stringResource(R.string.settings_language_packs), style = MaterialTheme.typography.titleMedium)
        }
        items(state.downloadedModels) { model ->
            LanguagePackRow(
                lang = model.lang,
                sizeBytes = model.sizeBytes,
                isDownloaded = true,
                onRemove = { vm.deleteModel(model.lang) },
            )
        }

        // ─── Available language packs (download on demand) ───
        item {
            Text(stringResource(R.string.settings_available_packs), style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(R.plurals.language_packs_count, state.availableModels.size, state.availableModels.size),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(state.availableModels) { model ->
            LanguagePackRow(
                lang = model.lang,
                sizeBytes = model.sizeBytes,
                isDownloaded = false,
                downloadProgress = state.downloadProgress[model.lang],
                onDownload = { vm.downloadModel(model.lang) },
                onCancel = { vm.cancelDownload(model.lang) },
            )
        }

        // ─── DeepL (optional) ───
        item {
            Text(stringResource(R.string.settings_deepl), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.deeplApiKey,
                onValueChange = vm::setDeepLApiKey,
                label = { Text(stringResource(R.string.settings_deepl_api_key)) },
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                stringResource(R.string.settings_deepl_hint),  // "500K chars/month free. Get a key at deepl.com/pro-api"
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ─── On-device LLM (depends on task 1-b) ───
        item {
            Text(stringResource(R.string.settings_on_device_llm), style = MaterialTheme.typography.titleMedium)
            SwitchSetting(
                title = stringResource(R.string.settings_use_llm_for_long_texts),
                subtitle = stringResource(R.string.settings_use_llm_for_long_texts_subtitle),  // "Use Gemma for synopses >500 chars (slower but better quality)"
                checked = state.useLlmForLongTexts,
                onChange = vm::setUseLlmForLongTexts,
                enabled = state.llmAvailable,   // false if phone has <6 GB RAM
            )
        }

        // ─── Cache stats ───
        item {
            Text(stringResource(R.string.settings_cache), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_cache_size, formatBytes(state.cacheBytes)))
            Text(stringResource(R.string.settings_cache_entries, state.cacheCount))
            Button(onClick = vm::clearCache) { Text(stringResource(R.string.settings_cache_clear)) }
        }

        // ─── Quality dashboard ───
        item {
            Text(stringResource(R.string.settings_translation_quality), style = MaterialTheme.typography.titleMedium)
            state.qualityByPair.forEach { (pair, stats) ->
                Row {
                    Text("${pair.source} → ${pair.target}")
                    Text("👍 ${stats.up}   👎 ${stats.down}")
                    LinearProgressIndicator(progress = stats.up.toFloat() / (stats.up + stats.down))
                }
            }
        }
    }
}
```

### 4.6 Storage impact (estimates)

| Component | Per-unit | Default set | Default total | Worst case (power user) |
|---|---|---|---|---|
| ML Kit Language ID model | ~5 KB (bundled) | always | ~5 KB | ~5 KB |
| ML Kit Translate model | ~30 MB | 0 (download on demand) | 0 | up to ~510 MB if user downloads all 17 models |
| TranslateGemma 2B (task 1-b dependency) | ~1.5 GB (quantized) | 0 | 0 | ~1.5 GB |
| Room translation cache | variable (~500 B/entry avg) | grows on use | ~5 MB after first month | ~50 MB (LRU-evicted) |
| DeepL client + deps | ~200 KB | 0 (only if user enables) | 0 | ~200 KB |
| App UI `strings.xml` × 17 langs | ~50 KB/lang | 17 langs | ~850 KB | ~850 KB |
| **App APK + libs subtotal** | | | **~30 MB** | **~30 MB** |

**Per-user typical scenarios:**

- **English user, mostly watching subbed Japanese anime:** downloads 1 ML Kit model (ja, ~30 MB). Total translation footprint: **~30 MB**.
- **English user, watching Japanese + Chinese + Korean anime:** downloads 3 models (~90 MB). Total: **~90 MB**.
- **Japanese user, watching Chinese donghua + English-subtitled anime:** downloads 2 models (zh, en — ~60 MB). Total: **~60 MB**.
- **Power user who enables TranslateGemma:** ~1.5 GB one-time + cache. Total: **~1.55 GB**. Show a prominent warning before this download.

**Decision rule for the UI:** always show "this requires a 30 MB one-time download" before each model download; never auto-download more than one model without user consent; show a Storage Usage card in Settings summarizing current usage with a "Manage" shortcut to system Settings → Apps → Reverb → Storage.

---

## Part 5 — Summary & recommendations

### TL;DR

1. **App UI i18n:** plain `res/values/strings.xml` + `stringResource(R.string.x)` + `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(...))` (AndroidX AppCompat 1.7.0). **Do NOT use Moko Resources** — Aniyomi uses it only because it's KMP-destined; for a single-platform Android app it's pure overhead. Ship 17 languages matching anime-extensions' `src/<lang>/` folders. Weblate for community translations.

2. **Content translation:** ML Kit Translate (`com.google.mlkit:translate:17.0.3`) is the primary on-device engine, free, 58 langs, ~30 MB/model. ML Kit Language ID (`com.google.mlkit:language-id:17.0.6`) auto-detects source. Optional paid DeepL fallback (500K chars/month free; `seratch/deepl-jvm` client). Optional on-device LLM fallback — either Gemma 2 2B-IT (task 1-b's general LLM) or, **better**, TranslateGemma 2B (the translation-specialized sibling released by Google in 2025, trained on 55 langs, ~1.5 GB, runs via the same LiteRT-LM runtime). Aniyomi does NOT currently translate content (open feature request #2020) — Reverb doing this is a differentiator.

3. **Strategy:** cache every translation in Room (`translations` table, SHA-256 hash of normalized-text|source|target as PK, `user_override` flag pins user-edited rows, LRU eviction at 50 MB). Auto-translate is opt-in (off by default) — first model download triggers a permission dialog. Per-field "show original / show translated" toggle on every translated text. Fallback chain: cache → ML Kit → DeepL (if configured) → on-device LLM (if enabled) → show original with "translation unavailable" badge.

4. **Storage:** ~30 MB per ML Kit language model (downloaded on demand). Worst case ~510 MB if user downloads all 17 models; typical 30–90 MB. TranslateGemma 2B adds ~1.5 GB if enabled (task 1-b dependency).

### Open questions for the orchestrator

- **Q1.** Task 1-b is the on-device LLM (Gemma 2 2B). Does the orchestrator want Reverb to ship TranslateGemma 2B (the translation-specialized sibling) as a separate model, or reuse the general Gemma for translation with a prompt? **Recommendation:** ship TranslateGemma 2B for v1 — it's the same LiteRT-LM runtime, so the user only pays once for the runtime; the 1.5 GB TranslateGemma model is additive and optional.
- **Q2.** The `Site.language` field (already in PLAN.md line 239) is a per-site language hint. Should Reverb prefer it over ML Kit Language ID for source detection? **Recommendation:** use the site hint as the source first; only fall back to Language ID when the site hint is `"all"` or missing. Saves ~10 ms per detect and avoids false positives on short strings.
- **Q3.** For AniList-backed catalogs (4 of 5 sites in batch 4-a): AniList already returns `title.english`, `title.romaji`, `title.native`. Should Reverb skip ML Kit translation entirely when AniList provides the target-language title natively? **Recommendation:** yes — if `title.english` is non-null and the user's target is English, use it directly (cache as `translator = "anilist"`, no model download needed). This is a free win that bypasses ML Kit for ~70% of mainstream anime.

### Implementation phases

- **Phase 0 (week 1–2):** ship App UI i18n with `strings.xml` + AppCompat 1.7 per-app language API + 5 starter languages (en, ja, es, pt, zh-CN). No content translation yet.
- **Phase 1 (week 3–4):** ship ML Kit Translate + Language ID + Room cache + per-field translate toggle on the details screen. Auto-translate setting OFF by default. On-demand downloads.
- **Phase 2 (week 5–6):** add DeepL client + settings UI for API key + per-source-language quality dashboard + user override / rating.
- **Phase 3 (when task 1-b ships):** wire in TranslateGemma 2B as the third-tier fallback for synopses > 500 chars; add the LLM toggle in Settings.
- **Phase 4 (community):** ship on Weblate; community contributes the remaining 12 languages. Auto-invalidate ML Kit cache on major app updates.

### Files this research produced

- This report: `/home/z/my-project/research/01-c-translation-i18n.md` (~25 KB, 5 parts + summary)
- Worklog entry: `/home/z/my-project/worklog.md` (appended, see Task ID 1-c)
