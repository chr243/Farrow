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
| 4 | Internal browser: Termux/Firefox/Xvfb/TBP setup wizard, localhost bridge, human input, device fingerprint, HTTP+Jsoup fallback, web_* tools | ✅ compiled in v0.9.0 (not device-tested yet) |
| 5 | X.com automation: login detection, post, timeline/profile/search scraping, selectors file, session-expiry pause + Re-login | ✅ compiled in v0.9.0 (not device-tested yet) |
| 6 | Accessibility service (tap/swipe/type/read tree), Shizuku (UserService `run_shell`), JGit clone/status/commit/push | ✅ compiled in v0.9.0 (not device-tested yet) |
| 7 | Chat heads | ✅ (see 2.1) |
| 8 | Keep-alive foreground service (min-importance), battery-optimisation prompt, HyperOS autostart guidance, resume on boot | ✅ compiled in v0.9.0 (not device-tested yet) |
| 9 | Facebook automation (minimal): login detection, post, scrape, re-login, same engine as X | ✅ compiled in v0.9.0 (not device-tested yet) |

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
│   ├── tasks/         running / queue & paused / finished
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
| `web_scrape`, `web_click`, `web_type`, `web_session` | Phase 4: Termux Browser Pilot bridge; `web_scrape` falls back to HTTP + Jsoup |
| `x_status`, `x_post`, `x_scrape` | Phase 5: X.com through the bridge; a login wall pauses the task (`SESSION_EXPIRED`) |
| `fb_status`, `fb_post`, `fb_scrape` | Phase 9: Facebook through the same engine |
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
The bottom bar has three tabs: **Chats, Tasks, Menu**. The in-app notification list is under Menu → Notifications, and the Menu tab shows the unread badge. The Chats screen still shows the low-quota banner. All NavHost transitions are set to `EnterTransition.None`/`ExitTransition.None`, so switching tabs is instant.

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
- **UI:** a yellow story dot with a countdown, an auto-resume countdown in the chat, a notification entry, and **Force retry now** / **Cancel** in both the chat and the Tasks tab.
- Room schema v2 adds `tasks.attempt` and `tasks.pauseReason` (migration `MIGRATION_1_2`).

