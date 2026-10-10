package com.farrow.app.data.pdf

import java.util.Base64

/**
 * Python PDF helper dropped into Termux at `~/.farrow/farrow_pdf.py` before each pdf_* call (absolute path, never a
 * quoted "~/…"). Backend: PyMuPDF (Termux apt package `python-pymupdf`) when importable, else pypdf (pip, pure Python)
 * with poppler's pdftotext for text. Commands: info, text, pages, merge, annotate; one `FARROW_JSON=` result line.
 */
object FarrowPdfPy {
    const val DIR = com.farrow.app.data.termux.TermuxManager.TERMUX_HOME + "/.farrow"
    const val FILE = "$DIR/farrow_pdf.py"
    /** Set when apt could not install python-pymupdf, so later calls don't retry it every time (pypdf is used). */
    const val NO_PYMUPDF_FLAG = "$DIR/.pymupdf_unavailable"
    const val MARKER = "FARROW_JSON="

    val SOURCE = """
# Farrow PDF helper (written by the Farrow app; changes are overwritten).
# Backend: PyMuPDF (Termux apt package python-pymupdf) when importable, else pypdf (pip) + poppler pdftotext for text.
# Commands: info, text, pages, merge, annotate. Prints one FARROW_JSON=<json> line.
import argparse, json, os, re, shutil, subprocess, sys

MARKER = "FARROW_JSON="


def out(obj):
    print(MARKER + json.dumps(obj, ensure_ascii=False), flush=True)
    return 0 if obj.get("ok") else 1


def load_backend():
    try:
        import pymupdf as m
        return "pymupdf", m
    except ImportError:
        pass
    try:
        import fitz as m
        if hasattr(m, "open"):
            return "pymupdf", m
    except ImportError:
        pass
    try:
        import pypdf as m
        return "pypdf", m
    except ImportError:
        return None, None


def parse_pages(spec, count):
    # "1-3,5,7-" / "last" / "all" (1-based, inclusive) -> list of 0-based indexes (order kept, duplicates allowed).
    spec = (spec or "all").strip().lower()
    if spec in ("", "all"):
        return list(range(count))
    res = []
    for tok in spec.split(","):
        tok = tok.strip().replace("last", str(count)).replace("end", str(count))
        if not tok:
            continue
        m = re.fullmatch(r"(\d+)(?:\s*-\s*(\d*))?", tok)
        if not m:
            raise ValueError("bad page range: " + tok)
        a = int(m.group(1))
        b = a if m.group(2) is None else (int(m.group(2)) if m.group(2) else count)
        if a < 1 or b < a:
            raise ValueError("bad page range: " + tok)
        if a > count:
            raise ValueError("page %d is past the end (the PDF has %d pages)" % (a, count))
        res.extend(range(a - 1, min(b, count)))
    if not res:
        raise ValueError("no pages selected")
    return res


def open_doc(kind, m, path, password=None):
    if kind == "pymupdf":
        d = m.open(path)
        if d.needs_pass and not d.authenticate(password or ""):
            raise ValueError("PDF is encrypted: pass password")
        return d
    r = m.PdfReader(path)
    if r.is_encrypted and not r.decrypt(password or ""):
        raise ValueError("PDF is encrypted: pass password")
    return r


def page_count(kind, d):
    return d.page_count if kind == "pymupdf" else len(d.pages)


def ensure_parent(p):
    os.makedirs(os.path.dirname(os.path.abspath(p)), exist_ok=True)


def poppler_text(path, idx, password=None):
    # One pdftotext call for the span, split on form feeds; None if poppler is missing or fails.
    exe = shutil.which("pdftotext")
    if not exe or not idx:
        return None
    lo, hi = min(idx) + 1, max(idx) + 1
    cmd = [exe, "-q", "-enc", "UTF-8", "-f", str(lo), "-l", str(hi)]
    if password:
        cmd += ["-upw", password]
    try:
        raw = subprocess.run(cmd + [path, "-"], capture_output=True, timeout=600).stdout.decode("utf-8", "replace")
    except Exception:
        return None
    parts = raw.split("\f")
    by = {lo - 1 + i: t for i, t in enumerate(parts)}
    return [by.get(i, "") for i in idx]


def page_texts(kind, m, d, path, idx, password=None):
    if kind == "pymupdf":
        return [d[i].get_text("text") for i in idx]
    t = poppler_text(path, idx, password)
    if t is not None and any(x.strip() for x in t):
        return t
    return [(d.pages[i].extract_text() or "") for i in idx]


def clean(t):
    t = t.replace("\r", "")
    t = re.sub(r"[ \t]+\n", "\n", t)
    return re.sub(r"\n{3,}", "\n\n", t).strip()


def cmd_info(kind, m, a):
    d = open_doc(kind, m, a.input, a.password)
    n = page_count(kind, d)
    if kind == "pymupdf":
        meta = {k: v for k, v in (d.metadata or {}).items() if v}
        first = d[0].rect if n else None
        size = [round(first.width), round(first.height)] if first else None
        toc = [[lvl, title, page] for lvl, title, page in d.get_toc()[:50]]
        sample = "".join(d[i].get_text("text") for i in range(min(n, 3)))
    else:
        meta = {str(k).lstrip("/").lower(): str(v) for k, v in (d.metadata or {}).items() if v}
        box = d.pages[0].mediabox if n else None
        size = [round(float(box.width)), round(float(box.height))] if box else None
        toc = []
        sample = "".join(page_texts(kind, m, d, a.input, list(range(min(n, 3))), a.password))
    return out({"ok": True, "backend": kind, "file": a.input, "pages": n, "bytes": os.path.getsize(a.input),
                "metadata": meta, "page_size_pt": size, "toc": toc,
                "has_text": bool(sample.strip()),
                "note": "" if sample.strip() or not n else "No text layer on the first pages (scanned PDF?); text extraction will be empty without OCR."})


def cmd_text(kind, m, a):
    d = open_doc(kind, m, a.input, a.password)
    n = page_count(kind, d)
    idx = parse_pages(a.pages, n)
    texts = [clean(t) for t in page_texts(kind, m, d, a.input, idx, a.password)]
    saved = None
    if a.save:
        ensure_parent(a.save)
        with open(a.save, "w", encoding="utf-8") as f:
            for i, t in zip(idx, texts):
                f.write("--- Page %d ---\n%s\n\n" % (i + 1, t))
        saved = a.save
    budget, used, pages, next_page, truncated = max(200, a.max_chars), 0, [], None, False
    for i, t in zip(idx, texts):
        if used >= budget:
            next_page, truncated = i + 1, True
            break
        room = budget - used
        if len(t) > room:
            pages.append({"page": i + 1, "text": t[:room], "cut": True})
            used = budget
            truncated = True
            pos = idx.index(i)
            next_page = idx[pos + 1] + 1 if pos + 1 < len(idx) else None
            break
        pages.append({"page": i + 1, "text": t})
        used += len(t)
    total = sum(len(t) for t in texts)
    return out({"ok": True, "backend": kind, "file": a.input, "page_count": n, "selected_pages": len(idx),
                "returned_pages": len(pages), "chars": used, "total_chars": total, "truncated": truncated,
                "next_page": next_page, "saved": saved, "pages": pages,
                "empty": total == 0})


def new_doc(kind, m):
    return m.open() if kind == "pymupdf" else m.PdfWriter()


def save_doc(kind, doc, path):
    ensure_parent(path)
    tmp = path + ".part"
    if kind == "pymupdf":
        doc.save(tmp, garbage=3, deflate=True)
    else:
        with open(tmp, "wb") as f:
            doc.write(f)
    os.replace(tmp, path)
    return os.path.getsize(path)


def cmd_pages(kind, m, a):
    d = open_doc(kind, m, a.input, a.password)
    idx = parse_pages(a.pages, page_count(kind, d))
    w = new_doc(kind, m)
    for i in idx:
        if kind == "pymupdf":
            w.insert_pdf(d, from_page=i, to_page=i)
            if a.rotate:
                p = w[w.page_count - 1]
                p.set_rotation((p.rotation + a.rotate) % 360)
        else:
            p = w.add_page(d.pages[i])
            if a.rotate:
                p.rotate(a.rotate)
    size = save_doc(kind, w, a.out)
    return out({"ok": True, "backend": kind, "output": a.out, "pages": len(idx), "bytes": size})


def cmd_merge(kind, m, a):
    w = new_doc(kind, m)
    total = 0
    for p in a.inputs:
        d = open_doc(kind, m, p, a.password)
        if kind == "pymupdf":
            w.insert_pdf(d)
        else:
            for pg in d.pages:
                w.add_page(pg)
        total += page_count(kind, d)
    size = save_doc(kind, w, a.out)
    return out({"ok": True, "backend": kind, "output": a.out, "inputs": len(a.inputs), "pages": total, "bytes": size})


def cmd_annotate(kind, m, a):
    d = open_doc(kind, m, a.input, a.password)
    n = page_count(kind, d)
    if a.page < 1 or a.page > n:
        raise ValueError("page must be 1..%d" % n)
    i = a.page - 1
    if kind == "pymupdf":
        pg = d[i]
        r = pg.rect
        x, y = a.x if a.x is not None else 36, a.y if a.y is not None else 36
        if a.mode == "note":
            pg.add_text_annot(m.Point(x, y), a.text)
        else:
            box = m.Rect(x, y, r.width - 36, r.height - 18)
            rc = pg.insert_textbox(box, a.text, fontsize=a.font_size, fontname="helv", color=(0, 0, 0))
            if rc < 0:
                pg.insert_text(m.Point(x, y + a.font_size), a.text, fontsize=a.font_size, fontname="helv")
        doc = d
    else:
        from pypdf.annotations import FreeText, Text
        w = m.PdfWriter(clone_from=d)
        box = w.pages[i].mediabox
        h, wd = float(box.height), float(box.width)
        x, y = a.x if a.x is not None else 36, a.y if a.y is not None else 36
        # pypdf uses PDF coordinates (origin bottom-left); convert from top-left like PyMuPDF.
        top = h - y
        if a.mode == "note":
            ann = Text(rect=(x, top - 20, x + 20, top), text=a.text, open=False)
        else:
            lines = max(1, a.text.count("\n") + 1 + len(a.text) // 90)
            ann = FreeText(text=a.text, rect=(x, max(0, top - lines * a.font_size * 1.4 - 4), wd - 36, top),
                           font="Helvetica", font_size="%dpt" % a.font_size, font_color="000000",
                           border_color=None, background_color=None)
        w.add_annotation(page_number=i, annotation=ann)
        doc = w
    size = save_doc(kind, doc, a.out)
    return out({"ok": True, "backend": kind, "output": a.out, "page": a.page, "mode": a.mode, "bytes": size})


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("info", "text", "pages", "annotate"):
        s = sub.add_parser(name)
        s.add_argument("input")
        s.add_argument("--password")
        if name == "text":
            s.add_argument("--pages", default="all")
            s.add_argument("--max-chars", type=int, default=20000)
            s.add_argument("--save")
        if name == "pages":
            s.add_argument("--pages", required=True)
            s.add_argument("--rotate", type=int, default=0, choices=[0, 90, 180, 270])
            s.add_argument("--out", required=True)
        if name == "annotate":
            s.add_argument("--page", type=int, default=1)
            s.add_argument("--text", required=True)
            s.add_argument("--mode", choices=["text", "note"], default="text")
            s.add_argument("--x", type=float)
            s.add_argument("--y", type=float)
            s.add_argument("--font-size", type=int, default=11)
            s.add_argument("--out", required=True)
    s = sub.add_parser("merge")
    s.add_argument("inputs", nargs="+")
    s.add_argument("--password")
    s.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    kind, m = load_backend()
    if kind is None:
        return out({"ok": False, "error": "Missing Python PDF library: install python-pymupdf (apt) or pypdf (pip) in Termux"})
    for p in ([a.input] if hasattr(a, "input") else a.inputs):
        if not os.path.isfile(p):
            return out({"ok": False, "error": "File not found (from Termux): " + p +
                        " - if it exists, run termux-setup-storage in Termux once"})
    try:
        return {"info": cmd_info, "text": cmd_text, "pages": cmd_pages, "merge": cmd_merge, "annotate": cmd_annotate}[a.cmd](kind, m, a)
    except Exception as e:
        return out({"ok": False, "backend": kind, "error": "%s: %s" % (type(e).__name__, e)})


if __name__ == "__main__":
    sys.exit(main())
""".trimStart()

