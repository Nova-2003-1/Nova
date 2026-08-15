package org.stypox.dicio.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Resolves an Ollama model reference such as `tinydolphin`, `qwen2.5:0.5b` or
 * `myuser/mymodel:latest` into a direct GGUF download URL.
 *
 * Ollama's registry speaks the standard OCI distribution API, anonymously and without any token
 * exchange, so `ollama pull` can be reproduced with two plain GETs:
 *
 *  1. `GET https://<host>/v2/<namespace>/<name>/manifests/<tag>` returns a manifest whose `layers`
 *     array describes the parts of the model.
 *  2. The layer with media type `application/vnd.ollama.image.model` is **the GGUF file itself**,
 *     byte for byte — it needs no unwrapping — and is fetched from
 *     `GET https://<host>/v2/<namespace>/<name>/blobs/<digest>`.
 *
 * The manifest also carries the model's prompt template (`…image.template`), which is what
 * [ChatFormat] needs in order to wrap messages the way the model was trained to expect. It is
 * returned in [Resolved.template] so the caller can persist it alongside the model.
 *
 * Blob responses honour HTTP range requests, so downloads of the resolved URL are resumable.
 */
object OllamaRegistry {

    /** Host used when a reference does not name one explicitly. */
    const val DEFAULT_HOST = "registry.ollama.ai"

    /** Namespace used for Ollama's own curated models, e.g. `tinydolphin` -> `library/tinydolphin`. */
    private const val DEFAULT_NAMESPACE = "library"

    private const val DEFAULT_TAG = "latest"

    private const val MEDIA_TYPE_MODEL = "application/vnd.ollama.image.model"
    private const val MEDIA_TYPE_TEMPLATE = "application/vnd.ollama.image.template"

    private const val ACCEPT_MANIFEST =
        "application/vnd.docker.distribution.manifest.v2+json, application/json"

    private val TAG = OllamaRegistry::class.simpleName

    /** A model reference broken into its parts. */
    data class Reference(
        val host: String,
        val namespace: String,
        val name: String,
        val tag: String,
    ) {
        val manifestUrl: String get() = "https://$host/v2/$namespace/$name/manifests/$tag"
        fun blobUrl(digest: String): String = "https://$host/v2/$namespace/$name/blobs/$digest"

        /** Canonical `host/namespace/name:tag` form, for logging and error messages. */
        override fun toString(): String = "$host/$namespace/$name:$tag"
    }

    /** The outcome of resolving a reference against the registry. */
    data class Resolved(
        /** Direct URL of the GGUF blob, suitable for handing to a plain downloader. */
        val blobUrl: String,
        /** Size of the GGUF in bytes, as declared by the manifest. */
        val sizeBytes: Long,
        /** The model's prompt template, or null if the manifest does not carry one. */
        val template: String?,
    )

    /**
     * Whether [text] should be treated as an Ollama reference rather than a plain download URL.
     * Anything that is already an `http(s)://` URL is left alone.
     */
    fun isReference(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && !t.startsWith("http://") && !t.startsWith("https://")
    }

    /**
     * Splits `[host/][namespace/]name[:tag]` into its parts, applying Ollama's defaults. A leading
     * host is only recognised when it looks like one (contains a dot or a port), so that a two-part
     * reference like `myuser/mymodel` is read as a namespace rather than a host.
     */
    fun parse(text: String): Reference {
        var rest = text.trim().removeSuffix("/")

        // the tag is separated by the last ':', but only if it comes after the last '/', so that
        // a port in the host (`localhost:11434/…`) is not mistaken for a tag
        var tag = DEFAULT_TAG
        val colon = rest.lastIndexOf(':')
        if (colon > rest.lastIndexOf('/')) {
            tag = rest.substring(colon + 1).ifBlank { DEFAULT_TAG }
            rest = rest.substring(0, colon)
        }

        val parts = rest.split('/').filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> throw IllegalArgumentException("Empty model reference")
            parts.size == 1 -> Reference(DEFAULT_HOST, DEFAULT_NAMESPACE, parts[0], tag)
            // a first component that looks like a hostname means the rest is namespace/name
            parts[0].contains('.') || parts[0].contains(':') || parts[0] == "localhost" ->
                Reference(
                    host = parts[0],
                    namespace = parts.subList(1, parts.size - 1)
                        .joinToString("/").ifBlank { DEFAULT_NAMESPACE },
                    name = parts.last(),
                    tag = tag,
                )
            else -> Reference(
                host = DEFAULT_HOST,
                namespace = parts.subList(0, parts.size - 1).joinToString("/"),
                name = parts.last(),
                tag = tag,
            )
        }
    }

    /**
     * Fetches the manifest for [text] and returns the location of its GGUF layer. Throws
     * [IOException] if the registry is unreachable, the reference does not exist, or the manifest
     * carries no model layer.
     */
    suspend fun resolve(client: OkHttpClient, text: String): Resolved =
        withContext(Dispatchers.IO) {
            val ref = parse(text)
            Log.i(TAG, "Resolving $ref via ${ref.manifestUrl}")

            val manifest = JSONObject(getString(client, ref.manifestUrl, ACCEPT_MANIFEST))
            val layers = manifest.optJSONArray("layers")
                ?: throw IOException("Manifest for $ref has no layers")

            var digest: String? = null
            var size = 0L
            var templateDigest: String? = null
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                when (layer.optString("mediaType")) {
                    MEDIA_TYPE_MODEL -> {
                        digest = layer.optString("digest").ifBlank { null }
                        size = layer.optLong("size")
                    }
                    MEDIA_TYPE_TEMPLATE -> {
                        templateDigest = layer.optString("digest").ifBlank { null }
                    }
                }
            }

            if (digest == null) {
                throw IOException("Manifest for $ref contains no $MEDIA_TYPE_MODEL layer")
            }

            // the template is a nicety: a failure to fetch it must not block the download
            val template = templateDigest?.let {
                try {
                    getString(client, ref.blobUrl(it), "*/*")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fetch prompt template for $ref", e)
                    null
                }
            }

            Resolved(blobUrl = ref.blobUrl(digest), sizeBytes = size, template = template)
        }

    private fun getString(client: OkHttpClient, url: String, accept: String): String {
        val request = Request.Builder().url(url).header("Accept", accept).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} fetching $url")
            }
            return response.body?.string() ?: throw IOException("Empty response from $url")
        }
    }
}
