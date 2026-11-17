package com.myassistant.app.semantic

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin SentencePiece BPE tokenizer for EmbeddingGemma, reading the original
 * `tokenizer.model` (protobuf). Verified in CI against Hugging Face `tokenizers`.
 */
class GemmaTokenizer private constructor(
    private val pieces: HashMap<String, Int>,
    private val scores: FloatArray,
    private val types: ByteArray,
    private val byteIds: IntArray,
    val bosId: Int,
    val eosId: Int,
    private val unkId: Int,
) {

    fun encode(text: String, maxTokens: Int = 256): LongArray {
        val ids = ArrayList<Int>(64)
        ids += bosId
        val normalized = text.replace(' ', '▁')
        if (normalized.isNotEmpty()) bpe(normalized, ids)
        if (ids.size > maxTokens - 1) {
            while (ids.size > maxTokens - 1) ids.removeAt(ids.size - 1)
        }
        ids += eosId
        return LongArray(ids.size) { ids[it].toLong() }
    }

    private fun bpe(text: String, out: MutableList<Int>) {
        // Start from single characters (code points).
        val symbols = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            symbols += text.substring(i, i + n)
            i += n
        }
        // Repeatedly merge the adjacent pair whose concatenation is the best-scoring piece.
        while (symbols.size > 1) {
            var bestIdx = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (k in 0 until symbols.size - 1) {
                val id = pieces[symbols[k] + symbols[k + 1]] ?: continue
                if (!mergeable(id)) continue
                val s = scores[id]
                if (s > bestScore) {
                    bestScore = s
                    bestIdx = k
                }
            }
            if (bestIdx < 0) break
            symbols[bestIdx] = symbols[bestIdx] + symbols[bestIdx + 1]
            symbols.removeAt(bestIdx + 1)
        }
        for (s in symbols) {
            val id = pieces[s]
            if (id != null && types[id] != TYPE_UNUSED) {
                out += id
            } else {
                // Byte fallback: one token per UTF-8 byte.
                for (b in s.toByteArray(Charsets.UTF_8)) {
                    val bid = byteIds[b.toInt() and 0xFF]
                    out += if (bid >= 0) bid else unkId
                }
            }
        }
    }

    private fun mergeable(id: Int) = types[id] == TYPE_NORMAL || types[id] == TYPE_USER_DEFINED

    companion object {
        private const val TYPE_NORMAL: Byte = 1
        private const val TYPE_UNKNOWN: Byte = 2
        private const val TYPE_CONTROL: Byte = 3
        private const val TYPE_USER_DEFINED: Byte = 4
        private const val TYPE_UNUSED: Byte = 5
        private const val TYPE_BYTE: Byte = 6

        fun load(input: InputStream): GemmaTokenizer = parse(input.readBytes())

        private fun parse(bytes: ByteArray): GemmaTokenizer {
            val names = ArrayList<String>(270_000)
            val scoreList = ArrayList<Float>(270_000)
            val typeList = ArrayList<Byte>(270_000)
            val r = ProtoReader(bytes, 0, bytes.size)
            while (r.hasMore()) {
                val tag = r.varint().toInt()
                val field = tag ushr 3
                val wire = tag and 7
                if (field == 1 && wire == 2) {
                    val len = r.varint().toInt()
                    val sub = ProtoReader(bytes, r.pos, r.pos + len)
                    var piece = ""
                    var score = 0f
                    var type: Byte = TYPE_NORMAL
                    while (sub.hasMore()) {
                        val t = sub.varint().toInt()
                        when {
                            t ushr 3 == 1 && t and 7 == 2 -> piece = sub.string()
                            t ushr 3 == 2 && t and 7 == 5 -> score = sub.fixed32Float()
                            t ushr 3 == 3 && t and 7 == 0 -> type = sub.varint().toByte()
                            else -> sub.skip(t and 7)
                        }
                    }
                    names += piece
                    scoreList += score
                    typeList += type
                    r.pos += len
                } else {
                    r.skip(wire)
                }
            }
            val map = HashMap<String, Int>(names.size * 2)
            val byteIds = IntArray(256) { -1 }
            var bos = 2
            var eos = 1
            var unk = 3
            names.forEachIndexed { id, p ->
                when (typeList[id]) {
                    TYPE_BYTE -> if (p.length == 6 && p.startsWith("<0x")) {
                        p.substring(3, 5).toIntOrNull(16)?.let { byteIds[it] = id }
                    }
                    TYPE_CONTROL -> when (p) {
                        "<bos>" -> bos = id
                        "<eos>" -> eos = id
                    }
                    TYPE_UNKNOWN -> unk = id
                    else -> Unit
                }
                if (typeList[id] != TYPE_CONTROL && typeList[id] != TYPE_BYTE) map.putIfAbsent(p, id)
            }
            return GemmaTokenizer(map, scoreList.toFloatArray(), typeList.toByteArray(), byteIds, bos, eos, unk)
        }
    }

    private class ProtoReader(private val buf: ByteArray, var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun string(): String {
            val len = varint().toInt()
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        fun fixed32Float(): Float {
            val f = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float
            pos += 4
            return f
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> {
                    val n = varint().toInt() // read the length first, then jump over the bytes
                    pos += n
                }
                5 -> pos += 4
                else -> pos = end
            }
        }
    }
}
