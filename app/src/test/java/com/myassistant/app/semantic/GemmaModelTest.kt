package com.myassistant.app.semantic

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks our Kotlin tokenizer and ONNX code against Hugging Face's reference implementation.
 * CI downloads the model and writes ref.tsv (see tools/ci/gemma_ref.py); skipped locally.
 */
class GemmaModelTest {

    private val dir = File(System.getProperty("gemmaDir").orEmpty())

    private data class Ref(val text: String, val ids: List<Long>, val emb: FloatArray)

    private fun refs(): List<Ref> {
        val f = File(dir, "ref.tsv")
        assumeTrue("no reference data (runs in CI only)", f.exists())
        return f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            val (t, ids, emb) = line.split('\t')
            val text = t.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
            Ref(text, ids.split(' ').map { it.toLong() }, emb.split(' ').map { it.toFloat() }.toFloatArray())
        }
    }

    @Test fun tokenizerMatchesHuggingFace() {
        val refs = refs()
        val tok = File(dir, "tokenizer.model").inputStream().use { GemmaTokenizer.load(it) }
        var bad = 0
        for (r in refs) {
            val ours = tok.encode(r.text, maxTokens = 10_000).toList()
            if (ours != r.ids) {
                bad++
                println("MISMATCH for [${r.text}]\n  hf  = ${r.ids}\n  ours= $ours")
            }
        }
        println("tokenizer: ${refs.size - bad}/${refs.size} identical")
        assertTrue("$bad of ${refs.size} sentences tokenized differently", bad == 0)
    }

    @Test fun embeddingsMatchReference() {
        val refs = refs()
        OnnxEmbedModel(File(dir, "onnx/${MeaningModel.MODEL}"), File(dir, "tokenizer.model")).use { m ->
            var worst = 1f
            val t0 = System.nanoTime()
            for (r in refs) {
                val ours = m.embedRaw(r.text)
                val cos = Vectors.dot(ours, Vectors.truncateNormalize(r.emb, r.emb.size))
                worst = minOf(worst, cos)
                println("cos=%.4f  %s".format(cos, r.text.take(40)))
            }
            println("avg ms: ${(System.nanoTime() - t0) / 1_000_000 / refs.size}")
            assertTrue("embedding differs from reference (worst cos=$worst)", worst > 0.99f)

            // The meaning model should prefer the right neighbour for a couple of obvious pairs.
            fun sim(a: String, b: String) = Vectors.dot(
                Vectors.truncateNormalize(m.embedRaw(OnnxEmbedModel.SIMILARITY + a)),
                Vectors.truncateNormalize(m.embedRaw(OnnxEmbedModel.SIMILARITY + b)),
            )
            val alarm = sim("صحيني الساعة 7 الصبح", "اظبطلي منبه على سبعة")
            val wrong = sim("صحيني الساعة 7 الصبح", "افتح يوتيوب")
            println("alarm-vs-alarm=$alarm alarm-vs-youtube=$wrong")
            assertTrue(alarm > wrong)
        }
    }
}
