# Farrow — architecture & development notes

> Detailed design notes and per-version history. The user-facing overview is in [../README.md](../README.md); agent/contributor rules are in [../AGENTS.md](../AGENTS.md).

A native Android agent app (Kotlin, Jetpack Compose, Material 3) with a Messenger-style UI. It runs a tool-using LLM agent on **OpenRouter free models**, falls back between models and API keys automatically, and handles free-tier rate limits.

- Package: `com.farrow.app`. minSdk 30, targetSdk/compileSdk 35. Built for Android 15 / HyperOS (Poco X8 Pro Max) and runs on any Android 11+ device.
- Stack: Hilt, Navigation Compose, Room, DataStore, WorkManager (+ Hilt-Work), Retrofit + OkHttp + kotlinx.serialization, androidx.security-crypto. No vendor LLM SDKs: everything goes through OpenRouter's OpenAI-compatible REST API.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 1 | Messenger-style UI shell and Settings (keys, model priority, limits) | ✅ done |
| 2 | Agent core: OpenRouter client, rate-limit detection and logging, client RPM limiter, model/key fallback, quota polling, tool loop, file tools, rolling summarization, chat UI | ✅ done |
| 2.1 | Chat heads (Bubbles API + HyperOS overlay fallback), 3-tab nav, instant transitions | ✅ compiled in v0.9.0 (not device-tested yet) |
| 3 | Rate-limit recovery: durable WorkManager queue, checkpoints, backoff, daily-quota pause, persisted cooldowns/counters | ✅ compiled in v0.9.0 (not device-tested yet) |
| 6 | Accessibility service (tap/swipe/type/read tree), Shizuku (UserService `run_shell`), JGit clone/status/commit/push | ✅ compiled in v0.9.0 (not device-tested yet) |
| 7 | Chat heads | ✅ (see 2.1) |
| 8 | Keep-alive foreground service (min-importance), battery-optimisation prompt, HyperOS autostart guidance, resume on boot | ✅ compiled in v0.9.0 (not device-tested yet) |

## Build

Requirements: JDK 17 and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk        # or create local.properties with sdk.dir=...
./gradlew assembleDebug                          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                      # JVM unit tests
./gradlew lintDebug                              # Android lint (0 errors at v0.2.0)
```

Toolchain: Gradle 8.11.1 (wrapper), AGP 8.7.3, Kotlin 2.1.0 with the Compose compiler plugin, KSP. All versions live in `gradle/libs.versions.toml`.

## Install on the phone (Poco / HyperOS)

1. Download `Farrow-debug.apk` (or `app-debug.apk`) from the GitHub Release **v0.2.0-phase2** on the phone.
2. Open it. When HyperOS asks, allow "Install unknown apps" for your browser or file manager. If MIUI/HyperOS security scanning blocks it, choose "Install anyway".
3. Or use adb: enable Developer options → USB debugging (on HyperOS also enable "Install via USB"), then run `adb install -r app-debug.apk`.
4. Open Farrow → **Menu → API keys** → add your OpenRouter key (`sk-or-…`).
5. Optional: Settings → Apps → Farrow → Battery saver → "No restrictions", so the periodic quota worker isn't killed.

The debug APK is signed with the standard debug key. To upgrade, install a newer debug build over it.

## Architecture

```
com.farrow.app
├── ui/            Compose screens + ViewModels
│   ├── chats/         conversation list, stories row, search, quota banner, FAB
│   ├── chat/          chat detail (bubbles, tool cards, status lines, Stop/Resume, input)
│   ├── notifications/ Room-backed alerts (opened from Menu → Notifications)
│   ├── menu/          Notifications, API keys, model priority, rate limits & quota, Rate Limit Log, Chat heads, Shizuku placeholder
│   ├── chathead/      compact chat panel, chat-head avatar, dismiss target, overlay-permission dialog
│   └── components/    avatars, status dots, markdown, JSON/shell highlighter
├── domain/        models, repository interfaces, use cases (no Android deps)
├── data/
│   ├── local/         Room: tasks, messages, tool_calls, rate_limit_events, notifications
│   ├── network/       Retrofit API, interceptors, error/header parsers, client limiter, OpenRouterClient
│   ├── secure/        EncryptedSharedPreferences API key store
│   ├── settings/      DataStore (model list, limits, cached quota)
│   ├── repository/    repository implementations
│   └── work/          QuotaPollWorker (periodic WorkManager job)
├── chathead/      ChatHeadController (mode resolution), BubbleNotifier (Bubbles API), BubbleActivity, ChatHeadService (overlay)
├── agent/
│   ├── AgentLoop      tool-use loop (max steps, persistence, fallback JSON tool calls)
│   ├── AgentRunner    one coroutine job per task; Stop; in-process auto-resume after rate limits
│   ├── context/       token estimation + rolling summarization
│   └── tools/         sandboxed file tools, stub tools, registry, fenced-JSON parser
└── di/            Hilt modules
```

### OpenRouter client
- Base URL `https://openrouter.ai/api/v1/`. Uses `POST chat/completions` (OpenAI format with `tools`, `tool_choice: auto`) and `GET key`.
- Every request carries `HTTP-Referer` and `X-Title: Farrow`.
- `RateLimitInterceptor` reads `X-RateLimit-Limit/-Remaining/-Reset` (Unix ms, seconds tolerated) on every response. The latest values per key are shown under Menu → Rate limits & quota.
- `ApiErrorDetector` flags errors from the HTTP status (429/402/401/5xx) **and** from the body: a top-level `{"error":{"code":429,…,"metadata":{"error_type":"rate_limit_exceeded"}}}`, including when it comes with HTTP 200, or an `error` object inside a choice.
- Fallback per request:
  - 429, 5xx, upstream or bad-request on a model: the model cools down (default 60 s, longer if the reset header says so) and the next model in the priority list is tried.
  - A key-wide limit (remaining=0 with a far reset, a "per-day" message, 402, 401/403, or the local requests/day reached): the key is parked and the next key is used.
  - Everything exhausted: the task becomes `RATE_LIMITED` with a live "Resuming in …" status line, a notification is posted, and an in-process auto-resume is scheduled.
