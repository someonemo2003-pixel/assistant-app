import json, os, time, sys
import numpy as np
from huggingface_hub import list_repo_files, hf_hub_download, HfApi
import onnxruntime as ort
from tokenizers import Tokenizer

out = []
def log(*a):
    s = " ".join(str(x) for x in a); print(s); out.append(s)

SENTS = [
    "صحيني الساعة 7 الصبح",        # 0 alarm
    "عايز أصحى بدري بكرة",          # 1 alarm-ish
    "اظبطلي منبه على سبعة",         # 2 alarm
    "افتح يوتيوب",                   # 3 open
    "شغللي الفيس",                   # 4 open facebook
    "عايز أتفرج على فيديوهات",       # 5 youtube-ish
    "ميعاد الدكتور إمتى؟",           # 6 memory query
    "حجزت عند دكتور الأسنان يوم الخميس",  # 7 memory fact
    "عيد ميلاد أمي يوم 5 مارس",      # 8 fact
    "وضع النوم",                     # 9 shortcut
]

def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

def probe(repo, prompt, prefer):
    log("=" * 70); log("REPO", repo)
    api = HfApi()
    info = api.model_info(repo, files_metadata=True)
    for s in info.siblings:
        if s.rfilename.startswith("onnx/") or s.rfilename.startswith("tokenizer") or s.rfilename.endswith(".json"):
            log("  file", s.rfilename, s.size)
    tok_path = hf_hub_download(repo, "tokenizer.json")
    tj = json.load(open(tok_path, encoding="utf-8"))
    m = tj["model"]
    log("tokenizer model type:", m.get("type"), "byte_fallback:", m.get("byte_fallback"), "ignore_merges:", m.get("ignore_merges"),
        "fuse_unk:", m.get("fuse_unk"), "unk:", m.get("unk_token") if "unk_token" in m else m.get("unk_id"))
    log("vocab size:", len(m["vocab"]), "merges:", len(m.get("merges", [])))
    if m.get("merges"): log("merge sample:", m["merges"][:3])
    log("normalizer:", json.dumps(tj.get("normalizer"), ensure_ascii=False)[:400])
    log("pre_tokenizer:", json.dumps(tj.get("pre_tokenizer"), ensure_ascii=False)[:400])
    log("post_processor:", json.dumps(tj.get("post_processor"), ensure_ascii=False)[:600])
    log("decoder:", json.dumps(tj.get("decoder"), ensure_ascii=False)[:200])
    log("added_tokens (first 6):", [(t["id"], t["content"]) for t in tj.get("added_tokens", [])[:6]])
    tok = Tokenizer.from_file(tok_path)
    for s in SENTS[:4]:
        e = tok.encode(prompt + s)
        log("ids", s, "->", e.ids[:40], e.tokens[:40])
    files = [s.rfilename for s in info.siblings]
    model_file = next((f for f in prefer if f in files), None)
    log("using model file", model_file)
    path = hf_hub_download(repo, model_file)
    data = model_file + "_data"
    if data in files: hf_hub_download(repo, data)
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    log("inputs:", [(i.name, i.type, i.shape) for i in sess.get_inputs()])
    log("outputs:", [(o.name, o.type, o.shape) for o in sess.get_outputs()])
    embs = []
    t0 = time.time()
    for s in SENTS:
        e = tok.encode(prompt + s)
        ids = np.array([e.ids], dtype=np.int64)
        mask = np.ones_like(ids)
        feeds = {}
        for i in sess.get_inputs():
            if "input_ids" in i.name: feeds[i.name] = ids
            elif "attention" in i.name: feeds[i.name] = mask
            elif "token_type" in i.name: feeds[i.name] = np.zeros_like(ids)
        res = sess.run(None, feeds)
        names = [o.name for o in sess.get_outputs()]
        if "sentence_embedding" in names:
            v = res[names.index("sentence_embedding")][0]
        else:
            h = res[0][0]; v = h.mean(axis=0)
        embs.append(v / np.linalg.norm(v))
    log("avg ms per sentence (CI cpu):", round((time.time() - t0) * 1000 / len(SENTS), 1), "dim", len(embs[0]))
    log("similarity matrix:")
    for i, s in enumerate(SENTS):
        log(f"{i:2d}", " ".join(f"{cos(embs[i], embs[j]):.2f}" for j in range(len(SENTS))), "|", s)

try:
    probe("onnx-community/embeddinggemma-300m-ONNX", "task: sentence similarity | query: ",
          ["onnx/model_q4.onnx", "onnx/model_quantized.onnx", "onnx/model_int8.onnx", "onnx/model.onnx"])
except Exception as e:
    import traceback; log("GEMMA FAILED", repr(e)); log(traceback.format_exc()[-1500:])
try:
    probe("Xenova/multilingual-e5-small", "query: ",
          ["onnx/model_quantized.onnx", "onnx/model_int8.onnx", "onnx/model.onnx"])
except Exception as e:
    import traceback; log("E5 FAILED", repr(e)); log(traceback.format_exc()[-1500:])

open("probe-results.txt", "w", encoding="utf-8").write("\n".join(out))
