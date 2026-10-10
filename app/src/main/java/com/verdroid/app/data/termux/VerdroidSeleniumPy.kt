package com.verdroid.app.data.termux

import java.util.Base64

/**
 * The Python helper Farrow drops into Termux at `~/.farrow/farrow_selenium.py` before every selenium_* / termux_python
 * call (headless Chromium through chromedriver). It is importable from the agent's own scrapers
 * (`from farrow_selenium import make_driver`) and is also the CLI behind the selenium_* tools; it prints one
 * `FARROW_JSON=<json>` line.
 */
object VerdroidSeleniumPy {
    /** Absolute (quoted "~/…" arguments are not tilde-expanded). */
    const val DIR = TermuxManager.TERMUX_HOME + "/.farrow"
    const val FILE = "$DIR/farrow_selenium.py"
    const val MARKER = "FARROW_JSON="

    val SOURCE = """
# Farrow headless Chromium helper (written by the Farrow app; changes are overwritten).
import argparse, json, os, shutil, sys, time


def make_driver(width=1366, height=900, user_agent=None):
    # Headless Chromium (Termux TUR package) via chromedriver; English UI.
    from selenium import webdriver
    from selenium.webdriver.chrome.service import Service
    opts = webdriver.ChromeOptions()
    binary = shutil.which("chromium-browser") or shutil.which("chromium")
    if binary:
        opts.binary_location = binary
    for a in ("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
              "--window-size=%d,%d" % (width, height), "--lang=en-US", "--mute-audio",
              "--disable-blink-features=AutomationControlled"):
        opts.add_argument(a)
    if user_agent:
        opts.add_argument("--user-agent=" + user_agent)
    opts.add_experimental_option("prefs", {"intl.accept_languages": "en-US,en"})
    driver_path = shutil.which("chromedriver")
    service = Service(driver_path) if driver_path else Service()
    drv = webdriver.Chrome(service=service, options=opts)
    drv.set_page_load_timeout(60)
    return drv


def load(drv, url, wait=0.0, wait_for=None):
    drv.get(url)
    if wait_for:
        from selenium.webdriver.common.by import By
        from selenium.webdriver.support import expected_conditions as EC
        from selenium.webdriver.support.ui import WebDriverWait
        WebDriverWait(drv, max(wait, 1.0)).until(EC.presence_of_element_located((By.CSS_SELECTOR, wait_for)))
    elif wait > 0:
        time.sleep(wait)


def _save(path, data, binary=False):
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "wb" if binary else "w", **({} if binary else {"encoding": "utf-8"})) as f:
        f.write(data)
    return os.path.getsize(path)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="farrow_selenium")
    ap.add_argument("cmd", choices=["open", "source", "shot"])
    ap.add_argument("url")
    ap.add_argument("--wait", type=float, default=2.0)
    ap.add_argument("--wait-for", default=None)
    ap.add_argument("--max-chars", type=int, default=12000)
    ap.add_argument("--max-links", type=int, default=60)
    ap.add_argument("--save", default=None)
    ap.add_argument("--width", type=int, default=1366)
    ap.add_argument("--height", type=int, default=900)
    ap.add_argument("--full", action="store_true")
    a = ap.parse_args(argv)
    out = {"ok": True}
    drv = None
    try:
        drv = make_driver(a.width, a.height)
        load(drv, a.url, a.wait, a.wait_for)
        out["url"] = drv.current_url
        out["title"] = drv.title
        if a.cmd == "open":
            text = drv.execute_script("return document.body ? document.body.innerText : '';") or ""
            out["text_chars"] = len(text)
            out["truncated"] = len(text) > a.max_chars
            out["text"] = text[: a.max_chars]
            links = drv.execute_script(
                "return Array.from(document.querySelectorAll('a[href]')).slice(0, 1000)"
                ".map(function(e){return [(e.innerText||'').trim().slice(0,120), e.href];});") or []
            seen, kept = set(), []
            for t, h in links:
                if h and h.startswith("http") and h not in seen:
                    seen.add(h)
                    kept.append({"text": t, "url": h})
            out["links"] = kept[: a.max_links]
            if a.save:
                out["saved"] = a.save
                out["bytes"] = _save(a.save, drv.page_source)
        elif a.cmd == "source":
            html = drv.page_source or ""
            out["html_chars"] = len(html)
            out["truncated"] = len(html) > a.max_chars
            out["html"] = html[: a.max_chars]
            if a.save:
                out["saved"] = a.save
                out["bytes"] = _save(a.save, html)
        else:
            if a.full:
                h = drv.execute_script("return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);") or a.height
                drv.set_window_size(a.width, min(int(h), 12000))
                time.sleep(0.5)
            png = drv.get_screenshot_as_png()
            out["saved"] = a.save
            out["bytes"] = _save(a.save, png, binary=True)
    except ImportError as e:
        out = {"ok": False, "error": "Selenium is not installed in Termux (%s). Install 'chromium-selenium' in Farrow Settings > Tools." % e}
    except Exception as e:
        out = {"ok": False, "error": ("%s: %s" % (type(e).__name__, e))[:2000]}
    finally:
        if drv is not None:
            try:
                drv.quit()
            except Exception:
                pass
    print("$MARKER" + json.dumps(out, ensure_ascii=False))
    return 0 if out.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
""".trimStart()

    /** Shell that (re)writes the helper into Termux. */
    fun installCommand(): String =
        "mkdir -p '$DIR' && echo " + Base64.getEncoder().encodeToString(SOURCE.toByteArray()) + " | base64 -d > '$FILE'"
}
