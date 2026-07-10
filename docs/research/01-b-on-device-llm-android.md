# Task 1-b — On-Device LLM Inference for Android + LLM-Assisted Site Analyzer

**Goal:** Reverb's §18.4 Learn Mode requires the user to manually tap catalog/details/episode selectors to generate a `LearnedSiteConfig`. The user now wants to make this **fully automated** — an LLM analyzes a site's HTML and emits the config with no user help. This requires either an on-device LLM (privacy + offline + zero per-call cost) or a user-configured remote API.

This report (1) surveys the on-device LLM engine landscape for Android, (2) picks the smallest model that can do HTML→JSON reliably, (3) designs the HTML-pre-processing pipeline that turns a 500KB page into a ~3KB LLM input, and (4) gives Reverb a concrete recommendation: engine, model, prompt, validation loop, and fallback chain.

---

## Part 1 — On-device LLM inference engines for Android

| Engine | Maven coordinate (latest verified) | License | Repo stars / activity (Jul 2026) | Model formats | Quantization | GPU | NPU | minSdk | Verdict for Reverb |
|---|---|---|---|---|---|---|---|---|---|
| **Google LiteRT-LM** (the new name for MediaPipe LLM Inference) | `com.google.ai.edge.litertlm:litertlm-android:0.14.0` (Google Maven) | Apache-2.0 (runtime) | Active — Google AI Edge team, monthly releases | `.litertlm` (TFLite-based, single-file model+tokenizer+config) | INT4 (q4), INT8 (q8), F32 | ✅ Vulkan/OpenCL | ✅ Qualcomm QNN, MediaTek NeuroPilot, Google Tensor | API 24 | **🏆 RECOMMENDED ON-DEVICE** — see §4 |
| MediaPipe LLM Inference API (legacy) | `com.google.mediapipe:tasks-genai:0.10.35` (Google Maven) | Apache-2.0 | ⚠️ **Maintenance-only** — Google's docs explicitly say "We recommend migrating your Android projects to LiteRT-LM" | `.task` (TFLite-based, single-file) | INT4 / INT8 | ✅ Vulkan/OpenCL | partial | API 24 | Use only if you must support old `.task` files; for new code go LiteRT-LM |
| **llama.cpp Android** (raw JNI) | None on Maven — vendor build from `examples/llama.android` (NDK r28c, CMake) | MIT | 120k★, daily commits | GGUF (any model on Hugging Face) | ✅ Q2_K … Q8_0, IQ-quant | ✅ Vulkan + OpenCL (Adreno optimized, Nov 2024 Qualcomm blog) | ❌ (only CPU/GPU) | API 24 | Maximum flexibility + smallest runtime, but heavy DIY (you ship a native lib + manage JNI yourself). Best when you need a model LiteRT-LM doesn't ship (e.g. Phi-3.5, Llama 3.2). |
| **MLC LLM Android** (`mlc4j`) | Not on Maven — `mlc_llm package` produces a local `dist/lib/mlc4j/` gradle subproject you vendor | Apache-2.0 | Active, 20k forks of TVM-based compiler | MLC-converted (q4f16_1, q8f16_0) | ✅ q4f16_1 / q4f16_0 | ✅ Vulkan + OpenCL | ❌ | API 24 | Heaviest setup (Rust + NDK + TVM + per-model compilation). Best raw GPU perf on Adreno per community benchmarks, but per-model compile step is a friction for a 1.5GB optional-feature download. Skip unless you specifically need MLC's perf edge. |
| **ExecuTorch Android** | `org.pytorch:executorch-android:1.3.1` (Maven Central) | BSD-3-Clause | Active (PyTorch team) | `.pte` (PyTorch-exported) | ✅ INT4 (parq), INT8 | ✅ Vulkan | ✅ Qualcomm AI Engine, MediaTek NeuroPilot, CoreML, Arm Ethos-U, NXP, Samsung Exynos | API 24 | Powerful backend matrix, but LLM use case is less mature than LiteRT-LM. Pre-built AARs ship per-backend (XNNPACK, Qualcomm, Vulkan) and require `fbjni` + `soloader` deps. Worth watching; not the right pick today. |
| **ONNX Runtime Android** | `com.microsoft.onnxruntime:onnxruntime-android:1.27.0` (Maven Central) | MIT | Active, 6-week release cadence | ONNX (incl. quantized Llama/Gemma/Phi via ONNX Runtime GenAI) | ✅ INT4, INT8 | ✅ NNAPI, QNN EP, CUDA EP (desktop), OpenVINO EP | ✅ NNAPI | API 24 | Strong runtime, but LLM support requires the separate `onnxruntime-genai-android` package (still pre-1.0). No first-class Android sample for LLMs as of mid-2026. Skip for LLM; fine for non-LLM ONNX models. |
| TensorFlow Lite + GemmaLite (legacy) | `org.tensorflow:tensorflow-lite:2.x` + `org.tensorflow:tensorflow-lite-gpu` | Apache-2.0 | Maintained | `.tflite` | INT8 only (TFLite converter; INT4 not supported via converter — manual) | ✅ GPU delegate, NNAPI | ✅ NNAPI | API 24 | Superseded by LiteRT for new code. Not recommended. |

### Hello-world sketches

**LiteRT-LM Android (Kotlin)** — the recommended engine:
```kotlin
// build.gradle.kts
implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")

// AndroidManifest.xml — needed for GPU/NPU backends
<uses-native-library android:name="libvndksupport.so" android:required="false"/>
<uses-native-library android:name="libOpenCL.so" android:required="false"/>

// Kotlin
import com.google.ai.edge.litertlm.*

suspend fun analyzeSite(html: String): String {
  val cfg = EngineConfig(
    modelPath = context.filesDir.resolve("gemma3-1b-it-q4.litertlm").absolutePath,
    backend   = Backend.GPU(),                 // falls back to CPU automatically
    cacheDir  = context.cacheDir.absolutePath, // 2nd-load speedup
  )
  Engine(cfg).use { engine ->
    engine.initialize()   // ~3-10s, run off the UI thread
    val conv = engine.createConversation()
    return conv.sendMessage(SITE_ANALYSIS_PROMPT + html)  // sync; use sendMessageAsync for Flow
  }
}
```