- Each 429/402/rate-limit event goes into the `rate_limit_events` table with the timestamp, model, key label, status, headers, message and outcome. You can view it under Menu → Rate Limit Log.
- `ClientRateLimiter`: a sliding 60 s window per key that enforces the configured RPM (default **50**). It also keeps an in-memory requests/day counter per key.

### Quota polling
`GET /key` runs on app start, every 5 minutes while the app is in the foreground, and every 30 minutes through WorkManager (requires network). `free_model_daily_requests.used/limit/remaining` is parsed defensively; `usage`, `limit` and `rate_limit` are kept when present. If the free-model block is missing, the remaining count is estimated from the configured daily limit minus local usage and marked "(estimated)". When remaining < threshold (default 100), the Chats screen shows
`⚠️ Free tier: X requests remaining today (limit: 1000). Resets at 00:00 UTC.` A low-quota notification is posted at most once per UTC day.

### Agent loop
- Sends the system prompt + rolling summary + live turns + tool schemas. It executes the returned `tool_calls`, appends `tool` results, and repeats until there's a final answer or the max-steps cap (default 25) is hit. At the cap the task is paused and can be resumed.
- Fallback for models that ignore native tools: a JSON tool call in a fenced block (`{"tool": "...", "arguments": {...}}` and similar shapes) is executed, and the result is sent back as a user message.
- Every message and tool call is stored in Room. Task rows hold `status`, `currentStep`, `checkpointJson` and `resumeAt` for Phase 3.
- Stop Generation cancels the coroutine. The task becomes `CANCELLED` and can be resumed.

### Tools
| Tool | Status |
|---|---|
| `read_file`, `write_file`, `list_dir` | ✅ working inside `filesDir/workspace`. Absolute paths are re-rooted, and `..`/symlink escapes are rejected |
| `workspace_list`, `workspace_read`, `workspace_write`, `workspace_delete` | Shared `/storage/emulated/0/Documents/Farrow` (`Input/`, `Output/`); needs All files access; `SharedFolderSandbox` rejects `..`, outside absolute paths and symlink escapes; recursive delete never follows symlinks |
| `selenium_open`, `selenium_page_source`, `selenium_screenshot`, `termux_python` | Headless Chromium + Selenium inside Termux (`chromium-selenium` add-on) via `~/.farrow/farrow_selenium.py` (`data/termux/FarrowSeleniumPy`); scripts in `filesDir/workspace`, output to `Documents/Farrow/Output` |
| `skill_list`, `skill_get`, `skill_save`, `skill_edit`, `skill_delete` | Agent-writable skills in `files/skills/<id>/SKILL.md` (`data/skills/SkillStore`); enabled ones injected into the system prompt |
| `rish_run` | `sh files/rish/rish -c <cmd>` with `RISH_APPLICATION_ID=com.termux`; dex kept at chmod 400 (`shizuku/RishStore`, `RishRunner`) |
| `web_search` | Default search: keyless parallel DDG/Brave/Bing/Mojeek/Yahoo/Wikipedia, redirect unwrapping, canonical dedup, de-correlated RRF (`data/websearch/`, port of hec-ovi/websearch-skill, MIT) |
| `web_fetch` | Plain HTTP GET/HEAD with OkHttp (no browser, no JavaScript); `format=markdown` gives a paginated, fenced Markdown extract |
| `crypto_*` | Coinbase Exchange market data, local backtest; live trading tools off by default (v1.0.18) |
| `memory_*`, `chart` | Persistent memory (v0.9.16) and native charts (v1.0.12) |
| `run_shell` | Phase 6: Shizuku UserService (`sh -c` as uid 2000) |
| `git_clone`, `git_status`, `git_commit`, `git_push` | Phase 6: JGit 5.13 inside the workspace, using the token from encrypted settings |
| `screen_read`, `screen_tap`, `screen_swipe`, `screen_type`, `screen_action` | Phase 6: `FarrowAccessibilityService` |

### Context management
Tokens are estimated as chars/4. When the live history goes over 60% of the context budget (default 16K tokens, configurable), the oldest turns are summarized with the same model priority list. The summary is stored as a `SUMMARY` message, and the originals are flagged `summarized`, so they stay visible in the UI but are no longer sent. The kept window never starts with an orphaned tool result.

