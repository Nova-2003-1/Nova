# On-device LLM (llama.cpp) — Design & build guide

This document describes the **on-device local LLM** feature added to this Dicio
fork. It lets a small quantized model (e.g. **TinyDolphin** or **Qwen2.5-0.5B**)
run **fully offline on the phone** and act as an **orchestrator**: every user
request is sent to the model, which either answers directly or calls one of the
existing Dicio skills through a lightweight tool-calling protocol.

> ⚠️ **Verification boundary.** The Kotlin/DI/settings layer and the **native
> llama.cpp build both compile** — the APK ships a real `libdicio_llm.so` with
> llama.cpp linked in statically. What is still **unverified is the runtime**:
> loading a GGUF and generating tokens has not been exercised on a real device.

## Architecture overview

```
 user speech / text
        │
        ▼
 SkillEvaluator ──(LLM orchestrator enabled?)──► LlmOrchestrator
        │  no                                          │
        ▼                                              ▼
 normal skill ranker                        LlamaCppEngine (JNI)
                                                       │
                                    ┌──────────────────┴───────────────────┐
                                    │ model answers directly                │
                                    │  OR emits {"tool":"…","arguments":{…}} │
                                    └──────────────────┬───────────────────┘
                                                       ▼
                                               ToolRegistry → LlmTool.execute()
                                                       │
                                                       ▼
                                                  SkillOutput (speech + graphical)
```

Key packages (all under `app/src/main/kotlin/org/stypox/dicio/llm/`):

| File | Responsibility |
|------|----------------|
| `LlmMessage.kt` | Chat message / tool-definition / tool-call data model + streaming events |
| `LlmEngine.kt` | Backend-agnostic inference interface (so llama.cpp can later be swapped) |
| `LlamaCppEngine.kt` | JNI wrapper around the native llama.cpp bridge; single-threaded, streaming |
| `LlmModelState.kt` | Download/load state machine (mirrors the Vosk model states) |
| `GgufModelManager.kt` | Downloads the GGUF model (reuses `downloadBinaryFileWithPartial`) and loads it |
| `LlmService.kt` | Foreground service that keeps the model warm and unloads on memory pressure |
| `LlmModule.kt` | Hilt bindings |
| `orchestrator/LlmOrchestrator.kt` | Turns a user turn into messages, streams the model, dispatches tool calls |
| `orchestrator/ToolRegistry.kt` | Holds the available `LlmTool`s and renders the tool list into the prompt |
| `orchestrator/LlmTool.kt` | Interface: a skill exposed to the model as a callable tool |
| `orchestrator/tools/*.kt` | Concrete tool adapters (extend this to expose more skills) |
| `orchestrator/OrchestratorOutput.kt` | `SkillOutput` shown in the interaction log |

The native side lives in `app/src/main/cpp/` (`CMakeLists.txt`, `llama-jni.cpp`).

## Tool-calling protocol

Small models are not reliable at the OpenAI JSON function-calling schema, so we
use a **minimal, forgiving convention** the model is instructed to follow:

* To use a tool, output **only** a single line of JSON:
  `{"tool": "toggle_flashlight", "arguments": {"on": "true"}}`
* Otherwise, answer the user in plain natural language.

`LlmOrchestrator` scans the streamed text for the first balanced `{...}` block; if
it parses as a tool call whose name is in the `ToolRegistry`, the tool is
executed. Its `SkillOutput` (speech + graphical) is what the user gets. Otherwise
the streamed text is spoken/shown directly. See `LlmOrchestrator.kt` for the
exact parsing and the optional "feed the tool result back for a final sentence"
step.

Adding a new tool = implement `LlmTool` (name, description, params, `execute`)
and add it to the list provided in `LlmModule.provideToolRegistry`.

## Model choice

The model is chosen in **Settings → Local AI → "Ollama model or GGUF URL"**, and
the field accepts either an **Ollama reference** or a plain `https://` link to a
`.gguf`. Default is `qwen2.5:0.5b` (`GgufModelManager.defaultModelUrl`).

| Reference | Size on disk | Notes |
|-----------|--------------|-------|
| `qwen2.5:0.5b` | ~380 MB | Default. Multilingual (good German), best tool-following |
| `tinydolphin` | ~610 MB | 1.1B, English-centric, fast |
| `qwen2.5:1.5b` | ~1.0 GB | Best quality of the three; needs more RAM |

### Pulling from the Ollama registry