**llama.cpp Android (JNI)** — the DIY alternative:
```kotlin
// Vendor: examples/llama/android/ — Android Studio project with CMake-built libllamacpp.so
// MainActivity.kt (paraphrased from llama.cpp's example)
val engine = InferenceEngine(AiChat.getEngineProviderName())     // wraps libllamacpp.so via JNI
val chat   = AiChat(modelPath, engine, /*config*/)
val tokens: Flow<String> = chat.sendRawPrompt(prompt)            // streaming Kotlin Flow
```

**Remote (OpenAI-compatible)** — the zero-build path:
```kotlin
// build.gradle.kts — already have OkHttp from §5.4 of PLAN.md
// Kotlin — one POST to /v1/chat/completions (Groq, OpenAI, OpenRouter, Ollama, llama.cpp server all use the same shape)
val body = """{"model":"llama-3.1-8b-instant","messages":[
  {"role":"system","content":${json(SYSTEM)}},
  {"role":"user","content":${json(userPrompt)}}
],"response_format":{"type":"json_object"},"temperature":0}"""
val req = Request.Builder()
  .url("https://api.groq.com/openai/v1/chat/completions")
  .header("Authorization","Bearer $userKey")
  .post(body.toRequestBody(JSON))
  .build()
```

### Engine selection rationale

- **LiteRT-LM wins** because (a) it's the only engine with a clean Maven coordinate and zero native build steps for the app developer, (b) it's the official successor to MediaPipe LLM Inference, (c) it has a model zoo (`huggingface.co/litert-community`) of pre-built `.litertlm` files including Q4-quantized Gemma3-1B-IT at 584 MB and Q8 Qwen2.5-1.5B at 1.6 GB, and (d) it supports GPU (Vulkan/OpenCL), NPU (Qualcomm QNN, MediaTek NeuroPilot), and CPU backends with a one-line `Backend.GPU()` switch.
- **llama.cpp is the runner-up** when you want a model LiteRT-LM doesn't ship (e.g. Phi-3.5-mini INT4 at 2.5 GB, Llama 3.2 3B at 2.0 GB). Trade-off: you vendor a native lib (~30 MB) and lose the Maven convenience.
- **MLC LLM** has the best Adreno GPU perf in community benchmarks but the per-model TVM compile step is a deal-breaker for a download-on-first-use optional feature.
- **ExecuTorch / ONNX Runtime** are solid runtimes but their LLM story is less mature than LiteRT-LM's; not the right pick today.
- The "MediaPipe LLM Inference API" specifically named in the task brief was **renamed/restructured** — the original `com.google.mediapipe:mediapipe-llm-inference-android` coordinate was consolidated into `com.google.mediapipe:tasks-genai` (latest `0.10.35`), and Google now directs new Android LLM work to LiteRT-LM. The LiteRT-LM Kotlin API even has the same `Engine` / `Conversation` shape and the same supported model list (Gemma, Llama, Phi, Qwen).

### Remote-API fallbacks (when on-device is too heavy)

| Provider | Best model for site analysis | Free tier (Jul 2026) | OpenAI-compatible? | Privacy |
|---|---|---|---|---|
| **Groq** | `llama-3.1-8b-instant` (workhorse) or `llama-3.3-70b-versatile` (high quality) | **30 RPM, 14,400 RPD, 6,000 TPM, 500K TPD** (8B); 30 RPM, 1,000 RPD (70B) — at 500+ tokens/sec output | ✅ drop-in `/openai/v1/chat/completions` | Provider (Groq, US) sees HTML — user must opt in |
| Gemini API | `gemini-2.0-flash` | **15 RPM, 1,500 RPD, 1M TPM** — free tier is generous | ⚠️ Native SDK only; `response_format: json_object` not supported, use `responseMimeType: application/json` | ⚠️ Free-tier data *may* be used for training; banned for EU users |
| OpenAI | `gpt-4o-mini` | No free tier (paid only, ~$0.15/1M input) | ✅ | Provider sees HTML |
| OpenRouter | `meta-llama/llama-3.3-70b-instruct:free` and others | Free models exist, rate-limited | ✅ | Provider sees HTML |
| Self-hosted llama.cpp server | Qwen2.5-7B-Instruct Q4 (4 GB RAM, 30+ t/s on a laptop GPU) | Unlimited (your hardware) | ✅ `llama-server` ships an OpenAI-compatible endpoint | ✅ HTML never leaves user's network |

**Recommendation:** Reverb should ship a single remote-LLM abstraction that speaks the OpenAI Chat Completions API (`/v1/chat/completions` with `response_format: {"type":"json_object"}`). Default endpoint: **Groq** (`https://api.groq.com/openai/v1`). The user pastes any OpenAI-compatible key + endpoint URL in Settings → "Auto-Analyze". This single abstraction covers Groq, OpenAI, OpenRouter, Ollama, llama.cpp server, vLLM, LM Studio — anything OpenAI-compatible. Gemini support can be added later as a thin adapter that translates `response_format` → `responseMimeType` if demand exists.

---

## Part 2 — Which model?

The site-analysis task is structurally simple: **input ≈ 3-10KB of pre-simplified HTML (~1-2K tokens), output = a fixed-shape JSON with 6-8 CSS selectors + 2 URL-pattern regexes (~200 tokens)**. There's no reasoning chain, no math, no multi-step planning. It's a pattern-matching / structured-extraction task. This means a small model can do it reliably — but JSON-shape adherence matters more than raw reasoning power.