### Default model priority
Tried top to bottom (Menu → Model priority to change):
1. `inclusionai/ling-3.0-flash-sante:free`
2. `openrouter/free` (OpenRouter's free auto-router; it is not last, and nothing treats the last entry specially)
3. `openai/gpt-oss-120b:free`
4. `qwen/qwen3-coder:free`
5. `meta-llama/llama-3.3-70b-instruct:free`
6. `mistralai/mistral-small-3.1-24b-instruct:free`
7. `google/gemma-3-27b-it:free`

A one-time DataStore migration (`ModelDefaultsMigration`, `model_defaults_version` = 2) applies the new defaults to existing installs only when the saved list is exactly the old default list. Customised lists are kept as they are.

### Navigation
There is no bottom bar: the chat list is home and a gear opens **Settings** (Notifications, Memory, Archive, models, tools, phone and appearance settings). Notifications show an unread count in Settings. The Chats screen shows the low-quota banner. All NavHost transitions are set to `EnterTransition.None`/`ExitTransition.None`, so navigation is instant.

### Chat heads (v0.2.1)
- The 🫧 button in the chat detail top bar opens the conversation as a floating chat head. On Android 13+ it asks for `POST_NOTIFICATIONS` first.
- **Bubbles API** (primary): each task gets a long-lived dynamic conversation shortcut and a `MessagingStyle` notification on the `chat_bubbles` channel (bubbles allowed). Its `BubbleMetadata` opens `BubbleActivity` (`allowEmbedded`, `resizeableActivity`, `documentLaunchMode="always"`) with a compact chat. The notification is re-posted as the unread count changes, which drives the badge.
- **Overlay fallback** (HyperOS/MIUI): `ChatHeadService` is a `specialUse` foreground service that uses `SYSTEM_ALERT_WINDOW`. The chat head is draggable, snaps to the nearest edge, and closes when you drag it onto a ✕ target. It shows a red unread badge and expands into a compact Compose chat panel (send, Stop, Resume, "Open ↗" for the full app). If the permission is missing, a dialog explains it and opens the system setting. On HyperOS you also need to enable "Display pop-up windows while running in the background".
- **Setting** (Menu → Chat heads): Auto, Bubbles or Overlay. Auto uses Bubbles when they're allowed and the device isn't Xiaomi/Redmi/POCO; otherwise it uses Overlay. The screen also shows the permission states, with buttons to fix them.

**v0.9.1 fix (overlay couldn't be tapped or dragged):** Material3 `Surface` in `ChatHeadAvatar` installs an empty `pointerInput` to block click-through. That made the inner `AndroidComposeView` claim every touch, so the `OnTouchListener` on the outer `ComposeView` (a ViewGroup) never ran. The head window's root is now a `TouchInterceptLayout` that intercepts all touches before Compose. It handles tap or drag with touch slop by updating `LayoutParams.x/y` through `updateViewLayout`, then snaps to the edge with a `ValueAnimator`. The service now runs its own `LifecycleRegistry`, moved to RESUMED (a `LifecycleService` stops at STARTED). It sets the Lifecycle, SavedStateRegistry and ViewModelStore owners before `addView`. The panel root catches `ACTION_OUTSIDE` first. Logcat tag: `ChatHead`.

**v0.9.3 fix (head drawn at the wrong place after the panel closed):** in v0.9.2 the head window was moved to the top corner while the panel was open, then moved back with `updateViewLayout` on collapse. On HyperOS the window's touch area went back to the saved position, but its drawn surface stayed at the top corner over the clock. Taps there went through to the app underneath, and the first touch redrew the head at its real spot, so it seemed to teleport. Changes:
- The head now keeps one window and one `LayoutParams` object. The window is removed while the panel is open, then re-added with the same params at the saved x/y, clamped to `WindowMetrics` minus system-bar and cutout insets.
- The flags never change: `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN` with cutout mode `ALWAYS`, so params x/y are in the same frame as rawX/rawY. The head is never `NOT_TOUCHABLE`.
- The snap animation is cancelled on ACTION_DOWN.
- Dragging follows `raw − grab offset`, and the ✕ target appears once the drag passes touch slop.
- The position is saved in prefs.
- Logcat (`ChatHead`) prints params and `getLocationOnScreen` after a collapse.

### Rate-limit recovery (Phase 3)
- **Durable queue:** each paused or queued task has a unique WorkManager job, `agent-task-<id>`, with an initial delay equal to its resume time. When it fires, `AgentTaskWorker` restarts the loop. On app start, `AgentScheduler.recover()` re-queues tasks that were RUNNING or QUEUED when the process died and makes sure every paused task still has its job.
- **Checkpoint** (`tasks.checkpointJson`, v2): records the current step, the completed steps, the pending step (calling the model or running tools), recent tool results, the last message id and the model. A resumed run continues from the next step.
- **Pause instead of fail:**
  - Rate limits pause the task.
  - Network or upstream errors pause it with exponential backoff and equal jitter: base 5 s, capped at 5 min, up to 8 attempts.
  - An `X-RateLimit-Reset` header overrides the backoff cap.
  - When every key is out of daily free quota, the task pauses until 00:00 UTC with the message "⏸️ Paused: Daily free quota exhausted. Resuming at 00:00 UTC."
- **Persisted state:** the model cooldown pool, key blocks and per-key UTC-day request counters are stored in SharedPreferences (`AgentStateStore`).
- **UI:** a yellow story dot with a countdown, an auto-resume countdown in the chat, a notification entry, and **Force retry now** / **Cancel** in the chat.
- Room schema v2 adds `tasks.attempt` and `tasks.pauseReason` (migration `MIGRATION_1_2`).

### Accessibility, Shizuku and Git (Phase 6)
- **Accessibility:** `FarrowAccessibilityService` (`res/xml/accessibility_service_config.xml`, protected by `BIND_ACCESSIBILITY_SERVICE`) uses `dispatchGesture` for taps and swipes, `ACTION_SET_TEXT` on the first editable or focused node for text, and `rootInActiveWindow` for a bounded JSON tree with class, text, description, view id, bounds and the clickable/editable flags. It also supports global actions (back, home, recents, …). The user turns it on in system Accessibility settings, or on HyperOS under Additional settings → Accessibility → Downloaded apps.
- **Shizuku** (`dev.rikka.shizuku:api`/`provider` 13.1.5, with `rikka.shizuku.ShizukuProvider` in the manifest): `ShizukuManager` tracks NOT_INSTALLED, NOT_RUNNING, PRE_V11, NO_PERMISSION and READY through binder and permission listeners. Since `Shizuku.newProcess` is private in API 13, `run_shell` binds a **UserService** (`ShellUserService`, AIDL `IShellService`) that runs `sh -c` in Shizuku's process and returns exit code, stdout and stderr as JSON, with a timeout of up to 600 s. The setup screen is at Menu → Shizuku, accessibility & Git, and includes a "Test (id)" button.
- **JGit 5.13** (the last Java 8 line; 6.x needs APIs that only exist on Android 13+): `git_clone` (https only, shallow clones are not supported in 5.x), `git_status`, `git_commit` (stages adds and deletions, then commits with the configured author) and `git_push`. Push uses `UsernamePasswordCredentialsProvider(username, token)` with a PAT stored in EncryptedSharedPreferences (`git_secure`). Repositories are limited to the agent workspace sandbox.


**v0.9.2 run_shell backends** (`ShellExecutor`), tried in this order. *Since v0.9.9 the order is Shizuku newProcess → Shizuku UserService; the ADB backend described here and in v0.9.6/v0.9.7 was removed.*
1. **ADB over TCP** with dadb 2.0, connecting to `127.0.0.1:5555` (configurable). An RSA key is generated in `filesDir/adb`. Android asks "Allow USB debugging?" once.
2. **Shizuku UserService.** It now uses `debuggable(false)`, waits for the binder, records bind errors, and supports a `(Context)` constructor.
3. **Shizuku.newProcess**, called by reflection.

The device screen shows the active backend and each backend's error. **Connect / Test (id)** reports which backend ran the command, along with its output. v0.9.5 changes for the UserService (Shizuku permission was already granted, so the bind failure isn't a permission issue):
- It uses a tag per build, so a leftover record from an earlier failed start can't capture the bind.
- When the first bind times out, it removes the service with `unbindUserService(remove = true)` and binds once more.
- The card shows the Shizuku server version and patch, the uid and SELinux context.
- **Diagnose** reads `ps` plus a filtered `logcat` through ADB or `newProcess`, to show whether the `:shell` process started and why it failed. The service logs `ShellUserService created uid=…` when it starts.

**v0.9.6 ADB over TCP:** dadb was replaced by `MiniAdb`, a small ADB transport client (CNXN, AUTH, STLS detection, OPEN, WRTE, OKAY, CLSE, `shell,v2,raw:`).
- **Why:** in dadb 2.0.0, `AdbConnection.connect` wraps ANY `IOException` during CNXN/AUTH in `AdbConnectException("Connection handshake failed")`. That covers the peer closing the socket (key prompt denied or dismissed, or a non-adbd listener), a truncated non-ADB reply, or a read timeout. An AUTH rejection would surface as `AdbAuthException`, and STLS as `"Connection failed: STLS…"`. dadb doesn't check version or maxdata. So the error only means "the socket died mid-handshake".
- **Packet log:** MiniAdb records each handshake packet (`> CNXN`, `< AUTH TOKEN`, `> AUTH SIGNATURE`, `> AUTH RSAPUBLICKEY`, `< CNXN`/`STLS`/EOF), and the card shows that log in the error.
- **Port checks:** if the port answers with the adb *server* text protocol (5037 is the host adb server's default port, e.g. `adb` in Termux), it says so. STLS (the Wireless-debugging port) is also reported.
- **Key:** `adbkey` is a PKCS#8 PEM in the same format as before, created once and reused. The public key is derived from the private key and sent as `<base64> farrow@<model>\0`.
- **Prompt and retry:** it waits up to 60 s for "Allow USB debugging?" and retries once.
- **Port:** stays as configured.
- **Backend order:** now ADB → Shizuku newProcess → Shizuku UserService. A failed bind automatically attaches the relevant logcat lines, read through newProcess.

**v0.9.18:**
- **Harmonized themes:** each palette is now built from one seed with Material 3's tonal-palette algorithm (`SchemeContent`, via `com.materialkolor:material-color-utilities`). Primary, secondary, tertiary, surfaces, the surface-container ladder, containers and outlines all come from the same tones in light and dark. Seeds: Messenger Blue #0084FF, Forest #2E7D32, Sunset #F4511E, Purple #7E57C2, Rose #D81B60, Ocean #00897B. Midnight is the dark scheme on pure black. Dynamic still uses Material You on Android 12+.
- **Every screen uses theme color roles.** Sent bubbles use primary/onPrimary and received bubbles use surfaceContainerHigh/onSurface. Tool cards use surfaceContainer (errorContainer for errors). The chat head badge and dismiss target, the code colors, task avatars and status dots (harmonized with primary), the quota banner (tertiaryContainer), and the status/nav bar icons all follow the theme. The contrast tests are stricter.
- **New layout.** The bottom navigation bar is gone: the chat list is the home screen, and a gear opens **Settings** (the old Menu). Back returns to the chats. The chat-head button and the pen/new-chat icon are removed. The ＋ button on the chat list starts a new chat. A ⋮ menu in the chat screen opens Chat memory.
- **Settings has rounded groups with alternating row tones** (surfaceContainerLow/surfaceContainer), and so do Tools, MCP servers, API keys and Model priority.
- **MCP servers has its own screen.** Tools now only shows the built-in tools.
- **The Rate Limit Log screen is removed.** Rate-limit handling and logging are unchanged.

**v0.9.17:**
- **Two-tier memory.**
  - *Short-term* (`memory_save` with `scope="chat"`) is a per-chat scratchpad for task progress, decisions and findings. It's always in that chat's system prompt (~1k tokens, oldest trimmed) and deleted with the chat.
  - *Long-term* (`scope="global"`, the default) holds lasting facts and preferences, shared by all chats.
  - `memory_search` searches both (only the current chat's short-term notes) and labels each hit with its scope. The prompt tells the agent which tier to use.
- **Memory screen** has two tabs, *This chat* (with a chat picker) and *Long-term*. You can add, edit and delete in both, move a note from short-term to long-term, and clear either tier. A new **⋮ > Chat memory** entry in the chat screen opens that chat's short-term memory.
- Room DB v4 (`memories.chatId` + index) with a migration, tested against the exported schema. The export file lists long-term memories and each chat's notes.

**v0.9.16:**
- **Agent memory:** stored in a Room table `memories` (DB v3) and exported to `files/memory/MEMORY.md`. New tools `memory_save` (merges duplicates), `memory_search` and `memory_delete`, with toggles on the Tools page. The system prompt carries the ~20 most important/recent memories (≤ ~1.5k tokens) and tells the agent to save lasting facts without duplicates. New Menu > Memory screen: list, search, add, edit, delete, Clear all (with confirmation) and an Automatic saving switch.
- **Empty replies:** the nudge now asks for a 2–4 sentence progress report (done / stuck / next). The report shows as an agent message and the agent then keeps working. If the reply is empty a second time, the app shows a summary of the recent tool calls plus Continue.

**v0.9.15:**
- **Themes** (Menu > Theme):
  - Mode: System, Light or Dark.
  - Palettes: Messenger Blue (default), Dynamic (Material You, Android 12+), Midnight (true-black AMOLED), Forest green, Sunset orange, Purple, Rose pink, Ocean teal. Each has its own light and dark Material 3 scheme.
  - Chat bubbles and the chat head follow the theme. The choice is saved and applies instantly.

**v0.9.14:**
- **Remote MCP servers** (Menu > Tools > MCP servers). Supports Streamable HTTP (JSON or SSE replies, Mcp-Session-Id) and the legacy HTTP+SSE transport. Local stdio servers are not supported. Each server has a name, a URL, an optional auth header/token (stored encrypted; a bare token is sent as Bearer), an on/off switch, a status (connected with tool count, or error with Copy), and an expandable tool list with a switch per tool. You can add, edit and delete servers. The flow is initialize, then notifications/initialized, tools/list (paged) and tools/call (90 s timeout, one reconnect). Tools are exposed as `mcp__<server>__<tool>` with their input schemas. Results come back as text and errors go back to the model. Example servers, off by default (initialize, tools/list and tools/call checked from the box): DeepWiki, Context7, Microsoft Learn, Hugging Face.

**v0.9.13:**
- **Menu > Tools.**
  - Every agent tool, with a short description, its status (ready, or what it needs) and a persisted on/off switch.
    Tools that are off are left out of the model's tool list and refused if called.
- The Menu tab and its sub-screens are plain text: no leading icons/emoji on rows.
- The system prompt no longer says most tools are "not available yet".

**v0.9.10:**
Chat head: Back and Home/Recents collapse the expanded panel back to the head at its saved position.
- Back: the focused panel root handles it. If the keyboard is open, the first Back closes the keyboard.
- Home/Recents: detected via `ACTION_CLOSE_SYSTEM_DIALOGS`.
- Outside taps still collapse it too.

**v0.9.9:**
- **ADB over TCP removed:** run_shell now uses Shizuku newProcess, then the Shizuku UserService. The ADB card, the boot toggle, MiniAdb/AdbKey, their tests and the saved ADB settings and key are gone.

**v0.9.8:**
- Notifications: task completions no longer notify. Turn on **Notify when a task finishes** (Notifications screen, default off) to get them back. Rate limits, quota warnings and failures still notify.
- **Auto chat head on Home** (Chat heads settings, default on): pressing Home or Recents in a chat opens it as an overlay head (only if overlay permission is granted). The head is removed when you return.
- Agent loop never stops silently. Tool exceptions (any Throwable) are returned to the model as tool results and the loop continues. An empty reply gets one nudge (with the last tool error); if the next reply is also empty, a fallback agent message is posted. finish_reason length/content_filter/error, unparsable tool calls, max steps and crashes each post a visible reason, set the task to PAUSED/FAILED and show a **Continue** button.

**v0.9.7:**
- **ADB port:** on the user's phone, 127.0.0.1:5037 was Termux's adb *server*. The default port is back to 5555, and a saved or entered 5037 is migrated to 5555.
- **Enable ADB on 5555:**
  - What it does: runs `setprop service.adb.tcp.port 5555; setprop ctl.restart adbd` through Shizuku newProcess. If adbd doesn't come up, it toggles `adb_enabled`.
  - Checking the result: verifies with `getprop` and a port probe that confirms the listener is adbd, then connects.
  - If it fails: shows the manual Termux commands.
  - Side effect: this ends the current Wireless-debugging session; Shizuku keeps running.
  - Optional after-boot run via `AdbBootWorker`.
- **ShellUserService:** now plain Java with zero app dependencies.
- **Diagnose:** the logcat filter is tightened.

### Keep-alive (Phase 8)
- `KeepAliveService` is an opt-in foreground service (Menu → Background & battery). Its channel `keep_alive` uses **IMPORTANCE_MIN**: silent, no status-bar icon, `VISIBILITY_SECRET`, deferred FGS behaviour. **It can't be fully hidden.** Android requires a notification for every foreground service, Android 13+ always lists it in the "active apps" Task Manager, and HyperOS may show it more prominently. IMPORTANCE_NONE isn't allowed for foreground services.
- The service type is **`specialUse`**, with a subtype property. It isn't `dataSync` because, on Android 15, `dataSync` is capped at 6 h/day and can't start from `BOOT_COMPLETED`.
- The **battery optimisation** prompt uses `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (permission declared) and falls back to the settings list.
- **HyperOS autostart:** a button opens `com.miui.securitycenter/…AutoStartManagementActivity`, falling back to the app details page, alongside written guidance (Autostart, Battery saver → No restrictions, lock in Recents).
- **Boot:** `BootReceiver` (`BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`) restarts the service if enabled and calls `AgentScheduler.recover()`, which re-queues interrupted tasks and re-arms paused tasks' WorkManager resume jobs. WorkManager jobs themselves also survive reboots.

## Security
- API keys are stored with EncryptedSharedPreferences (AES-256-GCM values, AES-256-SIV key names, master key in the Android Keystore). Keys are masked in the UI and redacted from debug HTTP logs.
- `allowBackup=false` and data-extraction rules exclude preferences, databases and files.
- Nothing secret is committed. `local.properties`, keystores and `.env` files are git-ignored.

## Known limitations
- (v0.9.0) Everything after Phase 2 compiles, and its 25 JVM unit tests pass, but it **has not been tested on a device**. The Shizuku, accessibility and JGit paths are only exercised at runtime.
- There is no in-app browser: `web_search`/`web_fetch` run no JavaScript and can't log in, so JS-only or login-walled pages can't be read.
- JGit 5.x doesn't support shallow clones. `run_shell` runs as the shell uid, which can't read the app's private workspace.
- The keep-alive notification can't be fully hidden (Android rule).
- (Phase 3) If a crash happens halfway through running a step's tools, the tool calls that didn't run are dropped and the model is asked again.
- No streaming responses. Each step is a single, non-streamed completion.
- Model and key reordering uses up/down buttons (no drag-and-drop).
- Markdown rendering is minimal: headings, bullets, bold, italic, inline code and fenced code.
- Not tested against the live API from the build machine because no key was available. The parsing logic is covered by unit tests.

## v1.0.2
- An experimental feature, removed again in v1.0.11.

## v1.0.4
- Changes to the experimental feature removed in v1.0.11.

## v1.0.5
- **In-app updater (`data/update/`).** Settings > App > App update shows the installed version and a "Check for updates" button.
  - It reads the public GitHub API `releases/latest` (no token) and compares versions numerically (`UpdateLogic.compare`, pre-releases rank below their release). It then shows "Up to date" or "vX.Y.Z available" with the release notes and an Update button.
  - Update downloads `Farrow-*-<buildType>.apk` (`pickAsset`) with OkHttp into `cacheDir/updates`, with a progress bar, and checks that the size matches the asset.
  - It then opens the system installer through FileProvider (`${applicationId}.updates`) + ACTION_VIEW (`REQUEST_INSTALL_PACKAGES`). Without the "Install unknown apps" permission it opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Farrow first.
  - The same debug key means it installs over the app. A silent check runs on app start at most every 6 h, and a dot on the gear and the row marks an available update.

## v1.0.7
- **Audit of other screen-scoped jobs:**
  - The app update check and download moved from the Settings composable scope to `AppUpdater`'s app scope. The installer opens by itself only while the row is visible; otherwise tap Install.
  - MCP reconnect already runs in `McpManager`. Memory edits and key/model edits are short DB/DataStore writes and stay in the screen scope.

## v1.0.12
- Chat bubbles render GFM tables (`MarkdownTables` parser + `MarkdownTableView`): header row, zebra rows, inline
  markdown in cells, horizontal scroll, theme colors.
- New `chart` tool (`ChartTool`, `ChartSpecs`, `ChartMath`): bar/line/pie/scatter/table from
  `{type, title, x_label, y_label, labels[], series[{name, values[]}]}`. Rendered natively in the tool card
  (`ChartCard`, shared `ChartPainter`), tap = fullscreen with Share; PNG exported to `filesDir/charts/`
  (FileProvider path `charts`).
- System prompt: lists of items → Markdown tables, numeric comparisons → `chart`.
- Chat archive: deleting a chat on the home list (swipe either way, or long-press > Delete) sets `tasks.archivedAt`
  (Room v5, `MIGRATION_4_5` + index) with an Undo snackbar. Archived chats are hidden from every conversation list,
  skipped by restart recovery, and open read-only (banner with Restore). Settings > Archive lists them newest first
  with Restore / Delete permanently / Empty archive (both confirmed). Permanent delete removes messages and tool
  calls (FK cascade), the chat's short-term memory and its chart PNGs (only files inside `filesDir/charts`).
  Running work: archiving **stops** it (like the Stop button: generation cancelled, scheduled resumes dropped);
  after Restore, send a message to continue.
- Tests: Robolectric (JVM) DAO + migration tests that build v1/v4 databases from the exported schema JSON and let
  Room migrate and validate.

## v1.0.17

- **Colorful chat avatars:** a stable tonal color hashed from the chat id, and a topic/type/initial glyph.

## v1.0.18

- **Crypto tools (Coinbase Exchange).** Revolut's public developer APIs (Business / Merchant / Open Banking) expose
  accounts, payments and fiat FX — there is **no** crypto trading, order book, candles or spot-order endpoint. Retail
  crypto in the Revolut app has no documented API. Farrow therefore uses **Coinbase Exchange** for:
  - Public (no key): `crypto_markets`, `crypto_ticker`, `crypto_candles`, `crypto_orderbook`, `crypto_backtest` (local
    SMA crossover → return %, max drawdown, win rate, equity chart payload).
  - Authenticated (API key + secret + passphrase in EncryptedSharedPreferences, Settings > Shizuku… > Crypto exchange):
    `crypto_balance`, `crypto_order_status`.
  - Live trading (OFF by default under Tools): `crypto_place_order`, `crypto_cancel_order` — require `confirm=true` and
    an explicit user request with size/pair.

## v1.0.19

- **Internal browser removed.** The Termux/Firefox/Termux Browser Pilot browser and everything built on it are gone:
  the `tbp_bridge.py` asset and X/Facebook selector files, `BridgeClient`, setup wizard and auto-start, `BrowserOpsManager`
  and its service, X/Facebook login, WebView/cookie session import, session-expiry pause and Re-login, the
  `web_scrape`/`web_click`/`web_type`/`web_session`/`web_screenshot`, `x_*`, `x_post_beta`, `fb_*` and `reset_browser`
  tools, browser prefs (language, load images, show Termux, clear WebView cookies), the localhost cleartext exception and the Jsoup and
  androidx.browser dependencies. `web_fetch` (plain HTTP), crypto, Shizuku `run_shell`, screen, Git, memory and chart
  tools stay. The system prompt still prefers English sources.
- **Tasks screen removed** from Settings.
- **Termux kept without the bridge.** `termux_run` now runs `bash -lc` through Termux's RUN_COMMAND service in the
  background (`data/termux/TermuxManager`, result via `TermuxResultReceiver` + a mutable PendingIntent), in
  `~/farrow-work` under coreutils `timeout` (max 600 s, exit 124 = `timed_out`). Settings → Tools has a Termux card
  (installed / *Run commands in Termux* permission with a Grant button / allow-external-apps command to copy) and the
  "Available to install" package list (ffmpeg, imagemagick, yt-dlp, git, nodejs, jq, curl, pandoc; app-scoped
  `TermuxPackageJobs`). Needs `com.termux.permission.RUN_COMMAND` and a `com.termux` package query.
- **`web_search` (default web tool).** Port of [hec-ovi/websearch-skill](https://github.com/hec-ovi/websearch-skill)
  (MIT, Hector Oviedo) in `data/websearch/`: `Engines.kt` (DuckDuckGo html POST with lite fallback on the anomaly page,
  Brave, Bing, Mojeek, Yahoo, Wikipedia opensearch + intro), `SearchCore.kt` (`Canonical` URL normalisation and
  redirect unwrapping for `duckduckgo.com/l/?uddg=`, Bing `ck/a?u=a1…`, Yahoo `/RU=…/RK=`; `Fusion` dedup with provenance
  and de-correlated weighted RRF, k=60, Bing-backed DDG/Yahoo/Bing vote once, +10 % per extra independent group),
  `WebSearcher.kt` (engines in parallel, 8 s per engine, per-engine status/warnings), `PageReader.kt` (Markdown
  extraction, ≈4 chars/token pagination, random-nonce untrusted fence, block detection). `web_fetch` gained
  `format=markdown`, `page`, `page_size_tokens` and a 20-entry cache. Jsoup is back as a dependency for HTML parsing.
  The prompt tells the agent to use `web_search` first, then `web_fetch format=markdown` on the best 2–3 hits.
- **Shared folder `Documents/Farrow`.** `data/storage/SharedFolder` creates `/storage/emulated/0/Documents/Farrow` with
  `Input/` and `Output/` at launch (`FarrowApp`) and whenever Tools refreshes or a `workspace_*` tool runs, if missing.
  Needs `MANAGE_EXTERNAL_STORAGE` (All files access, minSdk 30); Settings → Tools has a *Shared folder* card explaining
  the folder with a Grant button (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`). Tools `workspace_list`,
  `workspace_read` (offset/max_bytes, binary detection), `workspace_write` (overwrite/append/create, directory=true) and
  `workspace_delete` (recursive=true for non-empty folders, root refused, Input/Output recreated) go through
  `SharedFolderSandbox`. `read_file`/`write_file`/`list_dir` stay the private `filesDir/workspace` scratch area.
- **Headless Chromium + Selenium in Termux.** New "Available to install" entry `chromium-selenium` (custom install:
  `x11-repo tur-repo`, `python python-pip chromium` [+ `chromedriver` if not bundled], `pip install -U selenium`; detected by
  chromium + chromedriver + `import selenium`). `TermuxPackage` gained `detect`/`install`; the detect query also reports
  whether Termux can write shared storage (Termux card step 4: `termux-setup-storage`). Before each call Farrow writes the
  helper `~/.farrow/farrow_selenium.py` (base64 over RUN_COMMAND): `make_driver()` (headless=new, no-sandbox, en-US) and a CLI
  (`open`/`source`/`shot`) printing one `FARROW_JSON=` line. Tools: `selenium_open`, `selenium_page_source`,
  `selenium_screenshot` (fresh browser per call; `save_as` only inside Documents/Farrow, written by Termux) and
  `termux_python` (script from the private workspace — scrapers never live in Documents — shipped to `$TMPDIR`, run in
  `~/farrow-work` with `FARROW_OUTPUT`/`FARROW_INPUT` and the helper on `PYTHONPATH`, max 600 s). Verified end to end on the
  build box with headless Chrome (`SeleniumToolsTest.localBashEndToEnd`, opt-in via `FARROW_LOCAL_BASH=1`).
- **Skills.** `data/skills/SkillStore` keeps agent-written procedures in app-internal `files/skills/<id>/SKILL.md`
  (front matter `name`/`description`/`enabled` + Markdown body; id = slug of the name; max 100 skills, 20k chars each).
  Tools `skill_save` (only after the user agrees), `skill_edit` (fields or unique find/replace), `skill_delete`,
  `skill_list`, `skill_get`. `AgentLoop` appends `SkillStore.promptBlock()` (index only: id, name and one-line description of
  each enabled skill; bodies are loaded with `skill_get`) before the memory block; disabled skills are never sent. Settings → Agent
  tools → Skills lists skills (tap to read), with an enable switch and Delete. The prompt tells the agent to offer saving
  a reusable multi-step procedure as a skill.
- **Set up Termux (one tap).** Settings → Tools → Termux has a *Set up Termux* button driven by
  `data/termux/TermuxSetupFlow` (INSTALL → GRANT → ALLOW_EXTERNAL → STORAGE → DONE) in `ToolsViewModel`: opens F-Droid,
  requests RUN_COMMAND, copies the allow-external-apps command to the clipboard and opens Termux (can't be automated:
  Termux rejects RUN_COMMAND until it is set), then runs `termux-setup-storage` in a visible Termux session
  (`TermuxManager.runInTerminal`, background=false) and opens Termux. Returning to Farrow (ON_RESUME / permission result)
  re-checks and continues; a step that is still pending shows a retry hint instead of re-triggering. The manual
  per-step buttons stay as a fallback.
- **rish picker + `rish_run`.** Settings → Shizuku, accessibility & Git → *rish*: the user picks the `rish` file exported by
  Shizuku (SAF multi-select; if only `rish` is picked, Farrow reads the sibling `rish_shizuku.dex` via All files access or asks
  for it). `RishStore` validates (shebang script / `dex\n` magic, companion name read from the script) and copies both into
  `files/rish/` (dex made read-only for Android 14+). `rish_run` runs `/system/bin/sh files/rish/rish -c <cmd>` in Farrow's
  process with `RISH_APPLICATION_ID` (Farrow at first; `com.termux` since the Unreleased change below), with timeout (exit 124).
  Settings shows status, *Test (id)* and *Remove*.
- Version-history entries that only covered the internal browser, X/Facebook automation, the Termux bridge and
  x_post_beta were removed with it.

## v1.0.20

- **Crash fix: Settings → Tools and Settings → MCP servers crashed on open (v1.0.19).** Both screens used
  `ToolsViewModel`, whose `init { refresh() }` was declared above the new `checkLock`/`_events` properties. Kotlin
  initializes properties and init blocks in source order, and `refresh()` runs `check()` immediately on
  `Dispatchers.Main.immediate`, so `checkLock.withLock` hit a not-yet-initialized (null) Mutex → NullPointerException.
  The properties now sit above `init` (guarded by `ToolsViewModelInitOrderTest`), and the MCP screen has its own
  lightweight `McpViewModel`.
- **Settings → Permissions.** `ui/permissions/PermissionsScreen` (+ `PermissionCatalog`, unit-tested) shows every
  permission Farrow uses with status and a Grant action: All files access (creates Documents/Farrow when granted),
  notifications (runtime + notification settings), Termux RUN_COMMAND (or Get Termux), Shizuku (get / open / grant),
  accessibility service, display over other apps, battery optimisation exemption and install unknown apps. Re-checked on
  resume; a *Set up Termux…* shortcut opens Settings → Tools for the in-Termux steps.
- **Skills index only.** The system prompt lists enabled skills as `id: name — description` (one line each, 4k chars max);
  the agent loads the full SKILL.md with `skill_get`. `skill_save` requires a one-line description (≤160 chars) and
  `skill_edit` can't blank it.

## v1.0.21

- **Find rish.** Settings → Shizuku, accessibility & Git → rish has a *Find rish* button: with All files access it scans
  `Download`, `Documents` and `Documents/Farrow/Input` (and sub-folders, 2 levels) for a `rish` script plus the companion
  it references (`rish_shizuku.dex`), takes the newest match and copies both into `files/rish` (`RishStore.find` /
  `findAndInstall`). Without the permission it says so and offers *Grant All files access*; the SAF *Pick rish file* stays
  as the fallback.

## Unreleased

- **rish permissions.** After every copy (Pick / Find rish) `RishStore.fixPermissions()` sets `rish_shizuku.dex` to
  chmod 400 (`r--------`, Android 14+ refuses writable dex files) and the script to 700; `rish_run` re-applies it before
  each run. Settings → Shizuku, accessibility & Git → rish shows the dex mode and has a *Fix rish permissions* retry button.
- **`rish_run` always sets `RISH_APPLICATION_ID=com.termux`** (instead of Farrow's package name).
