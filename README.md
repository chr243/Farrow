# Farrow

**An on-device AI agent for Android that actually does things:** it fetches web pages and APIs over HTTP, runs shell commands through Shizuku, drives apps with Accessibility, works with Git and reads crypto market data, all on **free OpenRouter / Kilo models** with automatic model and key fallback and rate-limit recovery. No server and no cloud backend: your keys stay encrypted on your phone.

<p align="center">
  <img src="docs/images/chat-list.jpg" alt="Farrow chat list" width="300">
  &nbsp;&nbsp;
  <img src="docs/images/settings.jpg" alt="Farrow settings" width="300">
</p>

## Features

- **Messenger-style chat UI** (Kotlin, Jetpack Compose, Material 3) with chat heads / Android Bubbles and a quota indicator.
- **Free-model agent loop:** tool calling on OpenRouter free models and Kilo, priority list, automatic fallback across models and API keys, backoff and auto-resume after rate limits.
- **Web search (default):** `web_search`, a keyless multi-engine search ported from [hec-ovi/websearch-skill](https://github.com/hec-ovi/websearch-skill) (MIT): DuckDuckGo (html, with lite fallback), Brave, Bing, Mojeek, Yahoo and Wikipedia queried in parallel over plain HTTP, redirect links unwrapped (`duckduckgo.com/l/?uddg=`, Bing `ck/a`, Yahoo `/RU=`), URLs canonicalised and deduplicated, then ranked with de-correlated reciprocal-rank fusion. No browser, so it is fast and avoids most captchas; a blocked engine just drops out.
- **Web fetching:** `web_fetch`, a plain HTTP GET/HEAD (no browser, no JavaScript) for a known URL or API. `format=markdown` returns a clean Markdown extract of the page, paginated by tokens (`page`, `page_size_tokens`, cached), wrapped in an untrusted-content fence, with block/captcha detection.
- **Crypto:** Coinbase Exchange market data, a local backtest and optional live trading (`crypto_place_order` / `crypto_cancel_order` are off by default; the API key is stored only in EncryptedSharedPreferences).
- **Device control:** `run_shell` via Shizuku (shell uid), `screen_*` tools via an Accessibility service, and `termux_run` (bash in Termux through its RUN_COMMAND service) with optional Termux packages (ffmpeg, imagemagick, yt-dlp, jq, curl, pandoc, …) installable from Settings → Tools.
- **Headless Chromium + Selenium (in Termux):** the optional `chromium-selenium` add-on (Settings → Tools → Available to install: `x11-repo`/`tur-repo` Chromium + chromedriver, `pip install selenium`) powers `selenium_open` (title, visible text, links), `selenium_page_source` (rendered HTML) and `selenium_screenshot` (PNG), all headless, plus `termux_python` to run Python scrapers the agent writes. Scripts stay in the private app workspace (e.g. `scrapers/*.py`); scraped files go to `Documents/Farrow/Output` (run `termux-setup-storage` in Termux once). Use it for JavaScript-heavy pages; `web_search`/`web_fetch` stay the default.
- **Shared folder:** `/storage/emulated/0/Documents/Farrow` with `Input/` (files you give Farrow) and `Output/` (its results), created at launch once *All files access* is granted (Settings → Tools → Shared folder). `workspace_list` / `workspace_read` / `workspace_write` / `workspace_delete` can only reach that tree (path escapes and symlinks are rejected).
- **Skills:** the agent can save reusable multi-step procedures it worked out with you (`skill_save`, `skill_edit`, `skill_delete`, `skill_list`, `skill_get`) as `files/skills/<id>/SKILL.md` inside the app. Settings → Skills lists them with an on/off switch and Delete; the system prompt only gets an index of enabled skills (name + one-line description, required on save), and the agent loads the full SKILL.md with `skill_get` when a task matches. The agent offers to save a skill when a procedure looks reusable.
- **rish:** in Settings → Shizuku, accessibility & Git → rish, tap **Find rish** (with All files access it scans Download, Documents and Documents/Farrow/Input) or pick the `rish` file exported by Shizuku (*Use Shizuku in terminal apps* → Export files; see [Shizuku-API/rish](https://github.com/RikkaApps/Shizuku-API/tree/master/rish)). Farrow copies both `rish` and `rish_shizuku.dex` into its internal folder (Find/Pick copies both to `/data/local/tmp/farrow_rish` via Shizuku and `chmod +x` both; **Fix rish permissions** retries that) and the agent runs them with `rish_run` (`RISH_APPLICATION_ID=com.termux`). Deliverables (including screenshots) go to `Documents/Farrow/Output`.
- **Permissions in one place:** Settings → Phone → Permissions lists everything Farrow can ask for (All files access, notifications, Termux RUN_COMMAND, Shizuku, accessibility, overlay, battery optimisation, installing updates) with status and a Grant button, plus a shortcut to *Set up Termux*.
- **Git:** clone / status / commit / push with JGit inside the app workspace.
- **Memory, MCP client, sandboxed file tools** and a keep-alive service that only runs while a task runs.
- **Security first:** API keys in EncryptedSharedPreferences (Android Keystore), no backups, HTTPS only.

## Quick start

### 1. Install the app

Download `Farrow-v<version>-debug.apk` from [Releases](https://github.com/chr243/Farrow/releases) and open it on the phone (Android 11+, minSdk 30), or with adb:

```bash
adb install -r Farrow-v1.0.0-debug.apk
```

Then open Farrow → **Menu → API keys** and add an OpenRouter key (`sk-or-…`) and/or a Kilo key.

### Shared folder (optional, for `workspace_*`)

Open **Settings → Tools → Shared folder** and tap **Grant** to give Farrow *All files access* (Android 11+ `MANAGE_EXTERNAL_STORAGE`). Farrow then creates `Documents/Farrow/Input` and `Documents/Farrow/Output`.

### 2. Termux (optional, for `termux_run`)

Open Farrow → **Settings → Tools → Termux** and tap **Set up Termux**. It walks through everything and continues each time you come back to Farrow: opens F-Droid if [Termux](https://f-droid.org/packages/com.termux/) is missing, asks for the *Run commands in Termux* permission, copies the `allow-external-apps` command and opens Termux for one paste (Termux refuses outside commands until that is set), then starts `termux-setup-storage` in Termux so you only tap Allow. Packages under **Available to install** are installed in the background with `pkg`.

### 3. Shizuku (optional, for `run_shell`)

Install [Shizuku](https://shizuku.rikka.app/), open it and start it via **Wireless debugging** (Developer options → Wireless debugging → pair from the Shizuku app), or from a computer:

```bash
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

Then open Farrow → **Menu → Shizuku, accessibility & Git** and grant the Shizuku permission; **Test (id)** should report `uid=2000(shell)`.

### 4. Build from source

Requires JDK 17 and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
git clone https://github.com/chr243/Farrow.git && cd Farrow
echo "sdk.dir=$ANDROID_HOME" > local.properties   # never commit this file
./gradlew assembleDebug testDebugUnitTest lint
# APK: app/build/outputs/apk/debug/Farrow-v<version>-debug.apk
```

## Documentation

- [AGENTS.md](AGENTS.md): repo map, commands and rules for AI coding agents and contributors
- [CONTRIBUTING.md](CONTRIBUTING.md): how to contribute
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): architecture, tools and version history

## Status and limitations

Personal project, debug-signed builds only. There is no in-app browser: pages that need JavaScript or a login can't be read. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#known-limitations).

## License

TODO(maintainer): no license file yet. Choose one (e.g. MIT / Apache-2.0) and add `LICENSE`.