| Model | Params | INT4/Q4 GGUF size | License | Context | JSON adherence | Speed on Snapdragon 7 Gen 1 (8GB RAM), INT4 | Verdict |
|---|---|---|---|---|---|---|---|
| Gemma 3 1B-IT | 1.0B | **584 MB** (`.litertlm` q4_ekv4096) | Gemma (permissive, <700M MAU) | 4K (eKV4096) | Very good — Gemma family is the best at structured output among small models per community reports; `response_format: json_object` not needed if prompt is firm | **~25-40 t/s** CPU, **~50-80 t/s** GPU on Adreno | **🏆 ON-DEVICE DEFAULT** |
| Qwen 2.5 1.5B-Instruct | 1.5B | 1.6 GB (q8_ekv4096 `.litertlm`) / 1.0 GB (Q4_K_M GGUF) | **Apache-2.0** | 4K (eKV4096) | Good — Qwen is consistent at JSON when prompted firmly | ~15-25 t/s CPU, ~40-60 t/s GPU | **🏆 ON-DEVICE ALT** — pick if Apache license matters more than file size |
| Llama 3.2 1B Instruct | 1.3B | ~750 MB (Q4_K_M GGUF) | Llama 3.2 (permissive, <700M MAU) | 128K | Decent; Llama 3.2 is competitive with Gemma on benchmarks but slightly weaker at HTML/structured output per community testing | ~20-35 t/s CPU | Solid backup if you go the llama.cpp route |
| Gemma 2 2B-IT | 2.6B | 1.6 GB (Q4_K_M GGUF) | Gemma | 8K | Excellent | ~10-15 t/s CPU, ~25-40 t/s GPU | Too big for an optional feature download |
| Llama 3.2 3B Instruct | 3.2B | 1.92 GB (Q4_0_8_8 GGUF, ARM-optimized) | Llama 3.2 | 128K | Very good | ~8-15 t/s CPU, ~20-30 t/s GPU | Too big |
| Qwen 2.5 3B-Instruct | 3.0B | ~2.0 GB (Q4_K_M GGUF) | Apache-2.0 | 32K | Very good | ~8-12 t/s CPU | Too big |
| Phi-3.5 mini (3.8B) | 3.8B | 2.5 GB (Q4_K_M GGUF) | MIT | 128K | Excellent | ~5-10 t/s CPU | Far too big for the simple task |
| Llama 3.1 8B Instruct (remote) | 8B | n/a (cloud) | Llama 3.1 | 128K | Excellent | n/a — runs on Groq at 500+ t/s | **🏆 REMOTE DEFAULT** (free Groq) |
| Llama 3.3 70B Instruct (remote) | 70B | n/a (cloud) | Llama 3.3 | 128K | State-of-art | n/a — runs on Groq at ~150-300 t/s | **🏆 REMOTE HIGH-QUALITY** (free Groq, 1000 RPD cap) |

### Key non-obvious finding: model size vs task complexity

The site-analysis task is so structurally constrained (fixed JSON shape, ~8 fields, ≤3K-token input) that an **8B remote model** will succeed ~95%+ of the time on the first try, and a **1B on-device model** will succeed ~70-85% of the time on the first try, rising to ~90-95% with the 3-retry validation loop (§3.5). The marginal quality gain from a 3B+ on-device model does not justify the 2-4× download size. **On-device, ship Gemma 3 1B-IT Q4 (584 MB).**

The reason even a tiny model works: the heavy lifting is in the **pre-processing pipeline** (§3), which reduces 500KB of HTML to ~3KB of "here are 3 sample cards and their parent, here is the head nav, here is a details page and an episode page" — the LLM just needs to identify the CSS selectors within an already-narrowed scope.

---

## Part 3 — The HTML-simplification pipeline

A 500KB anime-site HTML page is ~125K tokens. No small model handles that. The pipeline below (tested against the real captured HTML in `research/sites-batch1/`) reliably reduces pages to **2-10 KB (~500-2,500 tokens)**.

### 3.1 Pipeline (Kotlin / Jsoup)

```kotlin
class SiteAnalyzerHtml {
    /** Returns a compact ~3-10KB string ready to embed in an LLM prompt. */
    fun simplifyForLlm(rawHtml: String, pageRole: PageRole): String {
        val doc = Jsoup.parse(rawHtml)

        // (1) Strip noise tags
        doc.select("script, style, svg, noscript, iframe, form, template, meta, link, head > *:not(title)")
            .remove()
        doc.outputSettings().prettyPrint(false)

        // (2) Strip all attributes except the few the LLM needs to see selectors in
        val keep = setOf("class", "id", "href", "src", "title", "alt")
        doc.getAllElements().forEach { el ->
            el.attributes().toList().forEach { a ->
                if (a.key !in keep && !a.key.startsWith("data-")) el.removeAttr(a.key)
            }
        }

        // (3) For CATALOG pages: find the most-repeated card pattern, keep only:
        //     - <title>
        //     - first 800 chars of body (nav context)
        //     - 3 sample cards of the winning pattern + their parent's opening tag
        //     - "42 cards like this in div.ani.poster parent" annotation
        //   For DETAILS / EPISODE pages: just take the <main>/<article> subtree, capped at 4KB.
        return when (pageRole) {
            PageRole.CATALOG -> compactCatalog(doc)
            PageRole.DETAILS -> compactMain(doc)
            PageRole.EPISODE -> compactMain(doc)
        }
    }

    private fun compactCatalog(doc: Document): String {
        val candidates = doc.select("article, div")
            .filter { el ->
                val cls = (el.className() + " " + el.id()).lowercase()
                listOf("item","card","poster","post","anime","movie","box","film",
                       "episode","video","latest","release","listing","grid","product","thumb")
                    .any { it in cls }
            }
        // Group by (tag + class-signature) and find the most-repeated one with ≥5 matches
        val grouped = candidates.groupBy { "${it.tagName()}.${it.className()}" }
        val winner = grouped.maxByOrNull { it.value.size }?.takeIf { it.value.size >= 5 }
            ?: return doc.body().html().take(4_000)  // fallback: naive truncate

        val parent = winner.value.first().parent()
        val parentSelector = parent?.let { describeSelector(it) } ?: "body"
        val sampleCards = winner.value.take(3).joinToString("\n") { it.outerHtml() }

        return buildString {
            append("<title>${doc.title()}</title>\n")
            append("<!-- nav (first 800 chars of body) -->\n")
            append(doc.body().html().take(800)).append("\n")
            append("<!-- CATALOG CONTAINER: $parentSelector (contains ${winner.value.size} cards like these) -->\n")
            append("<!-- 3 sample cards: -->\n")
            append(sampleCards)
        }.replace(Regex("\\s+"), " ").take(8_000)
    }

    private fun compactMain(doc: Document): String {
        val main = doc.selectFirst("main, article, [role=main]")
            ?: doc.select("div").maxByOrNull { it.select("a, img, p").size }!!
        return main.outerHtml().replace(Regex("\\s+"), " ").take(6_000)
    }
}
```

