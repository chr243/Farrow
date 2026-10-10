# Verdroid

**An on-device AI agent for Android that actually does things:** it searches the web and fetches pages and APIs over HTTP, runs bash and Python in Termux (optionally with headless Chromium + Selenium), runs shell commands through Shizuku or rish, drives apps with Accessibility, translates ebooks, works with files in `Documents/Verdroid` and Git, and reads crypto market data, all on **free OpenRouter / Kilo models** with automatic model and key fallback and rate-limit recovery. No server and no cloud backend: your keys stay encrypted on your phone.

## Features

- **Messenger-style chat UI** (Kotlin, Jetpack Compose, Material 3) with chat heads / Android Bubbles and a quota indicator. Chat avatars are circles in shades of the theme green (lighter/darker per chat, theme-aware light/dark). Tool calls show as expandable cards; consecutive shell calls (`termux_run`, `termux_python`, `rish_run`, `run_shell`) stack into one expandable "termux_run ×N" row.
- **Free-model agent loop:** tool calling on OpenRouter free models and Kilo, priority list, automatic fallback across models and API keys, backoff and auto-resume after rate limits.
- **Web search (default):** `web_search`, a keyless multi-engine search ported from [hec-ovi/websearch-skill](https://github.com/hec-ovi/websearch-skill) (MIT): DuckDuckGo (html, with lite fallback), Brave, Bing, Mojeek, Yahoo and Wikipedia queried in parallel over plain HTTP, redirect links unwrapped (`duckduckgo.com/l/?uddg=`, Bing `ck/a`, Yahoo `/RU=`), URLs canonicalised and deduplicated, then ranked with de-correlated reciprocal-rank fusion. No browser, so it is fast and avoids most captchas; a blocked engine just drops out.
- **Web fetching:** `web_fetch`, a plain HTTP GET/HEAD (no browser, no JavaScript) for a known URL or API. `format=markdown` returns a clean Markdown extract of the page, paginated by tokens (`page`, `page_size_tokens`, cached), wrapped in an untrusted-content fence, with block/captcha detection.
- **Crypto:** Coinbase Exchange market data, a local backtest and optional live trading (`crypto_place_order` / `crypto_cancel_order` are off by default; the API key is stored only in EncryptedSharedPreferences).
- **Device control:** `run_shell` via Shizuku (shell uid), `screen_*` tools via an Accessibility service, and `termux_run` (bash in Termux through its RUN_COMMAND service, cwd `~/verdroid-work`) with optional Termux packages (ffmpeg, imagemagick, yt-dlp, jq, curl, pandoc, …) installable from Settings → Tools → Available to install. `termux_run` returns as soon as the command finishes; `timeout_seconds` is only an upper limit (background jobs left running are stopped).
- **Headless Chromium + Selenium (in Termux):** the optional `chromium-selenium` add-on (Settings → Tools → Available to install: `x11-repo`/`tur-repo` Chromium + chromedriver, `pip install selenium`) powers `selenium_open` (title, visible text, links), `selenium_page_source` (rendered HTML) and `selenium_screenshot` (PNG), all headless, plus `termux_python` to run Python scrapers the agent writes. Scripts stay in the private app workspace (e.g. `scrapers/*.py`); scraped files go to `Documents/Verdroid/Output` (run `termux-setup-storage` in Termux once). Use it for JavaScript-heavy pages; `web_search`/`web_fetch` stay the default.
- **Shared folder:** `/storage/emulated/0/Documents/Verdroid` with `Input/` (files you give Verdroid) and `Output/` (its results: translations, screenshots, scripts, projects), created at launch once *All files access* is granted (Settings → Tools → Shared folder, or Settings → Phone → Permissions). `workspace_list` / `workspace_read` / `workspace_write` / `workspace_delete` can only reach that tree (path escapes and symlinks are rejected).
- **Skills:** the agent can save reusable multi-step procedures it worked out with you (`skill_save`, `skill_edit`, `skill_delete`, `skill_list`, `skill_get`) as `files/skills/<id>/SKILL.md` inside the app. Settings → Skills lists them with an on/off switch and Delete; the system prompt only gets an index of enabled skills (name + one-line description, required on save), and the agent loads the full SKILL.md with `skill_get` when a task matches. The agent offers to save a skill when a procedure looks reusable.
- **rish:** in Settings → Shizuku & Git → rish, tap **Find rish** (with All files access it scans Download, Documents and Documents/Verdroid/Input) or **Pick rish file** for the `rish` exported by Shizuku (*Use Shizuku in terminal apps* → Export files; see [Shizuku-API/rish](https://github.com/RikkaApps/Shizuku-API/tree/master/rish)). Verdroid stages `rish` + `rish_shizuku.dex` in its private storage, then copies both to `/data/local/tmp/verdroid_rish/` through Shizuku and `chmod +x` both (**Fix rish permissions** repeats that; needs Shizuku running). The agent runs commands with `rish_run` from that folder with `RISH_APPLICATION_ID=com.termux`. Deliverables (including screenshots) go to `Documents/Verdroid/Output`.
- **Ebook translation:** `ebook_translate` (Termux Python: MOBI first, then EPUB/PDF/DOCX/TXT, plain-text fallback if a reader fails). `googletrans>=4.0.2` with a browser User-Agent, chunks of at most 4000 characters (never 5000+), ~0.3 s between requests with backoff on *Too many requests*, a 5–10 s pause every 4 chapters, MyMemory fallback, per-engine language-code handling, and resume after interruptions. It is two-step: the first call estimates chapters, chunks and time and the agent asks you to confirm before translating. Missing Python packages are installed on first use only after you agree in chat (the **ebook-translate** add-on under Settings → Tools installs them up front). Attach a file in chat or put it in `Documents/Verdroid/Input/`; the result lands in `Output/`.
- **Attach file in chat:** the composer’s **+** picks a file (SAF) and copies it into `Documents/Verdroid/Input`. The chat shows a clean 📎 chip with the file name and size; the path and tool hints are sent to the model only. A chat started with just an attachment is titled after the file.
- **Permissions in one place:** Settings → Phone → Permissions lists everything Verdroid can ask for (All files access, notifications, Termux RUN_COMMAND, Shizuku, accessibility, overlay, battery optimisation, installing updates) with status and a Grant button, plus a shortcut to *Set up Termux*.
- **Git:** clone / status / commit / push with JGit inside the app workspace.
- **Memory, MCP client, sandboxed file tools** and a keep-alive service that only runs while a task runs.
- **Security first:** API keys in EncryptedSharedPreferences (Android Keystore), no backups, HTTPS only.

## Quick start

### 1. Install the app

Download `Verdroid-v<version>-debug.apk` from [Releases](https://github.com/chr243/Verdroid/releases) and open it on the phone (Android 11+, minSdk 30), or with adb:

```bash
adb install -r Verdroid-v<version>-debug.apk
```

Then open Verdroid → **Menu → API keys** and add an OpenRouter key (`sk-or-…`) and/or a Kilo key.

### Shared folder (optional, for `workspace_*`)

Open **Settings → Tools → Shared folder** and tap **Grant** to give Verdroid *All files access* (Android 11+ `MANAGE_EXTERNAL_STORAGE`). Verdroid then creates `Documents/Verdroid/Input` and `Documents/Verdroid/Output`.

### 2. Termux (optional, for `termux_run`, `selenium_*`, `termux_python`, `ebook_translate`)

Open Verdroid → **Settings → Tools → Termux** and tap **Set up Termux**. It walks through everything and continues each time you come back to Verdroid: opens F-Droid if [Termux](https://f-droid.org/packages/com.termux/) is missing, asks for the *Run commands in Termux* permission, copies the `allow-external-apps` command and opens Termux for one paste (Termux refuses outside commands until that is set), then starts `termux-setup-storage` in Termux so you only tap Allow. Packages under **Available to install** are installed in the background with `pkg`.

### 3. Shizuku (optional, for `run_shell`)

Install [Shizuku](https://shizuku.rikka.app/), open it and start it via **Wireless debugging** (Developer options → Wireless debugging → pair from the Shizuku app), or from a computer:

```bash
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

Then open Verdroid → **Settings → Shizuku & Git** and grant the Shizuku permission; **Test (id)** should report `uid=2000(shell)`. For `rish_run`, add rish there too (see *rish* above). All grants are also listed under **Settings → Phone → Permissions**.

### 4. Build from source

Requires JDK 17 and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
git clone https://github.com/chr243/Verdroid.git && cd Verdroid
echo "sdk.dir=$ANDROID_HOME" > local.properties   # never commit this file
./gradlew assembleDebug testDebugUnitTest lint
# APK: app/build/outputs/apk/debug/Verdroid-v<version>-debug.apk
```

## Documentation

- [AGENTS.md](AGENTS.md): repo map, commands and rules for AI coding agents and contributors
- [CONTRIBUTING.md](CONTRIBUTING.md): how to contribute
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): architecture, tools and version history

## Status and limitations

Personal project, debug-signed builds only. There is no in-app browser and no X/Facebook tools (removed). Pages that need JavaScript can be read with the optional headless Chromium + Selenium add-on in Termux; pages behind a login are not supported. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#known-limitations).

## License

TODO(maintainer): no license file yet. Choose one (e.g. MIT / Apache-2.0) and add `LICENSE`.
