package org.stypox.dicio.llm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class OllamaRegistryTest : StringSpec({

    "a bare name resolves to the library namespace and the latest tag" {
        val ref = OllamaRegistry.parse("tinydolphin")
        ref.host shouldBe "registry.ollama.ai"
        ref.namespace shouldBe "library"
        ref.name shouldBe "tinydolphin"
        ref.tag shouldBe "latest"
        ref.manifestUrl shouldBe
            "https://registry.ollama.ai/v2/library/tinydolphin/manifests/latest"
    }

    "an explicit tag is honoured" {
        val ref = OllamaRegistry.parse("qwen2.5:0.5b")
        ref.name shouldBe "qwen2.5"
        ref.tag shouldBe "0.5b"
        ref.manifestUrl shouldBe "https://registry.ollama.ai/v2/library/qwen2.5/manifests/0.5b"
    }

    "a dot in the model name is not mistaken for a host" {
        // "qwen2.5" contains a dot, but as a single component it is a model name
        OllamaRegistry.parse("qwen2.5").host shouldBe "registry.ollama.ai"
        OllamaRegistry.parse("qwen2.5").name shouldBe "qwen2.5"
    }

    "a two-part reference is read as namespace/name, not host/name" {
        val ref = OllamaRegistry.parse("myuser/mymodel:v2")
        ref.host shouldBe "registry.ollama.ai"
        ref.namespace shouldBe "myuser"
        ref.name shouldBe "mymodel"
        ref.tag shouldBe "v2"
    }

    "a leading hostname is recognised" {
        val ref = OllamaRegistry.parse("hf.co/bartowski/model:Q4_K_M")
        ref.host shouldBe "hf.co"
        ref.namespace shouldBe "bartowski"
        ref.name shouldBe "model"
        ref.tag shouldBe "Q4_K_M"
    }

    "a port in the host is not mistaken for a tag" {
        val ref = OllamaRegistry.parse("localhost:11434/library/tinydolphin")
        ref.host shouldBe "localhost:11434"
        ref.namespace shouldBe "library"
        ref.name shouldBe "tinydolphin"
        ref.tag shouldBe "latest"
    }

    "the blob url is built from the digest" {
        OllamaRegistry.parse("tinydolphin").blobUrl("sha256:abc") shouldBe
            "https://registry.ollama.ai/v2/library/tinydolphin/blobs/sha256:abc"
    }

    "http(s) urls are not treated as references" {
        OllamaRegistry.isReference("https://example.com/model.gguf") shouldBe false
        OllamaRegistry.isReference("http://example.com/model.gguf") shouldBe false
        OllamaRegistry.isReference("tinydolphin") shouldBe true
        OllamaRegistry.isReference("  ") shouldBe false
    }
})
