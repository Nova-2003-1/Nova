// Stub used only when the llama.cpp submodule is absent (see CMakeLists.txt).
// It intentionally defines NO JNI symbols, so the native methods declared in
// LlamaCppEngine.kt resolve to nothing and the Kotlin side falls back to
// reporting "native library missing" instead of crashing. This lets the rest of
// the app build and run without the (large) llama.cpp checkout.
extern "C" void dicio_llm_stub_marker() {}
