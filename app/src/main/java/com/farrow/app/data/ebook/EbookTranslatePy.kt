package com.farrow.app.data.ebook

import java.util.Base64

/**
 * Python ebook translator dropped into Termux at `~/.farrow/farrow_ebook_translate.py` before each run.
 * MOBI first (mobi), then EPUB/PDF/DOCX (plain-text fallback); googletrans>=4.0.2 in ≤4000-char chunks (never ≥5000), ~0.3 s + 429 backoff between
 * requests, 5–10 s pause per 4-chapter batch, MyMemory fallback (500-char pieces, deep-translator), language check,
 * resume via a sidecar `.farrow-translate.json`, `--estimate` for an ETA, output under Documents/Farrow/Output.
 */
object EbookTranslatePy {
    /** Absolute Termux paths: a quoted "~/…" is never tilde-expanded by bash (v1.0.22 bug: script "missing"). */
    const val DIR = com.farrow.app.data.termux.TermuxManager.TERMUX_HOME + "/.farrow"
    const val FILE = "$DIR/farrow_ebook_translate.py"
    const val MARKER = "FARROW_JSON="

    val SOURCE = """
# Farrow ebook translator (written by the Farrow app; changes are overwritten).
# Settings follow howtotranslate.md: googletrans>=4.0.2 with a browser User-Agent, ~4000-char chunks (hard cap < 5000),
# ~0.3 s between requests + exponential backoff on "Too many requests" (429), a 5-10 s pause after every batch of
# 4 chapters, MyMemory fallback (500-char pieces), plain-text extraction fallback, resume.
# Formats: MOBI (preferred), EPUB, PDF, DOCX, TXT/MD.  --estimate prints chapter/chunk counts and an ETA only.
import argparse, json, os, random, re, sys, time
from pathlib import Path

CHUNK = 4000                 # target chunk size; Google rejects >= 5000 chars, so never exceed HARD_MAX
HARD_MAX = 4999
DELAY = (0.3, 0.45)          # seconds between requests (~0.3 s)
BACKOFF_429 = (10, 20, 40, 80, 160)   # seconds to wait after each "Too many requests" before retrying
USER_AGENT = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/129.0.0.0 Mobile Safari/537.36")
BATCH_CHAPTERS = 4           # chapters per batch (howtotranslate: 3-5)
BATCH_PAUSE = (5.0, 10.0)    # seconds between batches
MYMEMORY_MAX = 500           # MyMemory per-request limit
REQ_EST = (1.0, 2.0, 3.0)    # min / typical / max seconds one Google request takes (ETA only)
PSEUDO_CHAPTER = 20000       # books without detectable chapters are split into ~20k-char sections
MARKER = "FARROW_JSON="
assert CHUNK < 5000 and HARD_MAX < 5000


def out(obj):
    print(MARKER + json.dumps(obj, ensure_ascii=False), flush=True)
    return 0 if obj.get("ok") else 1


def need(mods):
    missing = []
    for m in mods:
        try:
            __import__(m.split(".")[0])
        except ImportError:
            missing.append(m)
    return missing


def detect_fmt(path, forced=None):
    if forced:
        return forced.lower()
    ext = path.suffix.lower().lstrip(".")
    return {"mobi": "mobi", "azw": "mobi", "azw3": "mobi", "epub": "epub", "pdf": "pdf",
            "docx": "docx", "txt": "txt", "md": "txt", "markdown": "txt"}.get(ext, ext)


def html_to_text(t):
    t = re.sub(r"(?is)<script.*?>.*?</script>", " ", t)
    t = re.sub(r"(?is)<style.*?>.*?</style>", " ", t)
    t = re.sub(r"(?is)<[^>]+>", "\n", t)
    for k, v in (("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", '"'), ("&#39;", "'")):
        t = t.replace(k, v)
    return re.sub(r"\n{3,}", "\n\n", t).strip()


CHAPTER_RE = re.compile(r"(?im)^[ \t]*(chapter|chapitre|cap[ií]tulo|capitolo|kapitel|hoofdstuk|rozdzia[lł]|part|partie|book|livre|prologue|prologo|epilogue|[ée]pilogue)\b[^\n]{0,80}$")


def split_chapters(text):
    # One big text -> chapters on headings; else ~PSEUDO_CHAPTER-char sections on paragraph breaks.
    text = text.strip()
    if not text:
        return []
    starts = [m.start() for m in CHAPTER_RE.finditer(text)]
    if len(starts) >= 2:
        if starts[0] > 0:
            starts = [0] + starts
        parts = [text[a:b].strip() for a, b in zip(starts, starts[1:] + [len(text)])]
        return [p for p in parts if p]
    parts, buf, n = [], [], 0
    for para in re.split(r"\n\s*\n", text):
        if n + len(para) > PSEUDO_CHAPTER and buf:
            parts.append("\n\n".join(buf)); buf, n = [], 0
        buf.append(para); n += len(para) + 2
    if buf:
        parts.append("\n\n".join(buf))
    return parts


def extract_mobi(path):
    import mobi, shutil
    tmpdir, extracted = mobi.extract(str(path))
    try:
        ex = Path(extracted)
        files = [ex] if ex.is_file() else sorted(p for p in ex.rglob("*") if p.suffix.lower() in (".html", ".htm", ".xhtml", ".txt"))
        texts = []
        for p in files:
            try:
                t = p.read_text(encoding="utf-8", errors="ignore")
            except Exception:
                continue
            t = html_to_text(t) if p.suffix.lower() != ".txt" else t.strip()
            if t:
                texts.append(t)
        if len(texts) == 1:
            return split_chapters(texts[0])
        return texts
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def extract_epub(path):
    from ebooklib import epub, ITEM_DOCUMENT
    book = epub.read_epub(str(path))
    parts = []
    for item in book.get_items_of_type(ITEM_DOCUMENT):
        t = html_to_text(item.get_content().decode("utf-8", "ignore"))
        if t:
            parts.append(t)
    return parts


def extract_pdf(path):
    try:
        import fitz
        doc = fitz.open(str(path))
        text = "\n\n".join(page.get_text("text") for page in doc)
    except ImportError:
        import subprocess
        text = subprocess.run(["pdftotext", "-layout", str(path), "-"], capture_output=True, text=True, check=True).stdout
    return split_chapters(text)


def extract_docx(path):
    import docx
    d = docx.Document(str(path))
    chapters, buf = [], []
    for p in d.paragraphs:
        if not p.text.strip():
            continue
        style = (p.style.name or "") if p.style is not None else ""
        if style.lower().startswith(("heading 1", "title")) and buf:
            chapters.append("\n\n".join(buf)); buf = []
        buf.append(p.text)
    if buf:
        chapters.append("\n\n".join(buf))
    return chapters if len(chapters) > 1 else split_chapters("\n\n".join(chapters))


def extract_txt(path):
    return split_chapters(path.read_text(encoding="utf-8", errors="ignore"))


def extract_plain(path):
    # Plain-text fallback: decode the raw bytes, strip markup/binary noise, keep readable text.
    raw = path.read_bytes()
    t = raw.decode("utf-8", "ignore")
    if t.count("\ufffd") > len(t) // 20:
        t = raw.decode("latin-1", "ignore")
    t = html_to_text(t)
    t = re.sub(r"[^\S\n]+", " ", re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", " ", t))
    lines = [ln for ln in t.splitlines() if sum(ch.isalpha() for ch in ln) >= max(3, len(ln.strip()) // 2)]
    return split_chapters("\n".join(lines))


FALLBACK_USED = []


def extract(path, fmt):
    fn = {"mobi": extract_mobi, "epub": extract_epub, "pdf": extract_pdf, "docx": extract_docx, "txt": extract_txt}.get(fmt)
    if fn is None:
        raise ValueError("Unsupported format: %s (use mobi, epub, pdf, docx, txt)" % fmt)
    err = None
    try:
        got = [c for c in fn(path) if c and c.strip()]
        if got:
            return got
    except Exception as e:
        err = e
    plain = [c for c in extract_plain(path) if c and c.strip()]
    if sum(len(c) for c in plain) >= 200:
        FALLBACK_USED.append("plaintext (%s reader: %s)" % (fmt, err or "no text"))
        return plain
    if err:
        raise err
    return []


def chunk_text(text, size=CHUNK):
    # Paragraph-aware chunks of <= size chars (size is clamped below 5000).
    size = max(100, min(size, HARD_MAX))
    text = text.replace("\r\n", "\n").strip()
    if not text:
        return []
    parts, buf, n = [], [], 0
    for para in re.split(r"\n\s*\n", text):
        para = para.strip()
        if not para:
            continue
        if n + len(para) + 2 > size and buf:
            parts.append("\n\n".join(buf)); buf, n = [], 0
        if len(para) > size:
            # Long paragraph: cut on sentence ends, then hard-cut anything still too long.
            piece = ""
            for sent in re.split(r"(?<=[.!?…。！？])\s+", para):
                while len(sent) > size:
                    if piece:
                        parts.append(piece); piece = ""
                    parts.append(sent[:size]); sent = sent[size:]
                if len(piece) + len(sent) + 1 > size and piece:
                    parts.append(piece); piece = ""
                piece = (piece + " " + sent).strip()
            if piece:
                parts.append(piece)
        else:
            buf.append(para); n += len(para) + 2
    if buf:
        parts.append("\n\n".join(buf))
    assert all(len(p) <= size for p in parts)
    return parts


def plan(chapters):
    # [(chapter_index, chunk_text)] for the whole book.
    out_ = []
    for ci, ch in enumerate(chapters):
        for c in chunk_text(ch):
            out_.append((ci, c))
    return out_


def estimate(n_chunks, n_chapters, start_chapter=0):
    # Batch pauses fall after every BATCH_CHAPTERS chapters that still have to be translated.
    left_ch = max(0, n_chapters - start_chapter)
    pauses = max(0, (left_ch - 1) // BATCH_CHAPTERS)
    lo = n_chunks * (REQ_EST[0] + DELAY[0]) + pauses * BATCH_PAUSE[0]
    mid = n_chunks * (REQ_EST[1] + sum(DELAY) / 2) + pauses * sum(BATCH_PAUSE) / 2
    hi = n_chunks * (REQ_EST[2] + DELAY[1]) + pauses * BATCH_PAUSE[1]
    return int(lo), int(mid), int(hi), pauses


# --- Language codes -------------------------------------------------------------------------------------------
# Each engine wants a different code style: googletrans takes bare/lowercase codes from googletrans.LANGUAGES
# ("fr", "zh-cn", "pt"; NOT "pt-BR"/"en-US"/"nb"), MyMemory (deep-translator) only accepts regional codes
# ("fr-FR", "zh-CN", "pt-BR"; bare "fr" raises LanguageNotSupported) and langdetect returns its own ("zh-cn", "he",
# "tl", "no"). The user's code is passed through untouched, so each engine normalises it here.
ALIASES = {"iw": "he", "jw": "jv", "tl": "fil", "nb": "no", "nn": "no", "zh": "zh-cn"}
MM_PREFERRED = {"en": "en-GB", "pt": "pt-PT", "es": "es-ES", "fr": "fr-FR", "de": "de-DE", "zh": "zh-CN", "ar": "ar-SA",
                "no": "nb-NO", "he": "he-IL", "fil": "fil-PH", "sr": "sr-Latn-RS", "ta": "ta-IN"}


def lang_family(code):
    c = (code or "").strip().lower().replace("_", "-")
    c = ALIASES.get(c, c)
    if c in ("zh-tw", "zh-hk", "zh-mo"):
        return "zh-tw"
    if c.startswith("zh"):
        return "zh-cn"
    return ALIASES.get(c.split("-")[0], c.split("-")[0])


def google_code(code):
    if code in ("", None, "auto"):
        return "auto"
    from googletrans import LANGUAGES
    c = code.strip().lower().replace("_", "-")
    if c in LANGUAGES:
        return c
    fam = lang_family(c)
    for k in (fam, {"he": "iw", "jv": "jw", "fil": "tl"}.get(fam, fam)):
        if k in LANGUAGES:
            return k
    return c


def mymemory_code(code):
    from deep_translator.constants import MY_MEMORY_LANGUAGES_TO_CODES as M
    codes = list(M.values())
    c = code.strip().replace("_", "-")
    for k in codes:
        if k.lower() == c.lower():
            return k
    fam = lang_family(c)
    if fam == "zh-tw":
        return "zh-TW"
    if fam in MM_PREFERRED:
        return MM_PREFERRED[fam]
    for k in codes:
        if k.lower().split("-")[0] == fam:
            return k
    raise ValueError("MyMemory does not support language %r" % code)


def guess_source(text, src):
    if src not in ("", None, "auto"):
        return src
    try:
        from langdetect import detect
        return detect(text[:2000])
    except Exception:
        return "en"


class Engines:
    def __init__(self):
        self.loop = None
        self.gt = None

    def _google(self, text, src, dest):
        import asyncio
        from googletrans import Translator
        if self.gt is None:
            self.loop = asyncio.new_event_loop()
            try:
                self.gt = Translator(user_agent=USER_AGENT)
            except TypeError:
                self.gt = Translator()
        r = self.gt.translate(text, src=google_code(src), dest=google_code(dest))
        if asyncio.iscoroutine(r):   # googletrans >= 4.0.2 is async; old 4.0.0rc1 was sync
            r = self.loop.run_until_complete(r)
        if not r or not getattr(r, "text", None):
            raise RuntimeError("empty googletrans result")
        return r.text

    def _mymemory(self, text, src, dest):
        from deep_translator import MyMemoryTranslator
        # Was: source hard-coded to "en-GB" for auto and the bare target ("fr") passed as-is, which MyMemory rejects.
        s = mymemory_code(guess_source(text, src))
        d = mymemory_code(dest)
        pieces, cur = [], ""
        for w in re.split(r"(\s+)", text):
            if len(cur) + len(w) > MYMEMORY_MAX and cur:
                pieces.append(cur); cur = ""
            while len(w) > MYMEMORY_MAX:
                pieces.append(w[:MYMEMORY_MAX]); w = w[MYMEMORY_MAX:]
            cur += w
        if cur.strip():
            pieces.append(cur)
        res = []
        for i, p in enumerate(pieces):
            if not p.strip():
                res.append(p); continue
            res.append(MyMemoryTranslator(source=s, target=d).translate(p) or "")
            if i + 1 < len(pieces):
                time.sleep(random.uniform(*DELAY))
        return " ".join(x.strip() for x in res)

    def translate(self, text, src, dest, prefer_fallback=False):
        last = None
        if not prefer_fallback:
            errors = 0
            limited_n = 0
            while True:
                try:
                    return self._google(text, src, dest), "google"
                except Exception as e:
                    last = e
                    msg = str(e).lower()
                    if "429" in msg or "too many" in msg or "rate" in msg:
                        if limited_n >= len(BACKOFF_429):
                            break
                        wait = BACKOFF_429[limited_n] * random.uniform(1.0, 1.25)
                        limited_n += 1
                        print("RATE_LIMITED backoff %ds" % wait, flush=True)
                        time.sleep(wait)
                    else:
                        errors += 1
                        if errors >= 3:
                            break
                        time.sleep(2 * errors)
        try:
            return self._mymemory(text, src, dest), "mymemory"
        except Exception as e:
            raise RuntimeError("translate failed (google: %s; mymemory: %s)" % (last, e))

    def close(self):
        try:
            c = getattr(self.gt, "client", None)
            if c is not None and hasattr(c, "aclose"):
                self.loop.run_until_complete(c.aclose())
        except Exception:
            pass


def verify_lang(text, dest):
    if not text or len(text.strip()) < 40:
        return True, "skip-short"
    try:
        from langdetect import detect
        got = detect(text[:2000]).lower()
        # Compare language families (zh-CN≈zh-cn, pt-BR≈pt, iw≈he, tl≈fil, nb≈no), not raw strings.
        return lang_family(got) == lang_family(dest), got
    except Exception as e:
        return True, "unverified:%s" % e


def load_state(path):
    try:
        return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None
    except Exception:
        return None


def save_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(str(path) + ".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, path)


def write_output(path, text, fmt):
    path.parent.mkdir(parents=True, exist_ok=True)
    if fmt == "docx":
        import docx
        d = docx.Document()
        for para in text.split("\n\n"):
            d.add_paragraph(para)
        d.save(str(path))
    else:
        path.write_text(text, encoding="utf-8")


def final_path(out_path):
    if out_path.suffix.lower() == "" or out_path.suffix.lower() in (".mobi", ".azw", ".azw3", ".epub", ".pdf"):
        return out_path.with_suffix(".txt")
    return out_path


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--src", default="auto")
    ap.add_argument("--dest", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--format", default=None)
    ap.add_argument("--no-resume", action="store_true")
    ap.add_argument("--estimate", action="store_true")
    a = ap.parse_args(argv)
    resume = not a.no_resume
    inp = Path(a.input)
    if not inp.is_file():
        return out({"ok": False, "error": "Input not found: %s" % inp})
    fmt = detect_fmt(inp, a.format)
    # Format readers are optional now (plain-text fallback); the translators are not.
    missing = need([] if a.estimate else ["googletrans", "deep_translator"])
    if missing:
        return out({"ok": False, "error": "Missing Python packages in Termux: %s. Install 'ebook-translate' in Settings > Tools." % ", ".join(missing)})
    out_path = Path(a.output)
    state_path = Path(str(out_path) + ".farrow-translate.json")
    try:
        chapters = extract(inp, fmt)
    except Exception as e:
        return out({"ok": False, "error": "extract %s: %s" % (type(e).__name__, e)})
    chunks = plan(chapters)
    if not chunks:
        return out({"ok": False, "error": "No text extracted from %s (%s)" % (inp.name, fmt)})
    state = load_state(state_path) if resume else None
    if not state or state.get("input") != str(inp) or state.get("dest") != a.dest or state.get("total") != len(chunks) \
            or state.get("chunk") != CHUNK:
        state = {"input": str(inp), "src": a.src, "dest": a.dest, "format": fmt, "chunk": CHUNK,
                 "total": len(chunks), "chapters": len(chapters), "done": 0, "parts": [], "engines": {}}
    start = int(state.get("done", 0))
    start_chapter = chunks[start][0] if start < len(chunks) else len(chapters)
    lo, mid, hi, pauses = estimate(len(chunks) - start, len(chapters), start_chapter)
    info = {"fallback": FALLBACK_USED[0] if FALLBACK_USED else None, "chapters": len(chapters), "chunks": len(chunks), "done": start, "remaining": len(chunks) - start,
            "chars": sum(len(c) for _, c in chunks), "max_chunk_chars": max(len(c) for _, c in chunks),
            "batch_pauses": pauses, "eta_seconds": mid, "eta_min_seconds": lo, "eta_max_seconds": hi,
            "settings": {"chunk_chars": CHUNK, "delay_s": list(DELAY), "backoff_429_s": list(BACKOFF_429), "batch_chapters": BATCH_CHAPTERS,
                         "batch_pause_s": list(BATCH_PAUSE), "engine": "googletrans>=4.0.2, fallback MyMemory"}}
    if a.estimate:
        return out(dict({"ok": True, "estimate": True, "format": fmt, "resuming": start > 0}, **info))
    print("ETA %ds (%d-%ds): %d chunks in %d chapters" % (mid, lo, hi, len(chunks) - start, len(chapters)), flush=True)
    eng = Engines()
    engines = state.get("engines") or {}
    chapters_in_batch = 0
    try:
        for i in range(start, len(chunks)):
            ci, text = chunks[i]
            translated, used = eng.translate(text, a.src, a.dest)
            ok, lang = verify_lang(translated, a.dest)
            if not ok:
                time.sleep(random.uniform(*DELAY))
                translated, used = eng.translate(text, a.src, a.dest, prefer_fallback=(used == "google"))
                ok, lang = verify_lang(translated, a.dest)
            engines[used] = engines.get(used, 0) + 1
            state["parts"].append([ci, translated])
            state["done"] = i + 1
            state["engines"] = engines
            state["last_lang"] = lang
            save_state(state_path, state)
            print("PROGRESS %d/%d chapter %d/%d" % (i + 1, len(chunks), ci + 1, len(chapters)), flush=True)
            if i + 1 >= len(chunks):
                break
            if chunks[i + 1][0] != ci:
                chapters_in_batch += 1
                if chapters_in_batch >= BATCH_CHAPTERS:
                    chapters_in_batch = 0
                    time.sleep(random.uniform(*BATCH_PAUSE))
                    continue
            time.sleep(random.uniform(*DELAY))
        eng.close()
        # Chunks of one chapter join with a blank line; chapters with a wider gap.
        by_ch = {}
        for ci, t in state["parts"]:
            by_ch.setdefault(ci, []).append(t)
        final = "\n\n\n".join("\n\n".join(by_ch[k]) for k in sorted(by_ch))
        dst = final_path(out_path)
        try:
            write_output(dst, final, "docx" if dst.suffix.lower() == ".docx" else "txt")
        except Exception:
            dst = dst.with_suffix(".txt")   # plain-text fallback when python-docx is missing/broken
            write_output(dst, final, "txt")
        try:
            state_path.unlink()
        except Exception:
            pass
        return out({"ok": True, "input": str(inp), "output": str(dst), "format": fmt, "chapters": len(chapters),
                    "fallback": FALLBACK_USED[0] if FALLBACK_USED else None, "chunks": len(chunks), "src": a.src, "dest": a.dest, "engines": engines,
                    "chars_out": len(final)})
    except Exception as e:
        eng.close()
        save_state(state_path, state)
        return out({"ok": False, "error": "%s: %s" % (type(e).__name__, e), "resumable": True,
                    "state": str(state_path), "done": state.get("done"), "total": state.get("total")})


if __name__ == "__main__":
    sys.exit(main())
""".trimStart()