    /**
     * First-use auto-install inside Termux (idempotent, instant when present): python via apt, then the apt package
     * python-pymupdf (prebuilt by Termux; `pip install pymupdf` has no Android wheel and rarely builds). If that fails,
     * a flag file stops later retries and pypdf (pip) + poppler (pdftotext) are installed instead. Progress → stderr.
     */
    fun setupCommand(): String = """
export DEBIAN_FRONTEND=noninteractive PIP_DISABLE_PIP_VERSION_CHECK=1
if ! command -v python3 >/dev/null 2>&1; then
  echo 'Farrow: installing python (first pdf_* run)' >&2
  timeout 900 apt-get -y install python python-pip >/dev/null 2>&1 || timeout 900 pkg install -y python python-pip >/dev/null 2>&1
fi
farrow_has_mupdf() { python3 -c 'import pymupdf' >/dev/null 2>&1; }
if ! farrow_has_mupdf && [ ! -e '$NO_PYMUPDF_FLAG' ]; then
  echo 'Farrow: installing python-pymupdf (first pdf_* run, a few minutes)' >&2
  timeout 600 apt-get -y install python-pymupdf >/dev/null 2>&1 || { timeout 120 apt-get update -q >/dev/null 2>&1; timeout 600 apt-get -y install python-pymupdf >/dev/null 2>&1; }
  farrow_has_mupdf || { mkdir -p '$DIR'; touch '$NO_PYMUPDF_FLAG'; }
fi
if ! farrow_has_mupdf; then
  python3 -c 'import pypdf' >/dev/null 2>&1 || { echo 'Farrow: installing pypdf (fallback PDF library)' >&2; timeout 300 pip install -q -U pypdf 2>&1 | tail -n 5 >&2; }
  command -v pdftotext >/dev/null 2>&1 || { echo 'Farrow: installing poppler (pdftotext)' >&2; timeout 300 apt-get -y install poppler >/dev/null 2>&1; }
fi
""".trim()

    /** Writes the helper into Termux and fails loudly (FARROW_JSON error, exit 4) if it isn't there afterwards. */
    fun installCommand(): String =
        "mkdir -p '$DIR' && echo " + Base64.getEncoder().encodeToString(SOURCE.toByteArray()) + " | base64 -d > '$FILE'\n" +
            "if [ ! -s '$FILE' ]; then echo '$MARKER{\"ok\":false,\"error\":\"Could not write $FILE in Termux\"}'; exit 4; fi"
}
