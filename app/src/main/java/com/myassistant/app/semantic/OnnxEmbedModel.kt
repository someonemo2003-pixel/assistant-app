package com.myassistant.app.semantic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer

/**
 * Runs the EmbeddingGemma ONNX model with ONNX Runtime. Plain JVM code (shared by the app and
 * the CI tests). Not thread-safe by contract — callers serialise access.
 */
class OnnxEmbedModel(modelFile: File, tokenizerFile: File, threads: Int = 4) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    val tokenizer: GemmaTokenizer = tokenizerFile.inputStream().use { GemmaTokenizer.load(it) }

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.absolutePath, opts)
    }

    /** Full 768-dim normalised embedding for an already-prompted text. */
    fun embedRaw(prompted: String): FloatArray {
        val ids = tokenizer.encode(prompted)
        val shape = longArrayOf(1, ids.size.toLong())
        val mask = LongArray(ids.size) { 1L }
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskT ->
                session.run(mapOf("input_ids" to idT, "attention_mask" to maskT)).use { res ->
                    val out = res.get("sentence_embedding").get() as OnnxTensor
                    val fb = out.floatBuffer
                    val v = FloatArray(fb.remaining())
                    fb.get(v)
                    return Vectors.truncateNormalize(v, v.size)
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** Prompts recommended for EmbeddingGemma. */
        const val SIMILARITY = "task: sentence similarity | query: "
        const val SEARCH_QUERY = "task: search result | query: "
        const val DOCUMENT = "title: none | text: "
    }
}