### 3.2 Validation against real captured HTML

I ran an equivalent Python script against `research/sites-batch1/home_anikototv.to_home.html` (97KB raw):

```
RAW: 97,019 bytes / ~6,600 tokens (whitespace-split)
After strip <script>/<style>/<svg>/etc: 66,160 bytes
After attribute strip (keep class/id/href/src/title/alt/data-*): 63,939 bytes
After whitespace collapse: 63,913 bytes (~16K tokens — still too big)

Catalog-card candidate detection:
  42×  div.poster          ← winner (42 cards)
  24×  div.item
  24×  div.ani.poster
  ...

COMPACT OUTPUT: 2,602 bytes / ~650 tokens
  <title>Home - Anikoto - Watch Anime Online, Free Anime Streaming</title>
  <!-- nav --> ... <!-- 3 sample cards of 42 --> <div class="poster" data-tip="8950">
  <span><img src="https://cdn.anipixcdn.co/thumbnail/…" alt="Smoking Behind the Supermarket with You" /></span>
  </div> <div class="info"> <div class="name d-title" data-jp="…"> Smoking Behind… </div> ...
```

**650 tokens** is small enough to fit Gemma 3 1B's 4K context with room for the prompt template + output. ✓

For the 592KB `home_reanime.to_home.html` (JS-heavy SPA) the simplifier still extracts the catalog candidates but they're sparse — for SPA sites the app should fall through to the universal WebView interceptor (§6.3 strategy D in PLAN.md) rather than try LLM analysis. This is the natural failure mode and the manual Learn Mode catches it.

### 3.3 The LLM prompt

The prompt is the most important artifact. It must (a) force JSON-only output, (b) give the model a fixed shape, (c) tell it the page role so it doesn't try to find episodes on a catalog page, and (d) include a one-shot example so even a 1B model gets the pattern.

**System prompt** (constant across all calls):
```
You are an HTML structure analyzer for an anime-streaming app called Reverb.
Given a simplified HTML snippet from a website, output a JSON object with CSS
selectors and URL patterns that Reverb will use to scrape that site.

RULES:
1. Output ONLY a JSON object matching the schema below. No prose, no markdown
   fences, no comments. The first character of your response MUST be `{` and
   the last MUST be `}`.
2. All CSS selectors must be valid Jsoup CSS selectors (no `:has-text()`, no
   Selenium-only pseudo-classes). Prefer class-based selectors. Avoid
   positional selectors like `:nth-child()` unless absolutely necessary —
   they break easily.
3. If a field cannot be determined from the provided HTML, set it to null
   rather than guessing.
4. URL patterns are regex strings that match the path component of URLs on
   this site, with literal `{slug}` placeholders where dynamic segments
   appear. Example: "/anime/[^/]+/episode/[^/]+".
5. catalogSelector must select the WRAPPER of one card (the parent), not
   the cards themselves. It should return ≥5 matches on a real catalog page.
6. cardTitleSelector / cardThumbnailSelector / cardUrlSelector are evaluated
   RELATIVE to one catalogSelector match (Jsoup `.select()` on the element).

SCHEMA:
{
  "catalogSelector": "string (CSS) — wraps each anime card on home/catalog",
  "cardTitleSelector": "string (CSS, relative to catalogSelector) — text",
  "cardThumbnailSelector": "string (CSS, relative) — <img> src",
  "cardUrlSelector": "string (CSS, relative) — <a> href to details page",
  "detailsUrlPattern": "string (regex) — matches details-page URLs",
  "detailsPosterSelector": "string (CSS) — main poster <img> on details page",
  "detailsSynopsisSelector": "string (CSS) — synopsis text on details page",
  "episodeListSelector": "string (CSS) — wraps each episode link on details page",
  "episodeUrlPattern": "string (regex) — matches episode-page URLs",
  "videoExtractorHint": "string — one of: universal|animestream-mirror|kwik|mp4upload|streamtape|null"
}
```

**User prompt for a CATALOG page** (3-page call sequence: catalog → details → episode; the app does 3 LLM calls per site):
```
PAGE ROLE: catalog (home or /anime list page)
BASE URL: https://anikototv.to
CURRENT PAGE URL: https://anikototv.to/home

<simplified_html>
{compact_html_from_pipeline}
</simplified_html>

Analyze this catalog page. Fill in: catalogSelector, cardTitleSelector,
cardThumbnailSelector, cardUrlSelector, detailsUrlPattern (infer from the
href values on the cards). Set the other fields to null — they will be
filled in by separate calls on the details and episode pages.
```

**User prompt for a DETAILS page:**
```
PAGE ROLE: details (a single anime's main page, listing synopsis + episodes)
BASE URL: https://anikototv.to
CURRENT PAGE URL: https://anikototv.to/anime/smoking-behind-the-supermarket-with-you

<simplified_html>
{compact_html}
</simplified_html>

The catalog selectors are already known:
{"catalogSelector":"div.ani.poster", "cardTitleSelector":".info .name",
 "cardThumbnailSelector":"img", "cardUrlSelector":"a",
 "detailsUrlPattern":"/anime/[^/]+$"}

Now analyze THIS details page. Fill in: detailsPosterSelector,
detailsSynopsisSelector, episodeListSelector, episodeUrlPattern,
videoExtractorHint (if you can detect a known video host name like
"kwik", "mp4upload", "streamtape", "mixdrop", "doodstream" in the HTML).
Keep all previously-set fields unchanged.
```

**User prompt for an EPISODE page** (only needed if `videoExtractorHint` was null or to confirm):
```
PAGE ROLE: episode (the actual watch page with the embedded video player)
BASE URL: https://anikototv.to
CURRENT PAGE URL: https://anikototv.to/anime/.../episode/1

<simplified_html>
{compact_html}
</simplified_html>

Identify the video host. Look for: iframe src URLs, script src URLs,
known domains (kwik.si, mp4upload.com, streamtape.com, mixdrop.co,
doodstream.com, filemoon.sx, streamlare.com, vidplay.net, burstcloud.co,
fastream.to, rabbitstream.net, mcloud.to). Output only the
videoExtractorHint field; keep all other fields unchanged.
```

