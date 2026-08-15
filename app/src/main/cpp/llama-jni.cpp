// JNI bridge between LlamaCppEngine.kt and llama.cpp.
//
// This targets the llama.cpp C API as of the pinned submodule commit (see
// docs/local-llm.md). The API drifts between releases: calls that have been
// renamed across versions are flagged with `// API:` so they are easy to find
// and adapt if you bump the submodule.
//
// Design: one `LlamaContext` per loaded model, holding the model, the inference
// context, the vocab and a reusable sampler chain. Generation is streamed one
// token per `nativeNextToken` call. The Kotlin side guarantees all native calls
// for a given handle happen on a single dedicated thread (llama.cpp contexts are
// not thread-safe), so no locking is needed here.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <cstring>

#include "llama.h"

#define LOG_TAG "DicioLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_sampler *sampler = nullptr;

    // streaming state for the current completion
    bool generating = false;
    int n_predict = 256;   // max tokens to generate this completion
    int n_generated = 0;   // tokens generated so far this completion
    int n_ctx = 2048;      // context window
    int n_past = 0;        // tokens currently in the KV cache
};

// Build a fresh sampler chain. Kept simple and deterministic-ish: top-k, top-p,
// temperature, then a distribution sampler. Tune here if you want different
// creativity.
llama_sampler *make_sampler() {
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *chain = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(0.7f));
    // fixed seed keeps behaviour reproducible; use LLAMA_DEFAULT_SEED for random
    llama_sampler_chain_add(chain, llama_sampler_init_dist(1234));
    return chain;
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return result;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_org_stypox_dicio_llm_LlamaCppEngine_nativeBackendInit(JNIEnv *, jobject) {
    llama_backend_init();
    // Route llama.cpp logs to logcat but drop the noisy debug spam.
    llama_log_set([](ggml_log_level level, const char *text, void *) {
        if (level >= GGML_LOG_LEVEL_WARN) {
            __android_log_write(ANDROID_LOG_INFO, "llama.cpp", text);
        }
    }, nullptr);
    LOGI("llama backend initialized");
}

JNIEXPORT void JNICALL
Java_org_stypox_dicio_llm_LlamaCppEngine_nativeBackendFree(JNIEnv *, jobject) {
    llama_backend_free();
}

// Returns a handle (pointer as jlong), or 0 on failure.
JNIEXPORT jlong JNICALL
Java_org_stypox_dicio_llm_LlamaCppEngine_nativeLoadModel(
        JNIEnv *env, jobject, jstring path, jint n_ctx, jint n_threads, jint n_gpu_layers) {
    const std::string model_path = jstring_to_std(env, path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers; // 0 == CPU only (typical on Android)

    // API: `llama_model_load_from_file` (was `llama_load_model_from_file`)
    llama_model *model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("failed to load model from %s", model_path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) n_ctx;
    cparams.n_batch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;

    // API: `llama_init_from_model` (was `llama_new_context_with_model`)
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("failed to create llama context");
        llama_model_free(model);
        return 0;
    }

    auto *lc = new LlamaContext();
    lc->model = model;
    lc->ctx = ctx;
    lc->vocab = llama_model_get_vocab(model); // API: was `llama_get_vocab`/`llama_model_vocab`
    lc->sampler = make_sampler();
    lc->n_ctx = (int) llama_n_ctx(ctx);

    LOGI("model loaded, handle=%p n_ctx=%d", (void *) lc, lc->n_ctx);
    return reinterpret_cast<jlong>(lc);
}

JNIEXPORT void JNICALL
Java_org_stypox_dicio_llm_LlamaCppEngine_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    auto *lc = reinterpret_cast<LlamaContext *>(handle);
    if (lc == nullptr) return;
    if (lc->sampler) llama_sampler_free(lc->sampler);
    if (lc->ctx) llama_free(lc->ctx);
    if (lc->model) llama_model_free(lc->model);
    delete lc;
    LOGI("model freed, handle=%p", (void *) lc);
}

// Tokenizes `prompt`, clears the KV cache, decodes the prompt and readies the
// context for token-by-token sampling. Returns true on success.
JNIEXPORT jboolean JNICALL
Java_org_stypox_dicio_llm_LlamaCppEngine_nativeStartCompletion(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jint n_predict) {
    auto *lc = reinterpret_cast<LlamaContext *>(handle);
    if (lc == nullptr) return JNI_FALSE;

    const std::string text = jstring_to_std(env, prompt);

    // reset any previous conversation state
    llama_memory_clear(llama_get_memory(lc->ctx), true); // API: was `llama_kv_cache_clear(ctx)`
    llama_sampler_reset(lc->sampler);
    lc->generating = false;
    lc->n_generated = 0;
    lc->n_past = 0;
    lc->n_predict = n_predict > 0 ? n_predict : 256;

    // tokenize (add BOS / parse special tokens so chat templates work)
    const int n_prompt = -llama_tokenize(
            lc->vocab, text.c_str(), (int32_t) text.size(),
            nullptr, 0, /*add_special*/ true, /*parse_special*/ true);
    if (n_prompt <= 0) {
        LOGE("tokenization returned no tokens");
        return JNI_FALSE;
    }
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(lc->vocab, text.c_str(), (int32_t) text.size(),
                       tokens.data(), (int32_t) tokens.size(), true, true) < 0) {
        LOGE("tokenization failed");
        return JNI_FALSE;
    }

    if (n_prompt >= lc->n_ctx) {
        LOGE("prompt (%d tokens) does not fit in context (%d)", n_prompt, lc->n_ctx);
        return JNI_FALSE;
    }

    // decode the whole prompt in one batch
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(lc->ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt");
        return JNI_FALSE;
    }
    lc->n_past = n_prompt;
    lc->generating = true;
    return JNI_TRUE;
}

// Samples and returns the next token piece as a Java string, or null when the
// generation is finished (end-of-generation token, length limit or context
// full). After returning null the completion is considered done.
JNIEXPORT jstring JNICALL
Java_org_stypox_dicio_llm_LlamaCppEngine_nativeNextToken(JNIEnv *env, jobject, jlong handle) {
    auto *lc = reinterpret_cast<LlamaContext *>(handle);
    if (lc == nullptr || !lc->generating) return nullptr;

    if (lc->n_generated >= lc->n_predict || lc->n_past >= lc->n_ctx) {
        lc->generating = false;
        return nullptr;
    }

    // sample using the logits from the previous decode (prompt or prior token)
    const llama_token token = llama_sampler_sample(lc->sampler, lc->ctx, -1);

    if (llama_vocab_is_eog(lc->vocab, token)) { // API: was `llama_token_is_eog`
        lc->generating = false;
        return nullptr;
    }

    // convert the token to its text piece
    char piece[256];
    const int n = llama_token_to_piece(lc->vocab, token, piece, sizeof(piece), 0, true);
    std::string out;
    if (n > 0) out.assign(piece, n);

    // decode this token so the next sample has fresh logits
    llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
    if (llama_decode(lc->ctx, batch) != 0) {
        LOGE("llama_decode failed during generation");
        lc->generating = false;
        return nullptr;
    }
    lc->n_past++;
    lc->n_generated++;

    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_org_stypox_dicio_llm_LlamaCppEngine_nativeStopCompletion(JNIEnv *, jobject, jlong handle) {
    auto *lc = reinterpret_cast<LlamaContext *>(handle);
    if (lc == nullptr) return;
    lc->generating = false;
}

} // extern "C"
