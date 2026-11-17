"""Downloads EmbeddingGemma files for the JVM tests and writes reference ids/embeddings (from HF tokenizers + ORT)."""
import os, sys
import numpy as np
from huggingface_hub import hf_hub_download
from tokenizers import Tokenizer
import onnxruntime as ort

REPO = "onnx-community/embeddinggemma-300m-ONNX"
MODEL = "onnx/model_no_gather_q4.onnx"
d = os.environ["GEMMA_TEST_DIR"]
os.makedirs(d, exist_ok=True)
paths = {}
for f in ["tokenizer.model", "tokenizer.json", MODEL, MODEL + "_data"]:
    paths[f] = hf_hub_download(REPO, f, local_dir=d)
tok = Tokenizer.from_file(paths["tokenizer.json"])
sess = ort.InferenceSession(paths[MODEL], providers=["CPUExecutionProvider"])

P = "task: sentence similarity | query: "
D = "title: none | text: "
S = [
    P + "صحيني الساعة 7 الصبح", P + "عايز أصحى بدري بكرة", P + "اظبطلي منبه على سبعة", P + "افتح يوتيوب",
    P + "شغللي الفيس", P + "وضع النوم", D + "حجزت عند دكتور الأسنان يوم الخميس", D + "عيد ميلاد أمي يوم 5 مارس",
    "Hello World, how are you?", "مرحبا 😀 يا جماعة!!", "الإيميل بتاع شركة X عن عرض السعر 1500 جنيه",
    "ﷺ ﻻ ٱلرَّحْمَٰن", "   مسافات   كتير  ", "a\tb\nc", "صحّيني", "OpenRouter وGroq وGemini",
]
with open(os.path.join(d, "ref.tsv"), "w", encoding="utf-8") as out:
    for s in S:
        ids = tok.encode(s).ids
        x = np.array([ids], dtype=np.int64)
        v = sess.run(["sentence_embedding"], {"input_ids": x, "attention_mask": np.ones_like(x)})[0][0]
        v = v / np.linalg.norm(v)
        esc = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        out.write(esc + "\t" + " ".join(map(str, ids)) + "\t" + " ".join(f"{t:.6f}" for t in v) + "\n")
print("wrote", len(S), "references to", d)