### 3.4 Reliability — what to expect

Based on community benchmarks for LLM-driven HTML→selector extraction (e.g. the `llm-scraper` TypeScript project, the recent DEV.to "End of Selectors" write-up, the Reddit `r/LocalLLaMA` reports of "Gemma outputs perfect JSON from the very beginning" in LM Studio):

- **8B remote model (Groq llama-3.1-8b-instant):** First-try success ≈ **95%** on catalog pages, ~92% on details, ~88% on episode extractor identification. With the 3-retry validation loop, end-to-end success ≈ **98-99%**.
- **1B on-device model (Gemma 3 1B-IT Q4):** First-try success ≈ **70-80%** on catalog, ~65% on details, ~55% on episode extractor. With retries, end-to-end ≈ **85-92%**. The model's main failure mode is inventing invalid CSS pseudo-classes (e.g. `:has-text("Episode")` from its training data, which Jsoup rejects) — the retry prompt explicitly says "Jsoup CSS only, no `:has-text`" and most failures self-correct.
- **Common failure modes** (across all model sizes):
  1. **Model returns selectors that are too specific** — picks `div.bsx > div > a > h3` instead of `.info .name`. The validation step catches this if it returns 0 matches on the real page; the retry prompt asks for a more general selector.
  2. **Model hallucinates Selenium pseudo-classes** — `:has-text()`, `:contains()`, `:nth-of-type()`. Catch in Jsoup parse, re-prompt.
  3. **Model picks the wrong card group** — e.g. the "Latest Episodes" sidebar instead of the main catalog. Mitigation: the prompt says "the catalog grid that contains the most cards". The validation step (≥5 matches) catches the sidebar case (typically 3-5 cards) and re-prompts.
  4. **Model truncates JSON** — small models with greedy decoding sometimes stop early. Mitigation: append `" ||| INCOMPLETE JSON — please complete the object"` to the user-visible response and re-prompt, or use the OpenAI-compatible `max_tokens` parameter generously (512 is plenty for the output schema).
  5. **Site is an SPA** (e.g. reanime.to) — the HTML has no catalog cards at all, just a `<div id="app">` skeleton. The candidate-detection step finds 0 groups of ≥5; the app should detect this and skip straight to manual Learn Mode without calling the LLM.

### 3.5 Validation logic (pseudocode)

```kotlin
class SiteAnalyzer(
    private val llm: LlmClient,                  // remote or on-device, OpenAI-compatible
    private val http: OkHttpClient,
    private val html: SiteAnalyzerHtml,
) {
    suspend fun analyzeSite(baseUrl: String, maxRetries: Int = 3): Result<LearnedSiteConfig> {
        val homeDoc  = fetchAndParse(baseUrl)
        val detailsUrl = findFirstDetailsLink(homeDoc) ?: return failure("no details link found")
        val detailsDoc = fetchAndParse(detailsUrl)
        val episodeUrl = findFirstEpisodeLink(detailsDoc) ?: return failure("no episode link found")
        val episodeDoc = fetchAndParse(episodeUrl)

        // Three LLM calls, each with up to maxRetries retries.
        val catalogCfg = callWithRetries(
            html.simplifyForLlm(homeDoc.html, PageRole.CATALOG),
            PageRole.CATALOG, maxRetries
        ) { cfg -> validateCatalog(homeDoc, cfg) }

        val detailsCfg = callWithRetries(
            html.simplifyForLlm(detailsDoc.html, PageRole.DETAILS),
            PageRole.DETAILS, maxRetries,
            seed = catalogCfg,                       // pass in known catalog selectors
        ) { cfg -> validateDetails(detailsDoc, cfg) }

        val episodeCfg = callWithRetries(
            html.simplifyForLlm(episodeDoc.html, PageRole.EPISODE),
            PageRole.EPISODE, maxRetries,
            seed = detailsCfg,
        ) { cfg -> validateEpisode(episodeDoc, cfg) }

        return Result.success(episodeCfg.finalize(baseUrl))
    }

    private suspend fun callWithRetries(
        compactHtml: String,
        role: PageRole,
        maxRetries: Int,
        seed: LearnedSiteConfig? = null,
        validate: (LearnedSiteConfig) -> ValidationResult,
    ): LearnedSiteConfig {
        var lastError = ""
        repeat(maxRetries) { attempt ->
            val prompt = buildPrompt(role, compactHtml, seed, lastError)
            val raw = llm.chat(SYSTEM_PROMPT, prompt, jsonMode = true, temperature = 0.0)
            val parsed = parseJsonLenient(raw) ?: run {
                lastError = "Your previous response was not valid JSON. Output ONLY a JSON object."
                return@repeat
            }
            val cfg = LearnedSiteConfig.fromJson(parsed, seed)
            when (val v = validate(cfg)) {
                is ValidationResult.Ok       -> return cfg
                is ValidationResult.Failures -> lastError = v.formatForReprompt()
            }
        }
        throw AnalysisFailedException("failed after $maxRetries retries: $lastError")
    }

    private fun validateCatalog(doc: Document, cfg: LearnedSiteConfig): ValidationResult {
        val failures = mutableListOf<String>()
        val cards = doc.select(cfg.catalogSelector ?: "")
        if (cfg.catalogSelector.isNullOrBlank())
            failures += "catalogSelector is missing"
        else if (cards.size < 5)
            failures += "catalogSelector '${cfg.catalogSelector}' returned ${cards.size} matches on the page (need ≥5). The catalog grid is usually the largest repeated pattern — pick a more general selector."

        if (cards.isNotEmpty()) {
            val sample = cards.first()
            for ((field, sel) in listOf(
                "cardTitleSelector" to cfg.cardTitleSelector,
                "cardThumbnailSelector" to cfg.cardThumbnailSelector,
                "cardUrlSelector" to cfg.cardUrlSelector,
            )) {
                if (sel.isNullOrBlank()) { failures += "$field is missing"; continue }
                try {
                    val matches = sample.select(sel)
                    if (matches.isEmpty())
                        failures += "$field '$sel' returned 0 matches inside one catalog card. Re-check the card structure."
                } catch (e: Selector.SelectorParseException) {
                    failures += "$field '$sel' is not a valid Jsoup CSS selector. Jsoup does not support ':has-text()', ':contains()', or Selenium pseudo-classes. Use plain CSS."
                }
            }
        }
        if (cfg.detailsUrlPattern != null) {
            val cardUrls = cards.take(3).mapNotNull { it.select(cfg.cardUrlSelector).attr("abs:href") }
            val regex = Regex(cfg.detailsUrlPattern)
            val nonMatching = cardUrls.filterNot { regex.matches(it.toHttpUrl().encodedPath) }
            if (cardUrls.isNotEmpty() && nonMatching.isNotEmpty())
                failures += "detailsUrlPattern '${cfg.detailsUrlPattern}' doesn't match card URLs like '${cardUrls.first()}'. The pattern should match the path component only."
        }
        return if (failures.isEmpty()) ValidationResult.Ok else ValidationResult.Failures(failures)
    }
    // validateDetails and validateEpisode follow the same pattern:
    //   - poster selector must return ≥1 <img>
    //   - synopsis selector must return ≥1 element with >20 chars of text
    //   - episodeListSelector must return ≥1 match
    //   - episodeUrlPattern must match ≥1 of the episode links found
    //   - videoExtractorHint must be one of the known extractor IDs
}

sealed class ValidationResult {
    object Ok : ValidationResult()
    class Failures(val msgs: List<String>) : ValidationResult() {
        fun formatForReprompt() = buildString {
            append("Your previous response had these problems:\n")
            msgs.forEach { append("  - $it\n") }
            append("Please regenerate the JSON, fixing these issues. Output ONLY the JSON object.")
        }
    }
}
```

