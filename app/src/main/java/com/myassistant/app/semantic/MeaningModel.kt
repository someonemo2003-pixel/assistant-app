package com.myassistant.app.semantic

import android.content.Context
import com.myassistant.app.ai.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val downloaded: Long, val total: Long) : ModelState
    data object Loading : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

enum class EmbedKind { SIMILARITY, QUERY, DOCUMENT }

/**
 * The on-device "meaning" model (Google EmbeddingGemma, ~200 MB, downloaded once from
 * Hugging Face). Turns a sentence into a meaning fingerprint, fully offline.
 */
class MeaningModel(context: Context) {

    private val dir = File(context.filesDir, "models/embeddinggemma")
    private val files = listOf(
        "$BASE/onnx/$MODEL" to MODEL,
        "$BASE/onnx/${MODEL}_data" to "${MODEL}_data",
        "$BASE/tokenizer.model" to "tokenizer.model",
    )

    private val _state = MutableStateFlow<ModelState>(if (isDownloaded()) ModelState.Loading else ModelState.NotDownloaded)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var model: OnnxEmbedModel? = null

    val isReady get() = _state.value == ModelState.Ready

    fun isDownloaded() = files.all { File(dir, it.second).let { f -> f.exists() && f.length() > 0 } }

    /** Loads the model into memory if it's on the phone. Safe to call many times. */
    suspend fun warmUp() {
        if (!isDownloaded()) {
            _state.value = ModelState.NotDownloaded
            return
        }
        mutex.withLock {
            if (model != null) return
            _state.value = ModelState.Loading
            try {
                model = withContext(Dispatchers.IO) {
                    OnnxEmbedModel(File(dir, MODEL), File(dir, "tokenizer.model"))
                }
                _state.value = ModelState.Ready
            } catch (e: Throwable) {
                _state.value = ModelState.Failed("تعذّر تشغيل الموديل: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Meaning fingerprint (256 numbers), or null if the model isn't ready. */
    suspend fun embed(text: String, kind: EmbedKind): FloatArray? {
        if (!isReady) return null
        val prompt = when (kind) {
            EmbedKind.SIMILARITY -> OnnxEmbedModel.SIMILARITY
            EmbedKind.QUERY -> OnnxEmbedModel.SEARCH_QUERY
            EmbedKind.DOCUMENT -> OnnxEmbedModel.DOCUMENT
        }
        val clean = text.trim().take(MAX_CHARS)
        if (clean.isEmpty()) return null
        return mutex.withLock {
            val m = model ?: return@withLock null
            withContext(Dispatchers.Default) {
                runCatching { Vectors.truncateNormalize(m.embedRaw(prompt + clean)) }.getOrNull()
            }
        }
    }

    /** Downloads the model files (resumable), then loads it. */
    suspend fun download() {
        val client = Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        try {
            dir.mkdirs()
            val sizes = files.map { (url, _) -> withContext(Dispatchers.IO) { remoteSize(client, url) } }
            val total = sizes.filter { it > 0 }.sum()
            fun complete(i: Int, f: File) = f.exists() && f.length() > 0 && (sizes[i] < 0 || f.length() == sizes[i])
            var done = files.mapIndexed { i, (_, name) ->
                val f = File(dir, name)
                if (complete(i, f)) f.length() else File(dir, "$name.part").length()
            }.sum()
            _state.value = ModelState.Downloading(done, total)

            files.forEachIndexed { i, (url, name) ->
                val target = File(dir, name)
                if (complete(i, target)) return@forEachIndexed
                val part = File(dir, "$name.part")
                withContext(Dispatchers.IO) {
                    val start = part.length()
                    val req = Request.Builder().url(url).apply { if (start > 0) header("Range", "bytes=$start-") }.build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        val append = start > 0 && r.code == 206
                        if (!append) done -= start
                        val body = r.body ?: throw IOException("empty body")
                        FileOutputStream(part, append).use { out ->
                            val buf = ByteArray(256 * 1024)
                            val input = body.byteStream()
                            var last = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done - last > 1_000_000) {
                                    last = done
                                    _state.value = ModelState.Downloading(done, total)
                                }
                            }
                        }
                    }
                    if (!part.renameTo(target)) throw IOException("rename failed")
                }
            }
            _state.value = ModelState.Loading
            warmUp()
        } catch (e: CancellationException) {
            _state.value = if (isDownloaded()) ModelState.Loading else ModelState.NotDownloaded
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Failed("فشل التحميل: ${e.message ?: e.javaClass.simpleName}. دوس تحميل تاني يكمّل من مكان ما وقف.")
        }
    }

    suspend fun delete() {
        mutex.withLock {
            model?.close()
            model = null
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            _state.value = ModelState.NotDownloaded
        }
    }

    private fun remoteSize(client: okhttp3.OkHttpClient, url: String): Long {
        val req = Request.Builder().url(url).head().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.header("Content-Length")?.toLongOrNull()
                ?: r.header("X-Linked-Size")?.toLongOrNull()
                ?: -1L
        }
    }

    companion object {
        const val BASE = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main"
        const val MODEL = "model_no_gather_q4.onnx"
        const val MAX_CHARS = 600
        const val APPROX_MB = 200
    }
}