    /** Python module + pip name per input format; PDF uses PyMuPDF when present, else poppler's pdftotext (apt). */
    private val FORMAT_DEPS = mapOf(
        "mobi" to ("mobi" to "mobi"), "epub" to ("ebooklib" to "ebooklib"), "docx" to ("docx" to "python-docx"),
    )
    const val PIP_CORE = "'googletrans>=4.0.2' deep-translator langdetect"

    /**
     * First-run install inside Termux, only after the user agreed (`allowInstall`; otherwise [probeCommand]) (idempotent, quick when everything is present): python/pip via apt, then
     * pip `googletrans>=4.0.2` (upgrades an older googletrans), deep-translator, langdetect and the format's reader.
     * PDF installs poppler (pdftotext) unless PyMuPDF is already there (it rarely builds on Termux). Progress → stderr.
     */
    fun setupCommand(format: String?, allowInstall: Boolean = false): String {
        if (!allowInstall) return probeCommand(format)
        val dep = FORMAT_DEPS[format]
        val mods = listOfNotNull("googletrans", "deep_translator", "langdetect", dep?.first).joinToString(",")
        val pipExtra = dep?.second?.let { " $it" } ?: ""
        val pdf = if (format == "pdf") "\nif ! python3 -c 'import fitz' >/dev/null 2>&1 && ! command -v pdftotext >/dev/null 2>&1; then " +
            "echo 'Farrow: installing poppler (pdftotext)' >&2; timeout 600 apt-get -y install poppler >/dev/null 2>&1; fi" else ""
        return """
export DEBIAN_FRONTEND=noninteractive PIP_DISABLE_PIP_VERSION_CHECK=1
if ! command -v python3 >/dev/null 2>&1; then
  echo 'Farrow: installing python (first ebook_translate run)' >&2
  timeout 900 apt-get -y install python python-pip >/dev/null 2>&1 || timeout 900 pkg install -y python python-pip >/dev/null 2>&1
fi
farrow_gt_ok() { python3 -c 'import importlib.metadata as m,re,sys; v=m.version("googletrans"); n=[int(x) for x in re.findall(r"\d+",v)[:3]]+[0,0,0]; sys.exit(0 if n[:3]>=[4,0,2] else 1)' >/dev/null 2>&1; }
if ! python3 -c 'import $mods' >/dev/null 2>&1 || ! farrow_gt_ok; then
  echo 'Farrow: installing Python packages for ebook_translate (first run, a few minutes)' >&2
  timeout 900 pip install -q -U $PIP_CORE$pipExtra 2>&1 | tail -n 5 >&2
fi$pdf
""".trim()
    }