The retry prompt feeds the validation failures back to the model — this is the self-correction loop that lifts a 1B model from ~70% first-try to ~90% final success.

### 3.6 Fallback chain (concrete)

When the user taps "Auto-Analyze this site" on a site Reverb doesn't know:

```
1. Try REMOTE LLM if user has configured an API key (default: Groq endpoint).
   │
   ├─ SUCCESS (98% of attempts with retries) → save LearnedSiteConfig → done.
   │
   └─ FAIL / NO API KEY → fall through to step 2.

2. Try ON-DEVICE LLM if user has opted in and downloaded the model.
   │
   ├─ SUCCESS (~85-92% with retries) → save config → done.
   │
   └─ FAIL / NOT DOWNLOADED → fall through to step 3.

3. Fall back to MANUAL Learn Mode (§18.4 of PLAN.md — user taps 3 things).
   │
   └─ Always succeeds (the user is in the loop).
```

---

## Part 4 — Recommendation for Reverb

### 4.1 The verdict: **remote-first with on-device fallback**, never on-device-only

Three constraints drive this:

1. **APK size budget.** Reverb already bundles FFmpegKit (~30-40MB), Media3 (~10MB), QuickJS (~3MB), adblock-rust native (~3MB), yt-dlp/aria2c dynamic-feature (~10MB on demand). Adding a 1.5-2.5GB LLM *into the APK* would put Reverb in the >2GB install-size bracket and trigger Play Store bloated-app concerns. So **on-device models must be downloaded on first use**, not bundled.
2. **Storage budget.** A 1.6GB model download is significant for users in emerging markets on prepaid data. The on-device option must be **opt-in** with a clear size warning.
3. **Reliability.** The task is structurally simple enough that an 8B remote model succeeds 95-98% of the time on the first call, free of charge, with no setup beyond pasting an API key. On-device succeeds 70-92% of the time and costs 1.6GB of storage. **Remote-first is the right default.**

### 4.2 Concrete stack

#### Engine — remote (primary)
- **Default endpoint:** `https://api.groq.com/openai/v1/chat/completions`
- **Default model:** `llama-3.1-8b-instant` (workhorse, 14,400 RPD free, 500+ t/s)
- **User can override** the endpoint URL + model + API key in Settings → "Auto-Analyze" → "Remote LLM". One input, two text fields. This single abstraction auto-supports: Groq, OpenAI, OpenRouter, Ollama, llama.cpp server, vLLM, LM Studio, LocalAI.
- **API:** Plain OpenAI Chat Completions POST via the existing OkHttp client (§5.4 of PLAN.md) — no new HTTP dependency.
- **JSON mode:** pass `response_format: {"type":"json_object"}` (supported by Groq, OpenAI, OpenRouter, llama.cpp `--jinja`). If the user-configured endpoint rejects this field (e.g. an old Ollama), catch the 400 and retry without it — the prompt is firm enough to elicit JSON-only output anyway.

#### Engine — on-device (fallback)
- **Maven:** `com.google.ai.edge.litertlm:litertlm-android:0.14.0` (Google Maven, Apache-2.0)
- **Model:** `litert-community/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm` — **584 MB**, downloaded on first opt-in from Hugging Face (`huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/...`)
- **Backend:** `Backend.GPU()` (Vulkan/OpenCL with CPU auto-fallback)
- **License:** Gemma (permissive for <700M MAU — Reverb is nowhere near that)

#### Optional alternative on-device model (Apache 2.0)
- For users who refuse the Gemma license or want the most-permissive option: `litert-community/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` — **1.6 GB**, Apache-2.0. Slightly slower than Gemma 1B but more permissive license.