### Internal browser (Phase 4)
- **Setup wizard** (Menu → Internal browser setup): shows whether Termux is installed, whether `com.termux.permission.RUN_COMMAND` is granted, whether the bridge is reachable, whether `tbp` is installed and whether the TBP daemon is running. Each step has a copyable command and, once the permission is granted, a **Run in Termux** button that uses Termux's `RunCommandService`:
  1. Enable `allow-external-apps = true` in `~/.termux/termux.properties`. This one has to be pasted by hand.
  2. Install `x11-repo`, `tur-repo`, `firefox`, `xorg-server-xvfb`, `xdotool`, `xclip`, `openbox`, `python` and `git`.
  3. Clone [Termux Browser Pilot](https://github.com/salviz/termux-browser-pilot) and run `setup.sh`, which provides `tbp`.
  4. Write `~/.farrow/tbp_bridge.py` (shipped in `assets/`) and the shared token.
  5. Start the bridge on `127.0.0.1:8765`.
- **Bridge** (`assets/tbp_bridge.py`, Python stdlib only):
  - `GET /health`, `POST /cmd {cmd,args}` and a minimal WebSocket at `/ws`.
  - Wraps `tbp goto/text/html/eval/click/type/press/cookies --json`.
  - Every request needs the `X-Bridge-Token` header, because any app on the phone can reach localhost.
  - Cleartext is allowed only to `127.0.0.1`/`localhost` (`network_security_config.xml`).
- **Kotlin client:** `BridgeClient` uses OkHttp for both HTTP and WebSocket.
- **Human input:**
  - Clicks send a normalised cubic-Bézier path, which the bridge maps from the current pointer to the element centre (`mozInnerScreenX/Y`) and replays with `xdotool`, then calls `tbp click --human`.
  - Typing sends per-character delays that model ~220 WPM with jitter and pauses at punctuation.
- **Device fingerprint:** screen (physical display mode, dpi, refresh rate), GPU (renderer/vendor/version from an offscreen EGL14 pbuffer and GLES20), CPU (SoC, cores, ABIs), model, locale, timezone and the WebView UA. It can be pushed to the bridge (`~/.farrow/fingerprint.json`). TBP also auto-detects the real hardware itself.
- **Cookie sessions:** `web_session save|load|list` maps to `tbp cookies --save/--load ~/.farrow/sessions/<name>.json`.
- **Fallback:** when the bridge is down, `web_scrape` uses OkHttp + Jsoup, while `web_click` and `web_type` return a "bridge not running" error. **Limitation:** Chrome Custom Tabs can't return the DOM to the app, so they are only used to show pages to the user (for example a manual login). The HTTP fallback runs no JavaScript.


**v0.9.2 setup wizard:** each step runs in a foreground Termux session that stays open. A wrapper tees output to `~/.farrow/logs/step-N.log`, prints `✅ Step N succeeded` or `❌ Step N failed (exit X)`, and writes `step-N.status`. Results come back to the app through `RUN_COMMAND_PENDING_INTENT` (`TermuxResultReceiver`). Each card shows its status and log tail, and has a View log button. Only one step runs at a time. The apt steps wait up to 2 minutes for other apt/dpkg processes, then run `dpkg --configure -a` and `apt-get -y -o Dpkg::Options::=--force-confold`.

**Bridge "started but not reachable" (v0.9.1):** `pkill -f tbp_bridge.py` matched the `bash -c` running the step, because that shell's argv contains the script name. The step therefore killed itself, and the bridge also died when its session closed. "Failed to connect to /127.0.0.1:8765" was a refused connection, not a cleartext block: `network_security_config.xml` already allows cleartext to 127.0.0.1 and localhost. Step 5 now kills only processes whose `comm` is `python*`. It starts `setsid nohup python -u …` detached and writes `bridge.pid`, then waits up to 10 s for the port. On failure it prints the tail of `bridge.log`. The Status card has a **Connect / Re-check** button with backoff of about 10 s, and also re-checks on resume. When the check fails, it shows the `bridge.log` tail fetched via RUN_COMMAND. Bridge 1.1.0 adds a `screenshot` command (`tbp screenshot`, falling back to ImageMagick `import`). Re-run step 4 to update the bridge.

### X.com automation (Phase 5)
- **One updatable file:** every URL, DOM selector, scrape field and step script lives in `assets/selectors/x.json`. You can override it at runtime (Menu → X.com account → Selectors file, or by dropping a file at `filesDir/selectors/x.json`) and restore the bundled copy with "Reset to bundled".
- **Login detection** (`SocialAutomation.loginStatus`) checks, in order:
  1. Logged-in DOM markers (`SideNav_AccountSwitcher_Button`, …).
  2. A redirect to a login URL (`/i/flow/login`, `/login`, …).
  3. A login form in the DOM.
  4. Whether readable cookies (`twid`/`ct0`) are present. `auth_token` is HttpOnly, so `document.cookie` can't see it.
- **Tools:**
  - `x_status`: restores the saved cookie session and reports the login state.
  - `x_post {text ≤ 280}`: runs `postSteps` (compose URL → human-typed text → tweet button → waits until the composer closes).
  - `x_scrape {kind: timeline|profile|search, handle, query, limit}`: extracts `article[data-testid=tweet]` items (author, text, time, url, reply/repost/like labels), scrolls, and de-duplicates by URL.
- **Session expiry:** a login wall throws `SessionExpiredException`, and the tool flags the task in `SessionGuard`. After the step, the agent loop pauses the task with `PauseReason.SESSION_EXPIRED` (no auto-resume) and adds the status message "🔐 Paused: x session expired…". It also creates a 🔐 in-app notification and a system notification on the `alerts` channel that opens the chat.
- **Re-login:** the chat shows a **Re-login** button, which opens `relogin/x`. From there you can:
  - Log in with credentials. The bridge types them into X's login flow, including an optional challenge or 2FA code. They are never stored.
  - Import the `auth_token` and `ct0` cookies.
  - Open the login page in a Custom Tab (view only, since it uses a separate cookie jar).

  On success the cookies are saved as the `x` session. Then tap **Force retry now**.


**v0.9.2 login:** step scripts poll the page via `eval` instead of using fixed sleeps. Every step has a budget of about 15 s, and failures name the step and its selector. Bridge calls are cancellable, which powers the **Cancel** button and stops the spinner on failure. A failure shows the page URL, a page-text snippet and a screenshot in the app. `x.json` v2 targets the current `/i/flow/login` → `/i/jf/onboarding` flow:
- `autocomplete=username` / `name=text` → **Next** (matched by text)
- the optional `ocfEnterTextTextInput` unusual-activity check
- `name=password` → `LoginForm_Login_Button`
- the optional 2FA code

**Paste cookies** is now the recommended path. It accepts `auth_token` and `ct0` values, a Cookie-Editor JSON export, Netscape cookies.txt, or a `name=value; …` header (`CookieParser`). It then verifies the session by loading x.com/home.

**v0.9.4 login (x.json v3):** X's `/i/jf/onboarding` page now opens with a cookie banner, labels its field "Email or username" and uses a **Continue** button. Login steps find elements by visible text and label through `DomFinder`, a JS helper run via `eval`. It also searches open shadow roots and same-origin iframes, and tags hits with `data-farrow-target`. Steps:
1. Refuse non-essential cookies, or accept all if there is no refuse button.
2. Click and type into the input labelled "Email or username" or "Phone, email, or username" (FR variants too). Fallbacks, in order: `name=text` or `autocomplete=username`, then any visible text input in the dialog.
3. Click `/^(Next|Continue|Suivant|Continuer)$/`, skipping "Continue with …" buttons.
4. Fill Password / Mot de passe.
5. Click `/^(Log in|Se connecter|Connexion)$/`.

Elements in the main document get human-like clicks and typing through the bridge. Elements inside shadow roots or iframes get JS clicks and are filled using the native value setter plus input events.

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
- **New layout.** The bottom navigation bar is gone: the chat list is the home screen, and a gear opens **Settings** (the old Menu). **Tasks** has moved into Settings, and Back returns to the chats. The chat-head button and the pen/new-chat icon are removed. The ＋ button on the chat list starts a new chat. A ⋮ menu in the chat screen opens Chat memory.
- **Settings has rounded groups with alternating row tones** (surfaceContainerLow/surfaceContainer), and so do Tools, MCP servers, API keys and Model priority.
- **MCP servers has its own screen.** Tools now only shows the built-in tools and Termux add-ons.
- **The Rate Limit Log screen is removed.** Rate-limit handling and logging are unchanged.

**v0.9.17:**
- **Two-tier memory.**
  - *Short-term* (`memory_save` with `scope="chat"`) is a per-chat scratchpad for task progress, decisions and findings. It's always in that chat's system prompt (~1k tokens, oldest trimmed) and deleted with the chat.
  - *Long-term* (`scope="global"`, the default) holds lasting facts and preferences, shared by all chats.
  - `memory_search` searches both (only the current chat's short-term notes) and labels each hit with its scope. The prompt tells the agent which tier to use.
- **Memory screen** has two tabs, *This chat* (with a chat picker) and *Long-term*. You can add, edit and delete in both, move a note from short-term to long-term, and clear either tier. A new **⋮ > Chat memory** entry in the chat screen opens that chat's short-term memory.
- Room DB v4 (`memories.chatId` + index) with a migration, tested against the exported schema. The export file lists long-term memories and each chat's notes.

**v0.9.16:**
- **Bridge 1.8.0, updates itself:** the app compares the running bridge with the bundled one. If the running bridge is older, it rewrites `~/.farrow/tbp_bridge.py` and restarts the bridge in the background (steps 4 + 5 through Termux, no window; Firefox keeps running), at most once every 10 min. Browser setup and the X login screen also show a **"Bridge outdated (old → new), tap to update"** banner.
- **Sturdier x_post:** the compose click now has 45 s. TBP click gets up to 30 s; if it times out or fails, the app waits until TBP is idle again, then focuses the Draft.js editor with one eval. Text goes in through the new `editor_type` command (xdotool typing, checked via `innerText`, with `execCommand('insertText')`/paste as fallback). Post button: TBP click → JS click → ctrl+Return (new `key` command). Old bridges fall back to `tbp type` plus an insertText check. The selectors are now x.json v5.
- **Fast login Check:** step 1 reads the session cookies from a copy of `cookies.sqlite` plus the history URL (no ready-wait, no eval, under 1 s) and shows "cookies present" right away. Step 2 runs in the background, and only when the browser is idle: it opens x.com/home and checks that the page stays on /home and the title isn't a login page. Timings show in the card.
- **Agent memory:** stored in a Room table `memories` (DB v3) and exported to `files/memory/MEMORY.md`. New tools `memory_save` (merges duplicates), `memory_search` and `memory_delete`, with toggles on the Tools page. The system prompt carries the ~20 most important/recent memories (≤ ~1.5k tokens) and tells the agent to save lasting facts without duplicates. New Menu > Memory screen: list, search, add, edit, delete, Clear all (with confirmation) and an Automatic saving switch.
- **No more accessibility confusion:** the system prompt now groups tools into *Internal browser* (web_*, x_*, fb_*, which need no accessibility permission) and *Phone screen (accessibility)* (screen_*, which only control other apps), plus a built-in note saying so. screen_* descriptions and errors point web tasks to the browser tools.
- **Empty replies:** the nudge now asks for a 2–4 sentence progress report (done / stuck / next). The report shows as an agent message and the agent then keeps working. If the reply is empty a second time, the app shows a summary of the recent tool calls plus Continue.

**v0.9.15:**
- **x_post / x_status timeouts fixed.** TBP's goto runs location.assign() through the DevTools console, waits 3 s, hides the console and then polls document.readyState and location.href. Every poll is another console paste, which takes seconds on a phone, and X doesn't settle quickly. The app's 20 s "goto compose" budget ran out while the daemon kept running the goto, and TBP runs one command at a time. So the next eval (x_status) waited behind it until the 90 s timeout.
- **Bridge 1.7.0, new commands:**
  - `nav`: navigates with the keyboard (no JS, no wait for 'load') and returns as soon as the Firefox history shows the target URL.
  - `ready`: socket up, no command still running, console answering; waits up to 30 s and runs before every X/Facebook tool.
  - `site_status`: login state from cookies.sqlite plus the current URL and title, with no JS.
- **x_status** (and Check on the account screen) no longer navigates or runs eval.
- **x_post:**
  - Navigates with `nav`. If that isn't confirmed but the page is already the compose page, it carries on; a login URL means the session expired.
  - Waits up to 45 s for the compose box and retries evals that fail or are slow.
  - After Post, it confirms success: the toast appears or the compose box closes.
  - Errors include a per-step log (time, result, last eval error), and successful posts return it too.
- **Selectors:** bundled x.json is now v4; an override older than the bundled file is ignored.
- **Account screens** (Menu > X.com / Facebook) no longer check or scan on open. They show the last known status with its time and only check when you tap Check.
- **Themes** (Menu > Theme):
  - Mode: System, Light or Dark.
  - Palettes: Messenger Blue (default), Dynamic (Material You, Android 12+), Midnight (true-black AMOLED), Forest green, Sunset orange, Purple, Rose pink, Ocean teal. Each has its own light and dark Material 3 scheme.
  - Chat bubbles and the chat head follow the theme. The choice is saved and applies instantly.

**v0.9.14:**
- **X import "no session cookies" + Google page fixed (root cause).** TBP runs JS by pasting it into the DevTools console. On the first JS after each daemon start it "syncs" the console: ctrl+l, a ctrl+shift+k toggle, then a test paste. On a slow phone the DevTools window wasn't open in time, so the script went into the address bar and ctrl+Return ran it as a Google search (hence the consent wall). TBP then thought the console was open and every later eval leaked the same way. The import restarts the daemon, so it always hit this. Bridge 1.6.0 now opens and focuses the DevTools window right after the daemon starts and before every bridge JS command (eval, url, title, text, html, links). It detects a leak from the main window title or the last history URL, goes back with the keyboard, reopens the console and returns a clear error.
- **The import is verified without JS.** It counts rows in cookies.sqlite before the write, after the write, after the restart and after goto. The final URL comes from places.sqlite history plus the window title. `logged_in` is true when auth_token/ct0 (or c_user/xs) are present and the URL is not a login, flow or onboarding page. The app uses this result instead of an eval-based login check.
- **Cookie write fix.** `pgrep`/`pkill` are often missing on Termux. That made "Firefox gone" true at once, so cookies.sqlite could be written while Firefox was still running and then overwritten. A `/proc` cmdline scan now waits until the daemon and every Firefox on the profile have exited (kill after 20 s). The write is followed by a WAL checkpoint.
- **The daemon always restarts after an import** and its socket is confirmed, with one clean retry (or a reset if a pid hangs). If it is still down: "The internal browser did not come back" with the daemon.log tail, Copy and a **Start browser** button.
- **Consent.** A reject-all SOCS cookie is pre-seeded on google.* and youtube.com while Firefox is stopped, before each daemon start (only where missing). After a goto, a consent wall (consent.google.com, "Before you continue", "Avant d'accéder à Google"…) gets Reject all, then the target page. The cookie-banner JS clicks Reject first, in EN/FR/DE/ES/IT/NL/PT.
- **Internal browser in English by default.** Before every daemon start, user.js gets intl.accept_languages "en-US, en", intl.locale.requested en-US and javascript.use_us_english_locale. Google search goes to www.google.com with hl=en&gl=us&pws=0, and DuckDuckGo gets kl=us-en. The HTTP fallback sends Accept-Language en-US. The system prompt says to prefer English sources. New **Browser language** setting (EN/FR/DE/ES/IT) in Internal browser setup. web_scrape suggests html.duckduckgo.com for searches.
- **Remote MCP servers** (Menu > Tools > MCP servers). Supports Streamable HTTP (JSON or SSE replies, Mcp-Session-Id) and the legacy HTTP+SSE transport. Local stdio servers are not supported. Each server has a name, a URL, an optional auth header/token (stored encrypted; a bare token is sent as Bearer), an on/off switch, a status (connected with tool count, or error with Copy), and an expandable tool list with a switch per tool. You can add, edit and delete servers. The flow is initialize, then notifications/initialized, tools/list (paged) and tools/call (90 s timeout, one reconnect). Tools are exposed as `mcp__<server>__<tool>` with their input schemas. Results come back as text and errors go back to the model. Example servers, off by default (initialize, tools/list and tools/call checked from the box): DeepWiki, Context7, Microsoft Learn, Hugging Face.

**v0.9.13:**
- **X/Facebook login import, fixed for real.** TBP has no privileged cookie API: its `cookies --load` and the
  `auto_cookies.json` auto-load ("Auto-loaded 0 cookies") both run page JS (`document.cookie`). That can't set HttpOnly
  (`auth_token`, `xs`) and only works for the page that is open. Bridge 1.5.0 instead:
  1. Stops the daemon and waits for Firefox to exit.
  2. Writes the cookies straight into the profile's `cookies.sqlite` (`moz_cookies`): host `.x.com` / `.facebook.com`,
     path `/`, isSecure=1, isHttpOnly for the session cookies, SameSite=None, schemeMap=https, expiry +1 year.
     Expiry is in ms for schema 16 (Firefox ≥ 142) and in seconds before. Columns are detected per schema.
  3. Restarts the daemon, opens the home page and dismisses the cookie banner.
  4. Reads Firefox's cookie store back (a copy of the db + WAL) for the verification card, with domain, path, expiry
     and HttpOnly per cookie plus the final URL.
  Restoring a saved session is a no-op while the cookies are still in the store.
- **Menu > Tools.**
  - Every agent tool, with a short description, its status (ready, or what it needs) and a persisted on/off switch.
    Tools that are off are left out of the model's tool list and refused if called.
  - New `termux_run` tool: runs bash in Termux through the bridge.
  - "Available to install": ffmpeg, imagemagick, yt-dlp, git, nodejs, jq, curl, pandoc, installed with `pkg` in the
    background and detected with `command -v`.
- The Menu tab and its sub-screens are plain text: no leading icons/emoji on rows.
- The system prompt no longer says most tools are "not available yet".

**v0.9.12:** Termux stays out of the way.
- "Set up everything" and single steps run as background RUN_COMMAND tasks by default: no Termux window, with live
  progress and logs in Farrow. Bridge and daemon starts were already in the background.
- A new switch, "Show the Termux window during setup", opts back into a visible session. When setup succeeds there,
  the script runs `am start --activity-reorder-to-front` to bring Farrow back (the app does the same with
  `FLAG_ACTIVITY_REORDER_TO_FRONT`) and ends with `exit 0`. Termux removes sessions that end with exit code 0 and
  closes its activity once none are left. On failure the session stays open so the error can be read.
- The Termux app process is never killed.
- The bridge keeps running after the UI closes: it is detached with `setsid nohup`, and the daemon is started in a new
  session. `termux-wake-lock` keeps TermuxService in the foreground; without it the service stops once no
  session/task is left.

**v0.9.11:** Fix "daemon pid … alive but its socket doesn't answer" and the repeated daemon restarts.
Root cause: TBP's `status` action reads url/title through the Firefox devtools console. That call is serialised with
every other JS call, so it can take far longer than the 5 s bridge 1.3.0 allowed. The bridge then called a healthy
daemon an orphan and killed it, which is where "Firefox process died (rc=1)" came from. The app's next auto-start
started it again.

Bridge 1.4.0:
- Health uses TBP's own check: the unix socket accepts a connection. Details come from `tbp status --json` (10 s,
  background, cached).
- It starts the daemon only when it is definitively absent (no live pid and nothing listening).
- If a pid is alive but nothing listens, it waits up to 20 s and then reports `needs_reset`. It never kills it; only
  **Reset browser** does.
- TBP discards Firefox's stderr, so when Firefox dies the bridge runs a one-off Firefox probe and shows its stderr
  (`~/.farrow/firefox-probe.log`) in the status/log.

App: no auto-start while the daemon is unresponsive; the Reset hint is shown instead.

**v0.9.10:** Fix "TBP daemon fails to start: Another browser session is running (PID …) / remove .tbp_browser.lock".
Root cause (termux-browser-pilot `src/client.py` `ensure_daemon`): any daemon-backed `tbp` command that runs while the
daemon is still starting (pid written, socket not yet created; Xvfb and Firefox take 6+ s) deletes `~/.tbp/daemon.pid`
and spawns a second daemon. The second one crashes on the session lock while the first keeps running without a pid file,
so `tbp status` / `tbp start` stop seeing it. Concurrent starts from step 5, setup and the app made this likely.
Bridge 1.3.0:
- Health means the socket answers `status` (daemon.pid gets repaired from the lock).
- `/daemon/start` is single-flight. Before starting it removes dead locks; it kills an orphaned lock holder and its
  children (Firefox, Xvfb) plus stale daemon.pid/sock and X99 lock files. It runs `tbp start` and waits up to 30 s for the socket.
- Daemon-backed commands never run while a start is in progress.
- New `/daemon/reset`.

App:
- One mutex-guarded, debounced daemon start shared by setup, web tools and app launch.
- Step 5 asks the bridge instead of running `tbp start`.
- New **Reset browser** button with a copyable log.

X/Facebook WebView login import fixed. In Firefox mode TBP's `cookies --load` sets cookies with `document.cookie` on
the *current* page, without expiry or SameSite. Cookies loaded before reaching x.com, or sent to a dead daemon, were
silently dropped while it still reported "loaded 9".
`cookies_import` now:
- Makes sure the daemon is healthy, then opens https://x.com/ first.
- Sets every cookie on `.x.com` with path `/`, expiry +1 year, `secure` and `SameSite=None`.
- Dumps `document.cookie` and reports which of auth_token/ct0/twid/kdt/att/guest_id are present, with domain/path/expiry
  (shown with Copy).
- Reloads /home and dismisses the cookie banner.

Saved sessions are restored the same way.

Chat head: Back and Home/Recents collapse the expanded panel back to the head at its saved position.
- Back: the focused panel root handles it. If the keyboard is open, the first Back closes the keyboard.
- Home/Recents: detected via `ACTION_CLOSE_SYSTEM_DIALOGS`.
- Outside taps still collapse it too.

**v0.9.9:**
- **Log in to X / Facebook in the app:** this is now the primary option on the account screen. A full-screen WebView opens x.com/i/flow/login or m.facebook.com/login with a mobile-Chrome user agent (no `; wv` or `Version/…`). JS, DOM storage and third-party cookies are on. You log in yourself. The app checks the cookies every second and on each page load. Once auth_token + ct0 (X) or c_user + xs (Facebook) exist, it closes and imports **all** site cookies into TBP: domain .x.com / .facebook.com, path /, secure, httpOnly for the session cookies (new bridge command `cookies_import` → `tbp cookies --load`). It then verifies on the home page and saves the session. You get "Logged in as @handle". The WebView's cookies are cleared afterwards unless you turn that off. The chat's Re-login button opens this login directly. Cookie paste and the automated login are under **Other ways**.
- **TBP daemon fix (bridge 1.2.0):** the daemon counts as running only when it matches TBP's own check (`~/.tbp/daemon.pid` alive, `daemon.sock` present, and `tbp status --json` returns success). New endpoints: `GET /daemon/status` (state, `~/.farrow/tbp.log`, `~/.tbp/daemon.log` tails) and `POST /daemon/start` (`tbp start` in a new session, output in `~/.farrow/tbp.log`). **Start browser** and **Set up everything** only report success once the daemon runs. They start it if needed and wait up to 30 s; otherwise they show ❌ with the logs and Copy. Re-check shows the daemon state and logs, and has a **Start TBP daemon** button. web_* tools also start a stopped daemon automatically. Because the bridge script changed, the first **Set up everything** reinstalls step 4.
- **ADB over TCP removed:** run_shell now uses Shizuku newProcess, then the Shizuku UserService. The ADB card, the boot toggle, MiniAdb/AdbKey, their tests and the saved ADB settings and key are gone.

**v0.9.8:**
- Internal browser: one **Set up everything** button. It checks Termux, the RUN_COMMAND permission and allow-external-apps (if that is missing it shows one command to copy), runs a fast check of what is already installed (packages, tbp, bridge script sha256, token), then installs only the missing steps in **one** Termux session. It shows live progress (progress bar, per-step ✅/⏭️/❌, log with Copy) and offers **Retry from step N**, then starts the bridge + daemon and connects. When everything is installed, the button reads **Start browser** and only starts the bridge, so nothing is reinstalled after a reboot. The individual steps are under **Advanced**.
- **Auto-start bridge when needed** (default on): on app launch, at boot (only with keep-alive on) and when a web_* tool finds the bridge down, step 5 runs in the background and the app waits up to 15 s.
- Notifications: task completions no longer notify. Turn on **Notify when a task finishes** (Notifications screen, default off) to get them back. Rate limits, quota warnings, failures and re-login requests still notify.
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

### Facebook (Phase 9, minimal)
- This is the same `SocialAutomation` engine as X, driven by `assets/selectors/facebook.json` on `www.facebook.com`.
- **Login detection:** the "Your profile" / "Create a post" aria labels, a redirect to `/login` or `/checkpoint`, and the readable `c_user` cookie.
- **Tools:**
  - `fb_post` opens "Create a post", types into the dialog's contenteditable and clicks **Post**.
  - `fb_scrape` (timeline/profile/search) reads `div[role=article]`/`[aria-posinset]` items.
  - Session expiry pauses the task and shows **Re-login** (Menu → Facebook account) exactly like X. Cookie import uses `c_user` + `xs`.
- Facebook's DOM is obfuscated and changes often, so expect to update the selectors file. Checkpoints and captchas aren't automated.

## Security
- API keys are stored with EncryptedSharedPreferences (AES-256-GCM values, AES-256-SIV key names, master key in the Android Keystore). Keys are masked in the UI and redacted from debug HTTP logs.
- `allowBackup=false` and data-extraction rules exclude preferences, databases and files.
- Nothing secret is committed. `local.properties`, keystores and `.env` files are git-ignored.

## Known limitations
- (v0.9.0) Everything after Phase 2 compiles, and its 25 JVM unit tests pass, but it **has not been tested on a device**. The bridge, Shizuku, accessibility and JGit paths are only exercised at runtime.
- `web_click`/`web_type` need the Termux bridge. The HTTP + Jsoup fallback only supports `web_scrape`, without JavaScript.
- X/Facebook selectors and login scripts are best-effort and will drift as those sites change. Update `assets/selectors/*.json` (or use the in-app override). Captchas and checkpoints are not automated.
- JGit 5.x doesn't support shallow clones. `run_shell` runs as the shell uid, which can't read the app's private workspace.
- The keep-alive notification can't be fully hidden (Android rule).
- (Phase 3) If a crash happens halfway through running a step's tools, the tool calls that didn't run are dropped and the model is asked again.
- No streaming responses. Each step is a single, non-streamed completion.
- Model and key reordering uses up/down buttons (no drag-and-drop).
- Markdown rendering is minimal: headings, bullets, bold, italic, inline code and fenced code.
- Not tested against the live API from the build machine because no key was available. The parsing logic is covered by unit tests.

## v1.0.1
- **Account jobs are app-scoped:** the X/Facebook cookie import (WebView login), Check, credential login, pasted-cookie import and Start browser run in `SocialSessionManager` (`@Singleton`, own `SupervisorJob` scope; lifecycle logic in the unit-tested `SocialJobRunner`). `ReloginViewModel` only observes its `StateFlow`, so leaving the screen or backgrounding the app no longer cancels them; only the Cancel button does. While a job runs, `SocialSessionService` (specialUse foreground service) shows a silent "Importing X login…" notification with the step and a Cancel action. The last result (message, error, cookie report, diagnostics, timings, screenshot path) is persisted (`social_session` prefs) and shown when the screen is reopened.
- **Atomic cookie import (bridge 1.10.0):** `job_start {"cmd": "cookies_import"}` runs stop Firefox → write cookies.sqlite → always restart the daemon in a bridge thread and returns a job id at once; the app polls `job_status`. Cancelling or losing the app only stops the waiting; the bridge always finishes the write and restart. Results persist in `~/.farrow/jobs/<id>.json`; a reopened screen waits for an import still pending from before the app was killed.
- **X replies scrape:** `x_scrape kind=replies url=<post>` returns only `article[data-testid="tweet"]` posts inside `[data-testid="primaryColumn"]` after the focal post, stopping at "Discover more". The side nav/account switcher, inline reply composer, focal post and entries without a status permalink are dropped. The logged-in handle (from `AppTabBar_Profile_Link`) is reported as `logged_in_as`, and your own real replies get `is_self: true`. Parsing is done in Kotlin with Jsoup (`ThreadReplies`, tested with fixture HTML); the spec lives in `selectors/x.json` v6 (`replies`, `textScope`).
- **web_scrape on x.com/twitter.com** (no selector) returns only the main column text, without header, nav, sidebar, account banner or composer.

## v1.0.2
- **`x_reply` (deterministic replies):** `x_reply(url, text, mode=reply|quote)`. Opens the post, waits for the focal post (`primaryColumn article[tabindex=-1]`) and clicks ITS reply button (modal composer). If no modal opens, it falls back to the conversation's inline reply box. Composer choice is made in Kotlin (`ReplyComposer`, Jsoup, fixture-tested) over a marked snapshot of the conversation column and dialogs: the top dialog's `tweetTextarea_0`, else the inline one (never one inside a post). Submit is only the `tweetButton`/`tweetButtonInline` in the same container. `scheduleOption`, GIF, poll, emoji, location and media controls are never clicked. Typing uses the x_post editor method, and the text is verified before submit. A schedule dialog, a dialog without a reply box or a wrong URL (`/compose/post/schedule`, unsent…) is closed with Escape plus the dialog's close button, and it retries once via `https://x.com/intent/post?in_reply_to=<id>`. There is no retry after the submit click. Success means a toast (its View link), or the composer closing/clearing, plus the new reply from the logged-in account found via the replies scrape (`reply_url`, `verified`). On failure it returns the error, the step log, the page URL/text and a screenshot (`image_path`, shown in the tool card). `mode=quote` posts `text + post URL` with the x_post steps (X shows it as a quote). Spec: `selectors/x.json` v7 `reply` + `urls.replyIntent`.
- The system prompt says replies/comments on X must ALWAYS use x_reply, never web_click/web_type.

## v1.0.3
- **Stray-dialog cleanup for x_reply / x_post** (`StrayDialogs`, Jsoup, fixture-tested; spec `selectors/x.json` v8 `reply.stray`).
  - **Detection:** an overlay is any `[role=dialog]`, `[aria-modal=true]`, `div[data-testid=sheetDialog]`, any `#layers` child with interactive content (toasts and hover cards are ignored), or anything around `unsentButton`, `scheduledConfirmationPrimaryAction` or `app-bar-close`. An overlay that isn't our own reply composer is stray.
  - **Closing:** one action per round, re-checking after each: Discard on a Save/Discard sheet (never Save or Enregistrer), then the close button, then Escape, then the mask.
  - **Fallback:** if a stray stays open, or the URL isn't the post (`/compose/`, `/schedule`, `/unsent`, `/drafts`, `/i/flow/`), it hard-navigates with `location.replace` to the clean post URL and waits for the focal post. If that fails, it goes to x.com/home and back.
  - **When:** at the start of x_reply and x_post, and before every retry.
  - **Wrong-state markers:** `unsentButton` and the `/unsent` and `/drafts` URLs were added.

## v1.0.4
- **x_reply: the reply bubble is now the primary path.**
  1. Clean up stray dialogs.
  2. Find the target post (`ReplyComposer.target`): the conversation article whose own permalink has the URL's status id, so a URL pointing at a reply targets that reply. Otherwise use the focal article.
  3. Scroll its `[data-testid=reply]` bubble (from its own action bar, never inside a quoted post) to the centre and click it with a TBP human click, the same trusted click x_post uses. A second TBP click is tried after the browser is idle; there is no synthetic `el.click()`.
  4. Wait ≤ 5 s (`reply.bubbleWaitMs`) for the dialog's `tweetTextarea_0` and check that `document.activeElement` is inside it (focus it otherwise).
  5. Type with the x_post method, verify the text, and submit with the dialog's `tweetButton`.
  6. Fallbacks: the inline box, then the intent URL on the retry.
  The step log and the tool result (`path`) say which path was used.
- **Why v1.0.2/1.0.3 could skip or miss the bubble:** the bubble was only looked for under `article[tabindex="-1"]`, so a missing tabindex or a URL pointing at a reply silently fell through to the inline box. A failed TBP click fell back to a synthetic JS click, which X can ignore. The bubble wasn't scrolled into view before the mouse path was replayed. The page-loaded check (`ensureCleanPost`) also depended on the focal selector. All four are fixed; selectors are in `x.json` v9.

## v1.0.5
- **Fast x_scrape (timeline / profile / search / replies), `FastScrape.kt`.** The v1.0.4 path was slow for these reasons:
  - It used TBP `goto` even when the page was already open. That is `location.assign` through the console, a fixed 3 s pause, then `readyState` polls (each poll is another console paste), and X rarely settles.
  - `waitFor` polled at 600 ms intervals with a separate eval per check.
  - Each scroll was a TBP `scroll` command followed by a fixed 1.2 s sleep.
  - Images and video downloaded during the scrape.

  Now:
  - The first eval reads `location.href`. If the page already is the target (the path regex, plus `q` for search), navigation is skipped. If it's scrolled down, it is scrolled to the top first.
  - Otherwise it uses keyboard `nav`, which doesn't wait for `load`.
  - Then it polls every 150 ms with ONE eval per poll. That eval extracts every post, using the unchanged v1.0.4 field JS and the same dedupe. It returns as soon as `limit` unique posts are there.
  - It scrolls (`scrollBy` inside the same eval) only when the count has stopped growing for 0.7 s. The stop rules are the same as v1.0.4.
  - No screenshots or diagnostics on success.
  - Results gain `timings_ms` (ready, locate, nav, wait_first_posts, polls, parse, restore_media, total) and `steps`.
- **Media blocked during X scrapes (`blockMedia` in x.json v10).** TBP drives Firefox without Marionette or WebDriver. There is no runtime privileged context, and prefs would need a restart. So a page-scope request filter does the job, in the same eval:
  - `src`/`srcset`/`poster` on img/source/video (property and `setAttribute`) are held back.
  - CSS background images are suppressed.
  - `play()` is refused like an autoplay block (NotAllowedError).
  - The last poll releases it once the page holds `limit` posts, otherwise one restore eval does. Held media then loads normally, so web_screenshot and x_post media are unaffected. It also lapses by itself after 45 s.
  - Images that started before the first poll after a fresh navigation still load.
- **x_reply always on the post itself (`StatusUrl.kt`).**
  - The URL is normalized to `https://x.com/<user>/status/<id>`: twitter.com and mobile.x.com are accepted, query and fragment stripped, /photo/, /video/ and /analytics suffixes dropped, and a bare id becomes /i/status/<id>. Anything else is an error.
  - x_reply hard-navigates unless `location` is exactly that status path. It then waits until the location is the post AND the article whose own permalink is the id is present. v1.0.4 accepted any `primaryColumn article`, so on /home the feed counted as "the post" and it never navigated.
  - x_scrape items carry `status_url`. The x_reply url description says: url = the post's status URL from x_scrape.
- **In-app updater (`data/update/`).** Settings > App > App update shows the installed version and a "Check for updates" button.
  - It reads the public GitHub API `releases/latest` (no token) and compares versions numerically (`UpdateLogic.compare`, pre-releases rank below their release). It then shows "Up to date" or "vX.Y.Z available" with the release notes and an Update button.
  - Update downloads `Farrow-*-<buildType>.apk` (`pickAsset`) with OkHttp into `cacheDir/updates`, with a progress bar, and checks that the size matches the asset.
  - It then opens the system installer through FileProvider (`${applicationId}.updates`) + ACTION_VIEW (`REQUEST_INSTALL_PACKAGES`). Without the "Install unknown apps" permission it opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Farrow first.
  - The same debug key means it installs over the app. A silent check runs on app start at most every 6 h, and a dot on the gear and the row marks an available update.

## v1.0.6
- **Why the agent bypassed x_reply** (phone run: web_type 'testing.. testing...' + web_click `button[aria-label="Reply"]` → "Save post?", text truncated, screen locked):
  - web_click and web_type had no X guard, and their descriptions didn't steer away from X composers.
  - Most x_reply errors were bare `{"error": …}` with no step log and no "don't fall back" hint. Only ReplyFailed had that hint, and those results started with `"ok":false`, so the registry logged them as success.
  - x_reply was only registered when the loaded selectors had a reply spec.
  - The prompt rule was soft. A weak model improvised after the first error.
- **Generic tools are guarded (`ComposerGuard`).** On x.com/twitter.com, web_click and web_type refuse compose surfaces with "Use x_reply (comments/replies) or x_post (new posts); generic clicks and typing on X composers are blocked":
  - tweetTextarea_* and tweetButton*
  - the reply bubble
  - Reply/Post/Répondre/Poster buttons
  - an inline reply box or a compose dialog
  - the "Save post?" sheet

  The check is one eval (`closest()` on the resolved element) plus a selector check, which fails closed when the page doesn't answer. There is no web_key tool.
- **x_reply is always registered on X.** Every error carries the step log and a note: no web_click/web_type, at most once more. The prompt rules are strict.
- **Before the submit (x_reply and x_post `settleSubmit`):**
  - The composer text must EQUAL the intended text, unchanged for 500 ms (`SubmitGuard.Settle`), and the button must not have aria-disabled="true".
  - Then exactly ONE trusted click. If TBP reports a failure, the app first checks whether the click went through before trying a JS click, so it never submits twice.
- **After the submit:** a "Save post?" sheet means the text was not sent.
  - x_reply discards it and retries the submit at most once.
  - x_post reports the failure.

  v1.0.5 counted a truncated box ("testing...") as "composer cleared". Now only an empty or closed composer that stays that way for about 1 s counts as success.
- **Loop protection:**
  - x_reply and x_post have a 60 s budget each. A reply timeout after the submit returns "submitted, not confirmed" with "do NOT reply again".
  - At most 2 composer attempts.
  - AgentLoop stops the task when x_reply targets the same post (by id: /i/status, twitter.com and the canonical URL count as one) more than twice since the user's last message.
  - The intent URL and the /i/status reload no longer wait 30 s for a history match that can't come, because they redirect.
  - The bubble is clicked only on the article identified by its own permalink. A focal-only match uses the inline box instead.

## v1.0.7
- **x_reply goes through the intent composer first.** Attempt 1 opens `x.com/intent/post?in_reply_to=<id>` (the `/compose/post` dialog). Before typing, `IntentComposer.check` requires X's "Replying to @author" line (several languages), so a lost `in_reply_to` can't turn into a standalone post. Attempt 2 is the bubble path on the canonical post URL. The intent path has no stray-dialog URL and confirms through the toast link.
- **Grok shield (`GrokShield`).** On the v1.0.6 phone run, X's Grok drawer covered the post. `ReplyComposer.locate` took it for the top dialog ("a dialog without a reply box"), and x_reply spent its 60 s on it. While x_reply, x_post and the scrapes run, a page-scope MutationObserver marks Grok drawers, panels and buttons with `data-farrow-grok`, clicks their close button and hides them. It never touches a root that contains a composer, a post or the main column, and it lapses after 90 s. Snapshots (`StrayDialogs`, `ReplyComposer.locate`) ignore Grok overlays.
- **Images and video blocked in the internal browser.**
  - Bridge 1.11.0 adds `set_media {load_images}`. It writes `~/.farrow/load_images` and Firefox's `user.js` prefs. Images blocked: `permissions.default.image=2`, `media.autoplay.default=5`, `media.autoplay.blocking_policy=2`. Images on: 1/1/0.
  - The prefs are written again before every daemon start. Firefox reads them only at startup, so they apply the next time the internal browser starts. **A restart is never forced.**
  - Settings → Internal browser → "Load images" (default off).
  - Until the pref is active, the page-level filter blocks media for 10 min on pages used by the X tools, the scrapes, web_scrape, web_click and web_type. Scrapes no longer restore images.
  - `web_screenshot load_images=true` releases what the page filter held back.
- **Browser setup actions are app-scoped (`BrowserOpsManager`).**
  - The affected actions are Set up everything / Start browser, Start TBP daemon, Reset browser, Update bridge and single setup steps. They ran in the screen's `viewModelScope`, so leaving Settings stopped them midway. This was the same bug as the v1.0.1 cookie import.
  - They now run in a SupervisorJob app scope, one at a time. `BrowserOpsService` shows a foreground notification with the current step and Cancel.
  - State and the last result are persisted (shown as "Last browser action" on return and after an app restart). The screen only observes it.
- **Atomic reset.** Bridge 1.11.0 runs `reset` (also `start`/`stop`) as a detached `job_start` job, like the cookie import. The app only waits for it, and the job id is persisted, so after an app restart the app waits for the same job again. Cancel only stops waiting. Older bridges fall back to `POST /daemon/reset`.
- **Audit of other screen-scoped jobs:**
  - The app update check and download moved from the Settings composable scope to `AppUpdater`'s app scope. The installer opens by itself only while the row is visible; otherwise tap Install.
  - Termux `pkg install` (up to 15 min) moved from the Tools ViewModel to `TermuxPackageJobs`.
  - MCP reconnect already runs in `McpManager`. Memory edits and key/model edits are short DB/DataStore writes and stay in the screen scope.
- x.json v12.

## v1.0.8
- **Root cause of the v1.0.7 phone failure (intent composer opened, text never typed, 60 s timeout):**
  - The intent composer opens as a modal over /home. The home timeline's own composer (also `tweetTextarea_0`) stays behind it and comes first in the document.
  - The intent wait (`waitFor(tweetTextarea_0)`) was satisfied at once by that hidden composer, so the composer was read before the modal's editor had settled.
  - Typing then went to `[data-farrow-i=N]`, a transient snapshot mark that X's re-render can drop. It used only a programmatic focus, without x_post's trusted click.
  - Its 30 s + 400 ms/char budget, plus the settle wait and the second attempt, used up the 60 s budget without a clear error.
- **Fix: x_reply types with x_post's exact routine, scoped to the dialog.**
  1. `ReplyComposer.editorTargetJs` finds the contenteditable inside the top non-Grok dialog that holds a reply box. The snapshot mark is used only if it is inside that dialog. It marks the editor `data-farrow-editor="1"` and scrolls it to the centre.
  2. x_post's trusted click (`sturdyClick`) on that editor.
  3. A check that `document.activeElement` is inside it (else a JS focus).
  4. x_post's `typeIntoEditor`: bridge `editor_type` (focus → xdotool → verify → insertText).
  5. The editor's `textContent` is verified for up to 2 s. If the text is still missing: one retry with refocus + `execCommand('insertText')`, then a synthetic paste (`ClipboardEvent` + `DataTransfer`). After that x_reply fails fast ("typing failed in the modal reply composer: …; nothing was posted") instead of running into the budget.
- The intent path now waits for `[role=dialog] [data-testid=tweetTextarea_0]` and rejects a non-modal pick (a page box behind the modal).
- The step log names the target: testid, class, bounding rect, inDialog, focused, number of reply boxes, chars. It also has stage timings (open composer / focus / type / verify / retry / settle / submit, plus a summary on failure or budget timeout).

## v1.0.9
- **x_reply now uses x_post's implementation** (`SocialAutomation.composeAndSubmit`, shared by both). The separate reply typing path is removed: `replyAttempts`, `openComposer`, `focusAndType`, the snapshot-mark editor, `submitOnce`, `confirmReply` and the stray/post-page retries. Only the opening differs, plus a "Replying to @author" pre-check.
- **What x_post did differently from x_reply (v1.0.8):**
  - **URL:** x_post opens `https://x.com/compose/post` with a `goto` step (nav, 40 s). x_reply opened `intent/post?in_reply_to=<id>`, which redirects, with a 10 s nav.
  - **Editor:** x_post uses x.json's plain `composeText` = `[data-testid="tweetTextarea_0"]` everywhere (TBP click, `editor_type`, settle). TBP resolves it with `document.querySelector`, i.e. the first match in the document. x_reply typed into an element it had resolved and marked itself (`data-farrow-i` from a page snapshot, then `data-farrow-editor` inside `[role=dialog]`).
  - **Waits:** x_post waits up to 45 s for `composeText` and goes straight to the click. x_reply waited 15 s for the dialog's box, then ran a stray-dialog cleanup, a page snapshot, a context eval and an editor-resolve eval before clicking.
  - **Click:** both use `sturdyClick`, but x_post allows 30 s for the TBP click and x_reply allowed 10 s.
  - **Typing:** both use `typeIntoEditor` / bridge `editor_type`, but x_post's budget is 45 s + 400 ms/char and x_reply's was 12 s + 150 ms/char. x_reply also added its own verify/insertText/paste retry.
  - **Submit and confirmation:** x_post runs `settleSubmit` on `composeText`/`composeSubmit`, clicks `composeSubmit` (ctrl+Return fallback) and waits for the success toast or the box closing (`waitPosted`). x_reply clicked a marked send button once and polled its own toast/emptied-box probe.
- **x_reply now:**
  - **Path A:** `/compose/post?in_reply_to=<id>`, then x_post's steps. The new `target` step runs right after `waitFor composeText`. It logs what x_post's selector resolves to (testid, class, rect, inDialog, inConversation, inArticle, focused, number of boxes, chars, URL). For x_reply it also requires that box to be inside the composer dialog with "Replying to @author" (polled for up to 4 s). This happens before anything is clicked or typed.
  - **Path B** (pre-check failed, e.g. the first `composeText` is the home box behind the modal): the post page, then x_post's steps on the conversation's inline reply box under the post. The reply bubble is clicked only when there is no inline box.
  - Any other step failure stops x_reply ("…; nothing was posted"), or reports "submitted, not confirmed" after the submit click.
- **x_post** is unchanged apart from the logging `target` step (8 steps).
- The x_reply budget is now 90 s (two opens with x_post's own waits). The step log keeps the v1.0.8 diagnostics: the target line and per-path timings with a summary.

## v1.0.10
- **Root cause, reproduced on the box** with the phone's stack: Firefox ESR, TBP (xdotool plus DevTools-console evals) and this `tbp_bridge.py`, against `tools/draftjs-repro/`. That page is X-like and uses the real draft-js 0.11.7: a home inline composer, plus a reply modal with "Replying to @…", autofocus and a focus trap. Both editors carry `data-testid="tweetTextarea_0"`.
  - The steps passed X's generic `composeText` selector to the bridge. `document.querySelector` returns the FIRST match, which is the home composer behind the modal, so `editor_type` focused that box. TBP evals run in the DevTools window, so the focus was only applied when Firefox's window was re-activated.
  - With X-like focus-trap behaviour, the keys went into the modal (the caret blinked there), but the bridge verified the home box and saw 0 chars. Its console `execCommand('insertText')` fallback then knocked the Draft.js modal out of the page in the fixture.
  - Without a focus trap, the keys went into the hidden home box and the bridge reported success, while the modal stayed empty.
  - **x_post** has the same latent bug whenever /compose/post shows the home timeline behind the modal. Its `composeSubmit` (`tweetButton, tweetButtonInline`) also resolves to the first match.
- **Fix, app side:** the `target` step (after `waitFor composeText`, before any click or typing) now picks THE composer: the box in the topmost open dialog, else the first box. It marks the editor `data-farrow-compose="1"` and that composer's own submit button `data-farrow-submit="1"`. The following click, editor_type, settleSubmit, submit and waitPosted steps use these unique marks. The step log shows when the first match was another box. This applies to both x_post and x_reply, because both use the same implementation.
- **Bridge 1.12.0 `editor_type`:**
  - It takes a unique `selector` (it warns when the selector is not unique), or `active: true`, which types into `document.activeElement`.
  - For single-line ASCII it types a 3-character probe with xdotool, verifies THAT element, then types the rest and verifies again.
  - If the probe did not land, and for non-ASCII or multi-line text, it does a real clipboard paste (xclip + ctrl+v), which Draft.js handles. These choices are proven on the fixture: xdotool drops é/✓ and loses characters after shift+Return, and a synthetic `ClipboardEvent` from the console is ignored by Draft.js.
  - It fails fast (about 9 s on the box) with diagnostics: the target, the number of matches, activeElement, and where the text went.
  - Console insertText is no longer used.
- **App verification:** the app reads the marked editor's text back after the bridge reports success. On a bridge-1.12 failure it fails at once with the bridge's diagnostics.