    private const val GT_OK = "farrow_gt_ok() { python3 -c 'import importlib.metadata as m,re,sys; v=m.version(\"googletrans\"); " +
        "n=[int(x) for x in re.findall(r\"\\d+\",v)[:3]]+[0,0,0]; sys.exit(0 if n[:3]>=[4,0,2] else 1)' >/dev/null 2>&1; }"

    /**
     * Without the user's OK nothing is installed: lists what [setupCommand] would install for [format]
     * (`needs_install`, exit 5) so ebook_translate can ask first (agent install consent).
     */
    fun probeCommand(format: String?): String {
        val pips = listOf("googletrans" to "googletrans>=4.0.2", "deep_translator" to "deep-translator", "langdetect" to "langdetect") +
            listOfNotNull(FORMAT_DEPS[format])
        val checks = pips.joinToString("\n") { (mod, pip) ->
            val ok = if (mod == "googletrans") "farrow_py 'import googletrans' && farrow_gt_ok" else "farrow_py 'import $mod'"
            "$ok || farrow_m=\"${'$'}farrow_m pip:$pip\""
        }
        val pdf = if (format == "pdf") "\nfarrow_py 'import fitz' || command -v pdftotext >/dev/null 2>&1 || farrow_m=\"${'$'}farrow_m apt:poppler\"" else ""
        return "farrow_m=\"\"\n" +
            "command -v python3 >/dev/null 2>&1 || farrow_m=\"apt:python apt:python-pip\"\n" +
            "farrow_py() { command -v python3 >/dev/null 2>&1 && python3 -c \"${'$'}1\" >/dev/null 2>&1; }\n" +
            GT_OK + "\n" + checks + pdf + "\n" + com.farrow.app.agent.tools.InstallConsent.probeExit(MARKER)
    }

    /** Writes the script into Termux and fails loudly (FARROW_JSON error, exit 4) if it isn't there afterwards. */
    fun installCommand(): String =
        "mkdir -p '$DIR' && echo " + Base64.getEncoder().encodeToString(SOURCE.toByteArray()) + " | base64 -d > '$FILE'\n" +
            "if [ ! -s '$FILE' ]; then echo '$MARKER{\"ok\":false,\"error\":\"Could not write $FILE in Termux\"}'; exit 4; fi"
}