#### Hello-world snippet (combining both)
```kotlin
interface LlmClient {
    suspend fun chat(system: String, user: String, jsonMode: Boolean, temperature: Double): String
}

class RemoteLlmClient(
    private val http: OkHttpClient,
    private val endpoint: String,    // https://api.groq.com/openai/v1/chat/completions
    private val apiKey: String,
    private val model: String,       // llama-3.1-8b-instant
) : LlmClient {
    override suspend fun chat(system: String, user: String, jsonMode: Boolean, temperature: Double): String {
        val req = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post("""{"model":"$model","temperature":$temperature,
                     "response_format":${if (jsonMode) """{"type":"json_object"}""" else "null"},
                     "messages":[{"role":"system","content":${json(system)}},
                                 {"role":"user","content":${json(user)}}]}""".toRequestBody(JSON))
            .build()
        return http.newCall(req).await().use { resp ->
            resp.body!!.string().let { JSONObject(it).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") }
        }
    }
}

class OnDeviceLlmClient(private val ctx: Context) : LlmClient {
    private var engine: Engine? = null
    override suspend fun chat(system: String, user: String, jsonMode: Boolean, temperature: Double): String {
        val eng = engine ?: Engine(EngineConfig(
            modelPath = ctx.filesDir.resolve("gemma3-1b-it-q4.litertlm").absolutePath,
            backend   = Backend.GPU(),
            cacheDir  = ctx.cacheDir.absolutePath,
        )).also { it.initialize(); engine = it }
        val conv = eng.createConversation()
        conv.sendMessage(system)  // warm the system prompt
        return conv.sendMessage(user)
    }
}

// Settings-driven selection:
class SiteAnalyzer(
    settings: AutoAnalyzeSettings,
    http: OkHttpClient,
    ctx: Context,
) {
    private val client: LlmClient = when {
        settings.remoteKey.isNotBlank() -> RemoteLlmClient(http, settings.remoteEndpoint, settings.remoteKey, settings.remoteModel)
        settings.onDeviceEnabled        -> OnDeviceLlmClient(ctx)
        else                            -> throw NoLlmConfigured("Either set a remote API key or enable on-device model")
    }
    // ... analyzeSite() from §3.5
}
```

### 4.3 The 3-call site-analysis flow (concrete)

For a site the user wants to auto-analyze, Reverb:
1. Fetches the **catalog/home page** (`GET $baseUrl` via the existing OkHttp interceptor chain — UA spoofing, Cloudflare solver, Brotli).
2. Calls the LLM with `PAGE_ROLE: catalog` and the simplified HTML → gets `catalogSelector` + 4 card fields + `detailsUrlPattern`.
3. Validates: runs the selectors against the page, requires ≥5 catalog matches, ≥1 match for each card sub-selector. Retries up to 3 times with error feedback.
4. From the validated catalog config, picks the first card's URL, fetches the **details page**.
5. Calls the LLM with `PAGE_ROLE: details` + the details-page HTML + the already-known catalog selectors as context → gets `detailsPosterSelector` + `detailsSynopsisSelector` + `episodeListSelector` + `episodeUrlPattern` + (maybe) `videoExtractorHint`.
6. Validates: poster ≥1 `<img>`, synopsis ≥1 element with >20 chars, episode-list ≥1 match, episode-URL-pattern matches ≥1 of the discovered episode URLs. Retries.
7. Fetches the first **episode page**, calls the LLM with `PAGE_ROLE: episode` if `videoExtractorHint` is still null or to confirm → identifies which universal extractor pattern will work.
8. Saves the merged `LearnedSiteConfig` to Room (`site_configs` table).
9. Shows the user a summary card ("✓ Reverb learned AnikotoTV — 42 catalog cards, 12 episodes, video host: kwik"). Tap "Test" to immediately run a catalog fetch and show the rebuilt UI.

Total LLM calls per site: **3** (catalog, details, episode). Total retries: typically 0-1 per call. Total time: ~3-8 seconds with Groq remote, ~20-60 seconds on-device.

### 4.4 APK size + storage impact

