package com.myassistant.app.semantic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Small helpers for "meaning fingerprints" (embedding vectors). Pure Kotlin. */
object Vectors {
    /** We keep the first 256 of 768 dimensions (EmbeddingGemma supports this) to save space. */
    const val DIM = 256

    fun truncateNormalize(v: FloatArray, dim: Int = DIM): FloatArray {
        val out = v.copyOf(minOf(dim, v.size))
        var n = 0.0
        for (x in out) n += x * x
        val norm = sqrt(n).toFloat().takeIf { it > 0f } ?: 1f
        for (i in out.indices) out[i] /= norm
        return out
    }

    /** Cosine similarity of two already-normalised vectors. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    fun toBytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.float }
    }
}
