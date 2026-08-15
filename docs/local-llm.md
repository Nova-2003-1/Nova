# On-device LLM (llama.cpp) — Design & build guide

This document describes the **on-device local LLM** feature added to this Dicio
fork. It lets a small quantized model (e.g. **TinyDolphin** or **Qwen2.5-0.5B**)
run **fully offline on the phone** and act as an **orchestrator**: every user
request is sent to the model, which either answers directly or calls one of the
existing Dicio skills through a lightweight tool-calling protocol.

> ⚠️ **Verification boundary.** The Kotlin/DI/settings layer is written to match
> the rest of the project. The **native llama.cpp build (NDK/CMake) and the model
> runtime cannot be compiled or run in CI/sandbox** — they require the Android NDK
> and a real device. The steps to finish that are in [Building](#building) below.

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

| Model | Size (Q4_K_M) | Notes |
|-------|---------------|-------|
| `tinydolphin` (1.1B) | ~0.7 GB | English-centric; fast; the one you asked about |
| `qwen2.5-0.5b-instruct` | ~0.4 GB | Multilingual (good German), better tool-following |
| `qwen2.5-1.5b-instruct` | ~1.0 GB | Best quality of the three; needs more RAM |

Because the engine is behind `LlmEngine`, the model is swappable at runtime via
settings (`SkillSettingsLlm.model_url` + `chat_template`). Default is a GGUF URL
configured in `GgufModelManager`.

## Building

> **Application identity.** This fork ships under its own package id
> `lol.everyday5631.nova` (set as `applicationId` in `app/build.gradle.kts`), so
> the built APK installs as a standalone app and does not collide with an
> upstream Dicio install. Debug builds additionally append the git-branch suffix
> (`applicationIdSuffix`), e.g. `lol.everyday5631.nova.<branch>`. The internal
> source package (`namespace`) intentionally stays `org.stypox.dicio`.

The native build is **opt-in** and only targets 64-bit ABIs (a 1B model is not
viable on 32-bit `armeabi-v7a`).

1. **Add llama.cpp as a submodule** (pinned — the C API drifts between releases;
   `llama-jni.cpp` targets the API around the pinned commit):
   ```bash
   git submodule add https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
   cd app/src/main/cpp/llama.cpp && git checkout <pinned-commit> && cd -
   ```
2. Ensure the **NDK** is installed (Android Studio → SDK Tools → NDK + CMake).
3. The `externalNativeBuild` block in `app/build.gradle.kts` wires
   `app/src/main/cpp/CMakeLists.txt`. It restricts the native ABIs to
   `arm64-v8a` (+ `x86_64` for the emulator) via `ndk { abiFilters }` in the
   `llm` product flavour / build config comment.
4. Build & install on a device with ≥4 GB RAM:
   ```bash
   ./gradlew :app:assembleDebug
   ```
5. On first launch, enable **Settings → Local AI**, which downloads the GGUF and
   loads it. The `LlmService` foreground notification shows load state.

If the pinned llama.cpp commit is newer/older than what `llama-jni.cpp` expects,
adjust the handful of `llama_*` calls flagged with `// API:` comments in
`llama-jni.cpp`.
