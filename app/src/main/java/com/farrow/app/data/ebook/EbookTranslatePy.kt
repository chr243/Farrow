package com.farrow.app.data.ebook

import java.util.Base64

/**
 * Python ebook translator dropped into Termux at `~/.farrow/farrow_ebook_translate.py` before each run.
 * MOBI first (mobi), then EPUB/PDF/DOCX; chunked Google Translate with MyMemory fallback (deep-translator),
 * language check, resume via a sidecar `.farrow-translate.json`, output under Documents/Farrow/Output.
 */
object EbookTranslatePy {
    const val DIR = "~/.farrow"
    const val FILE = "$DIR/farrow_ebook_translate.py"
    const val MARKER = "FARROW_JSON="

    val SOURCE = """
# Farrow ebook translator (written by the Farrow app; changes are overwritten).
# Formats: MOBI (preferred), EPUB, PDF, DOCX, TXT/MD. Engines: Google → MyMemory fallback.
import argparse, json, os, re, sys, time, zipfile
from pathlib import Path

CHUNK = 4200
PAUSE = 0.35
MARKER = "FARROW_JSON="


def out(obj):
    print(MARKER + json.dumps(obj, ensure_ascii=False))
    return 0 if obj.get("ok") else 1


def need(mods):
    missing = []
    for m in mods:
        try:
            __import__(m.split(".")[0])
        except ImportError:
            missing.append(m)
    return missing


def detect_fmt(path: Path, forced=None):
    if forced:
        return forced.lower()
    ext = path.suffix.lower().lstrip(".")
    return {"mobi": "mobi", "azw": "mobi", "azw3": "mobi", "epub": "epub", "pdf": "pdf",
            "docx": "docx", "txt": "txt", "md": "txt", "markdown": "txt"}.get(ext, ext)


def extract_mobi(path: Path):
    # MOBI first: unpack with `mobi`, then read HTML/text from the extracted tree.
    import mobi, tempfile, shutil
    tmp = Path(tempfile.mkdtemp(prefix="farrow-mobi-"))
    try:
        extracted = Path(mobi.extract(str(path))[1])
        texts = []
        for p in sorted(extracted.rglob("*")):
            if p.suffix.lower() in (".html", ".htm", ".xhtml", ".txt"):
                try:
                    t = p.read_text(encoding="utf-8", errors="ignore")
                except Exception:
                    continue
                t = re.sub(r"(?is)<script.*?>.*?</script>", " ", t)
                t = re.sub(r"(?is)<style.*?>.*?</style>", " ", t)
                t = re.sub(r"(?is)<[^>]+>", "\n", t)
                t = re.sub(r"\n{3,}", "\n\n", t).strip()
                if t:
                    texts.append(t)
        return "\n\n".join(texts)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def extract_epub(path: Path):
    from ebooklib import epub
    from ebooklib import ITEM_DOCUMENT
    book = epub.read_epub(str(path))
    parts = []
    for item in book.get_items_of_type(ITEM_DOCUMENT):
        raw = item.get_content().decode("utf-8", "ignore")
        raw = re.sub(r"(?is)<script.*?>.*?</script>", " ", raw)
        raw = re.sub(r"(?is)<style.*?>.*?</style>", " ", raw)
        raw = re.sub(r"(?is)<[^>]+>", "\n", raw)
        raw = re.sub(r"\n{3,}", "\n\n", raw).strip()
        if raw:
            parts.append(raw)
    return "\n\n".join(parts)


def extract_pdf(path: Path):
    import fitz
    doc = fitz.open(str(path))
    return "\n\n".join(page.get_text("text") for page in doc)


def extract_docx(path: Path):
    import docx
    d = docx.Document(str(path))
    return "\n\n".join(p.text for p in d.paragraphs if p.text.strip())


def extract_txt(path: Path):
    return path.read_text(encoding="utf-8", errors="ignore")


def extract(path: Path, fmt: str):
    if fmt == "mobi":
        return extract_mobi(path)
    if fmt == "epub":
        return extract_epub(path)
    if fmt == "pdf":
        return extract_pdf(path)
    if fmt == "docx":
        return extract_docx(path)
    if fmt == "txt":
        return extract_txt(path)
    raise ValueError("Unsupported format: %s (use mobi, epub, pdf, docx, txt)" % fmt)


def chunk_text(text: str, size=CHUNK):
    text = text.replace("\r\n", "\n").strip()
    if not text:
        return []
    parts, buf = [], []
    n = 0
    for para in re.split(r"\n\s*\n", text):
        para = para.strip()
        if not para:
            continue
        if n + len(para) + 2 > size and buf:
            parts.append("\n\n".join(buf))
            buf, n = [], 0
        if len(para) > size:
            if buf:
                parts.append("\n\n".join(buf)); buf, n = [], 0
            for i in range(0, len(para), size):
                parts.append(para[i:i + size])
        else:
            buf.append(para); n += len(para) + 2
    if buf:
        parts.append("\n\n".join(buf))
    return parts


def translate_chunk(text, src, dest):
    from deep_translator import GoogleTranslator, MyMemoryTranslator
    last = None
    for attempt in range(3):
        try:
            kwargs = {"source": "auto" if src in ("", "auto") else src, "target": dest}
            return GoogleTranslator(**kwargs).translate(text), "google"
        except Exception as e:
            last = e
            time.sleep(0.8 * (attempt + 1))
    try:
        # MyMemory wants language codes; "auto" → "en" guess for source when needed.
        s = "en" if src in ("", "auto") else src
        return MyMemoryTranslator(source=s, target=dest).translate(text), "mymemory"
    except Exception as e:
        raise RuntimeError("translate failed (google: %s; mymemory: %s)" % (last, e))


def verify_lang(text, dest):
    if not text or len(text.strip()) < 12:
        return True, "skip-short"
    try:
        from deep_translator import single_detection
        # deep-translator may not expose detection; fall back to langdetect.
    except Exception:
        pass
    try:
        from langdetect import detect
        got = detect(text[:2000])
        # Accept close matches (zh-cn/zh, pt-br/pt).
        ok = got == dest or got.startswith(dest.split("-")[0]) or dest.startswith(got.split("-")[0])
        return ok, got
    except Exception as e:
        return True, "unverified:%s" % e


def load_state(path: Path):
    if path.is_file():
        return json.loads(path.read_text(encoding="utf-8"))
    return None


def save_state(path: Path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def write_output(path: Path, text: str, fmt: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    if fmt == "docx":
        import docx
        d = docx.Document()
        for para in text.split("\n\n"):
            d.add_paragraph(para)
        d.save(str(path))
    else:
        # MOBI/EPUB/PDF → UTF-8 text (faithful plain-text translation); TXT/MD keep extension.
        path.write_text(text, encoding="utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--src", default="auto")
    ap.add_argument("--dest", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--format", default=None)
    ap.add_argument("--resume", action="store_true", default=True)
    ap.add_argument("--no-resume", action="store_true")
    a = ap.parse_args(argv)
    resume = not a.no_resume
    inp = Path(a.input)
    if not inp.is_file():
        return out({"ok": False, "error": "Input not found: %s" % inp})
    fmt = detect_fmt(inp, a.format)
    missing = need({
        "mobi": ["mobi"], "epub": ["ebooklib"], "pdf": ["fitz"], "docx": ["docx"], "txt": [],
    }.get(fmt, []) + ["deep_translator"])
    if missing:
        return out({"ok": False, "error": "Missing Python packages in Termux: %s. Install 'ebook-translate' in Settings > Tools." % ", ".join(missing)})
    out_path = Path(a.output)
    state_path = Path(str(out_path) + ".farrow-translate.json")
    try:
        text = extract(inp, fmt)
    except Exception as e:
        return out({"ok": False, "error": "extract %s: %s" % (type(e).__name__, e)})
    chunks = chunk_text(text)
    if not chunks:
        return out({"ok": False, "error": "No text extracted from %s (%s)" % (inp.name, fmt)})
    state = load_state(state_path) if resume else None
    if not state or state.get("input") != str(inp) or state.get("dest") != a.dest:
        state = {"input": str(inp), "src": a.src, "dest": a.dest, "format": fmt, "total": len(chunks), "done": 0, "parts": [], "engines": {}}
    start = int(state.get("done", 0))
    engines = state.get("engines") or {}
    try:
        for i in range(start, len(chunks)):
            translated, eng = translate_chunk(chunks[i], a.src, a.dest)
            ok, lang = verify_lang(translated, a.dest)
            if not ok:
                # One retry through the other engine path by forcing a second call after a pause.
                time.sleep(1.0)
                translated, eng = translate_chunk(chunks[i], a.src, a.dest)
                ok, lang = verify_lang(translated, a.dest)
            engines[eng] = engines.get(eng, 0) + 1
            state["parts"].append(translated)
            state["done"] = i + 1
            state["engines"] = engines
            state["last_lang"] = lang
            save_state(state_path, state)
            time.sleep(PAUSE)
            if (i + 1) % 5 == 0 or i + 1 == len(chunks):
                print("PROGRESS %d/%d" % (i + 1, len(chunks)), flush=True)
        final = "\n\n".join(state["parts"])
        # Prefer keeping a clear deliverable name: book.fr.txt (or .docx when source was docx).
        if out_path.suffix.lower() == "" or out_path.suffix.lower() in (".mobi", ".azw", ".azw3", ".epub", ".pdf"):
            out_path = out_path.with_suffix(".txt")
        write_fmt = "docx" if out_path.suffix.lower() == ".docx" else "txt"
        write_output(out_path, final, write_fmt)
        try:
            state_path.unlink()
        except Exception:
            pass
        return out({
            "ok": True, "input": str(inp), "output": str(out_path), "format": fmt,
            "chunks": len(chunks), "src": a.src, "dest": a.dest, "engines": engines,
            "chars_in": len(text), "chars_out": len(final),
        })
    except Exception as e:
        save_state(state_path, state)
        return out({"ok": False, "error": "%s: %s" % (type(e).__name__, e), "resumable": True,
                    "state": str(state_path), "done": state.get("done"), "total": state.get("total")})


if __name__ == "__main__":
    sys.exit(main())
""".trimStart()

    fun installCommand(): String =
        "mkdir -p $DIR && echo " + Base64.getEncoder().encodeToString(SOURCE.toByteArray()) + " | base64 -d > $FILE"
}