`OllamaRegistry` reproduces what `ollama pull` does, so no Hugging Face URL
hunting is needed. Ollama's registry is a stock OCI distribution API served
**anonymously — no token exchange**, so a pull is two plain GETs:

1. `GET https://registry.ollama.ai/v2/library/<name>/manifests/<tag>`
2. In the returned `layers`, the entry with media type
   `application/vnd.ollama.image.model` **is the GGUF file itself**, byte for
   byte, fetched from `…/v2/library/<name>/blobs/<digest>`. No unwrapping.

Blob responses honour HTTP range requests, so these downloads are resumable.

A reference is parsed as `[host/][namespace/]name[:tag]`, defaulting to
`registry.ollama.ai`, namespace `library` and tag `latest`. A leading component
is only treated as a host when it looks like one, so `myuser/mymodel` reads as a
namespace rather than a host, while `hf.co/user/repo` and `localhost:11434/…`
work as expected. See `OllamaRegistryTest` for the cases.

The manifest also carries the model's prompt template
(`application/vnd.ollama.image.template`). `ChatFormat` emits a fixed ChatML
prompt, which is what both Qwen2.5 and TinyDolphin expect, so the template is
**not** applied — it is only inspected, and `GgufModelManager` logs a warning if
the chosen model declares something other than ChatML. Supporting arbitrary
templates would mean interpreting Ollama's Go template syntax.

## Building

> **Application identity.** This fork ships under its own package id
> `lol.everyday5631.nova` (set as `applicationId` in `app/build.gradle.kts`), so
> the built APK installs as a standalone app and does not collide with an
> upstream Dicio install. Debug builds additionally append the git-branch suffix
> (`applicationIdSuffix`), e.g. `lol.everyday5631.nova.<branch>`. The internal
> source package (`namespace`) intentionally stays `org.stypox.dicio`.

The native build is **opt-in** and only targets 64-bit ABIs (a 1B model is not
viable on 32-bit `armeabi-v7a`).

1. **Check out llama.cpp** at `app/src/main/cpp/llama.cpp`:
   ```bash
   git submodule add https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
   ```
   No pin is currently needed: every `llama_*` symbol `llama-jni.cpp` calls
   (`llama_model_load_from_file`, `llama_init_from_model`,
   `llama_model_get_vocab`, `llama_memory_clear`, `llama_vocab_is_eog`, the
   sampler chain) exists in current upstream `master`. If a future bump does
   break it, the calls flagged `// API:` in `llama-jni.cpp` are the ones to fix.
2. Install the **NDK** and **CMake** (Android Studio → SDK Tools, or
   `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"`).
3. Build & install on a 64-bit device with ≥4 GB RAM:
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. On first launch, enable **Settings → Local AI**, pick a model, and press
   *Download / load model*. The `LlmService` foreground notification shows load
   state.

### Native build gotchas

Both of these are already handled in the build files; they are recorded because
each fails in a way that is hard to trace back to its cause.

* **16 KB page size.** Android 15+ uses 16 KB memory pages and Play has required
  16 KB-aligned native libraries since Nov 2025. **NDK r27 still links at 4 KB by
  default** (r28+ changed this), and a 4 KB-aligned `.so` fails to load on
  16 KB-page hardware such as the Pixel 9 or Galaxy S25 — while working fine in a
  typical emulator. `CMakeLists.txt` therefore sets
  `-Wl,-z,max-page-size=16384` before `add_subdirectory`, so ggml and llama
  inherit it. Verify with
  `llvm-readelf -lW libdicio_llm.so` — every `LOAD` segment must show `0x4000`.
* **`POSIX_MADV_RANDOM` undeclared.** llama.cpp's mmap path calls
  `posix_madvise()`, which bionic only exposes from API 23, while the app's
  `minSdk` is 21. `app/build.gradle.kts` builds the native library alone against
  `-DANDROID_PLATFORM=android-23`; the LLM is 64-bit-only and needs a modern
  device regardless.

`CMakeLists.txt` also sets `BUILD_SHARED_LIBS=OFF` so llama and ggml link
statically into a single `libdicio_llm.so` (~10 MB per ABI) rather than shipping
a set of interdependent shared objects. It does **not** link llama.cpp's `common`
target: that is only built when `LLAMA_BUILD_COMMON` is on, which it is not under
`add_subdirectory`, and `llama-jni.cpp` includes only `<llama.h>`.