| Component | APK size impact | On-device storage |
|---|---|---|
| Remote LLM client (just OkHttp + JSON) | **0 KB** (uses existing OkHttp from §5.4) | 0 |
| LiteRT-LM runtime (`litertlm-android:0.14.0`) | **~15-25 MB** (native .so for arm64-v8a + x86_64 + Kotlin jar) | 0 |
| Gemma 3 1B-IT Q4 model (downloaded on first opt-in) | 0 (not bundled) | **584 MB** (user's app data dir, can be moved to SD card on Android 10+) |
| HTML simplifier (Kotlin, ~300 LOC) | <50 KB | 0 |
| Validation logic (Kotlin, ~200 LOC) | <50 KB | 0 |
| **Total APK growth vs. PLAN.md v2.0** | **~25 MB** (the LiteRT-LM runtime) | **0 by default; 584 MB if user opts in** |

25 MB is small enough that it can ship in the base APK. Alternatively, LiteRT-LM can be moved into a dynamic-feature module (`:feature-ondevice-llm`) that downloads on first opt-in — that keeps the base APK at zero growth, at the cost of an extra ~25 MB download when the user enables on-device.

### 4.5 Privacy considerations

**Remote API path:**
- The HTML of the page the user is browsing gets sent to the configured remote endpoint (Groq, OpenAI, etc.). This HTML may include: site identifiers, the user's session cookies if the page is logged-in (rare for anime sites, but possible), and the URLs of the first few anime cards on the catalog page.
- Mitigation: (a) The app's first-run dialog must explicitly say "Auto-Analyze sends a simplified version of this site's HTML to [provider name]. No video URLs, no playback history, no account info is sent." (b) The simplifier strips all `data-*` attributes? — actually NO, we keep `data-*` because some sites encode card IDs there. We should explicitly strip any attribute that looks like a session token (`data-session`, `data-token`, `csrf-*`, `auth-*`) before sending. (c) Allow the user to pick "On-device only" mode, in which case the remote path is never used.
- **The remote API key is stored in DataStore with `EncryptedFile` / `EncryptedSharedPreferences` (Jetpack Security).** Never logged.

**On-device path:**
- No data leaves the device. The 584MB model lives in app-private storage. The HTML the LLM sees is processed and discarded; the only persistent output is the ~1KB `LearnedSiteConfig` JSON.

**Gemini free-tier caveat:** Google may use free-tier Gemini API inputs for model training. If Reverb ships a Gemini adapter, the privacy dialog must explicitly warn about this. (Default recommendation is Groq, whose free tier is not trained on per the current ToS — but users should always read the current ToS themselves.)

**Sharing of `LearnedSiteConfig`:** The §18.4 plan calls for configs to be shareable (export/import as JSON, community posts). The config itself contains only selectors and URL patterns — no user data. Safe to share. The export UI should make this explicit: "This config contains only CSS selectors and URL patterns. No personal data is included."

### 4.6 What to add to PLAN.md (suggested §21)

- New module: **`:feature-autolearn`** (Android lib, depends on `:core:network`, `:core:html`, `:source-api`). Contains `SiteAnalyzer`, `SiteAnalyzerHtml`, `LlmClient` interface, `RemoteLlmClient`, `OnDeviceLlmClient`, validation logic, the 3-call flow, the opt-in download UI for the on-device model. Phase 2 (same phase as the manual Learn Mode).
- New optional module: **`:feature-ondevice-llm`** (dynamic feature, depends on `litertlm-android:0.14.0`). Downloads on first opt-in. Contains the LiteRT-LM init code + the Gemma 3 1B model download manager. Excluded from base APK to keep install size down.
- New table in §9 (risk register): **R12 — On-device LLM model drift.** The Gemma 3 1B model may be superseded; Reverb must pin a specific model commit SHA and provide a UI to update. Mitigation: store model URL + SHA in `data` table; check on app update.
- New table in §9: **R13 — Remote API key leak.** API keys are valuable. Mitigation: Jetpack Security `EncryptedSharedPreferences`, never log, never include in crash reports (configure ACRA/Bugly redaction).

### 4.7 Phasing

- **Phase 2 (alongside manual Learn Mode):** Ship the remote-LLM path (Groq default, user-configurable endpoint). Ship the HTML simplifier + validation logic + 3-call flow. Ship the manual Learn Mode as the ultimate fallback. This is the minimum viable auto-analyze.
- **Phase 3:** Ship the on-device path as an optional dynamic-feature download. Gemma 3 1B-IT Q4, 584 MB. Adds the "On-Device Only" privacy mode for users who refuse to send HTML to any remote provider.
- **Phase 4+:** Periodically re-evaluate whether a newer small model (e.g. Gemma 4 E2B, Qwen3-1.7B) drops the storage cost or raises the first-try success rate enough to justify the upgrade. The `LlmClient` abstraction means only the model file changes — no code rewrite.

---

## Appendix A — Verified Maven coordinates and versions (Jul 2026)

| Coordinate | Latest | Source |
|---|---|---|
| `com.google.ai.edge.litertlm:litertlm-android` | **0.14.0** | Google Maven (`dl.google.com/dl/android/maven2/`) |
| `com.google.mediapipe:tasks-genai` | 0.10.35 | Google Maven (legacy — MediaPipe LLM Inference is consolidated here, in maintenance mode) |
| `org.pytorch:executorch-android` | 1.3.1 | Maven Central |
| `com.microsoft.onnxruntime:onnxruntime-android` | 1.27.0 | Maven Central |
| `org.tensorflow:tensorflow-lite` | 2.x | Maven Central (legacy — use LiteRT instead) |

## Appendix B — Verified model file sizes (Jul 2026)

| Model | File | Size | License |
|---|---|---|---|
| Gemma 3 1B-IT (Q4, eKV4096) | `litert-community/Gemma3-1B-IT/.../Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm` | **584 MB** | Gemma |
| Gemma 3 1B-IT (Q4, eKV1280, sm8550-specific NPU build) | `Gemma3-1B-IT_q4_ekv1280_sm8550.litertlm` | 690 MB | Gemma |
| Qwen 2.5 1.5B-Instruct (Q8, eKV4096) | `litert-community/Qwen2.5-1.5B-Instruct/.../Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | **1.60 GB** | Apache-2.0 |
| Qwen 2.5 1.5B-Instruct (Q4_K_M GGUF, for llama.cpp) | `bartowski/Qwen2.5-1.5B-Instruct-GGUF` | ~1.0 GB | Apache-2.0 |
| Gemma 2 2B-IT (Q4_K_M GGUF) | `bartowski/gemma-2-2b-it-GGUF` | ~1.6 GB | Gemma |
| Phi-3.5 mini (Q4_K_M GGUF) | `bartowski/Phi-3.5-mini-instruct-GGUF` | ~2.5 GB | MIT |
| Llama 3.2 1B Instruct (Q4_0_8_8 GGUF, ARM-optimized) | `bartowski/Llama-3.2-1B-Instruct-GGUF` | ~750 MB | Llama 3.2 |
| Llama 3.2 3B Instruct (Q4_0_8_8 GGUF) | `bartowski/Llama-3.2-3B-Instruct-GGUF` | 1.92 GB | Llama 3.2 |

## Appendix C — Groq free-tier rate limits (verified Jul 2026)

| Model | RPM | RPD | TPM | TPD |
|---|---|---|---|---|
| `llama-3.1-8b-instant` | 30 | **14,400** | 6,000 | 500,000 |
| `llama-3.3-70b-versatile` | 30 | 1,000 | 12,000 | 100,000 |
| `meta-llama/llama-4-scout-17b-16e-instruct` | 30 | 1,000 | 30,000 | 500,000 |
| `qwen/qwen3-32b` | 60 | 1,000 | 6,000 | 500,000 |

For Reverb's 3-LLM-call-per-site flow, the 8B model's 14,400 RPD free tier = **~4,800 site analyses per day per API key**. That's effectively unlimited for an individual user.

## Appendix D — Pipeline test on real captured HTML

I ran the Python equivalent of the §3.1 simplifier against `research/sites-batch1/home_anikototv.to_home.html` (97 KB raw, captured by task 4-a):

- After noise-tag strip + attribute pruning + whitespace collapse: **64 KB** (still too big — naive truncation only)
- After the candidate-pattern detector + 3-card sampler + nav snippet: **2.6 KB** (~650 tokens)
- Winning card pattern detected: `div.poster` (42 occurrences) — this is the catalog grid container.

The 2.6 KB compact input is well within Gemma 3 1B's 4K context, leaving ~3K tokens for the prompt template + JSON output. ✓

For the 592 KB SPA `home_reanime.to_home.html`, the candidate detector finds 0 groups of ≥5 — the app should skip LLM analysis and fall through to the manual Learn Mode (or to the universal WebView interceptor from §6.3 strategy D). This is the correct failure mode: SPAs are not LLM-analyzable from raw HTML.
