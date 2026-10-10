# AGENTS.md — Farrow

Farrow (`com.farrow.app`) is a native Android app (Kotlin, Jetpack Compose, Material 3, Hilt, Room) that runs a tool-using LLM agent on the phone itself. It talks to free OpenRouter and Kilo models with automatic model/key fallback and rate-limit recovery, and gives the agent real tools: keyless multi-engine web search (`web_search`, a port of hec-ovi/websearch-skill), plain HTTP fetching with a Markdown reader (`web_fetch`), Coinbase Exchange crypto tools, shell commands via Shizuku, shell commands through rish (`rish_run`, from `/data/local/tmp/farrow_rish`), bash in Termux (`termux_run`, via Termux's RUN_COMMAND service), headless Chromium + Selenium inside Termux (`selenium_*`, `termux_python` for agent-written scrapers), ebook/document translation in Termux Python (`ebook_translate`, estimate → user confirms → translate), PDF read/light edit in Termux Python (`pdf_*`: info, text extract with page markers, split/merge, annotate; PyMuPDF with pypdf fallback), screen control via an Accessibility service, JGit, sandboxed file tools, a shared `Documents/Farrow` folder (`workspace_*`, All files access; chat **Attach file** copies into `Input/`), memory, agent-writable skills (`skill_*`, `files/skills/<id>/SKILL.md`, Settings → Skills), a Permissions screen and an MCP client. There is no in-app browser and there are no X/Facebook tools (removed in 4bcca87). There is no backend server; everything runs on the device.

## Repo map

```
.
├── app/
│   ├── build.gradle.kts            app module (namespace/applicationId com.farrow.app, versionCode/versionName)
│   ├── schemas/                    Room schema JSON exports (commit changes when the DB version changes)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── aidl/com/farrow/app/        IShellService (Shizuku UserService)
│       │   ├── java/com/farrow/app/
│       │   │   ├── FarrowApp.kt, MainActivity.kt
│       │   │   ├── agent/        AgentLoop, AgentRunner, backoff, context/ (summarisation), tools/ (tool implementations, registry)
│       │   │   ├── chathead/     chat heads overlay + Android Bubbles
│       │   │   ├── data/         a11y, crypto (Coinbase Exchange), ebook (translator script for Termux), git,
│       │   │   │                 termux (RUN_COMMAND manager, result receiver, Set up Termux flow, Selenium helper script),
│       │   │   │                 local (Room), mcp, memory, network (OpenRouter/Kilo clients, providers, model caps), notify,
│       │   │   │                 prefs, repository, secure (encrypted key store), settings (DataStore), skills (SkillStore),
│       │   │   │                 storage (Documents/Farrow SharedFolder, ChatAttachment), tools (Termux add-ons, tool status),
│       │   │   │                 update, websearch (web_search engines + fusion), work (WorkManager)
│       │   │   ├── di/           Hilt AppModule (incl. tool registry)
│       │   │   ├── domain/       models, repository interfaces, use cases (no Android deps)
│       │   │   ├── keepalive/    foreground keep-alive service, boot receiver
│       │   │   ├── shizuku/      ShizukuManager, ShellExecutor, ShellUserService, RishStore (+ RishRunner for rish_run)
│       │   │   └── ui/           Compose screens + ViewModels (chats, chat incl. ToolStacks, menu, device, tools, permissions, skills, theme, …)
│       │   └── res/                        resources (launcher icon = vector S-curve, colour #3D5A3A on #FBF3E6)
│       └── test/java/com/farrow/app/       JVM unit tests (JUnit 4), mirroring the main package layout
├── gradle/libs.versions.toml       version catalog (AGP 8.7.3, Kotlin 2.1.0, KSP, Hilt, Room, Compose BOM)
├── gradle/wrapper/                 Gradle 8.11.1 wrapper (always use ./gradlew)
├── docs/ARCHITECTURE.md            detailed architecture notes and version history
├── .github/                        CI workflow, issue and PR templates
└── AGENTS.md, README.md, CONTRIBUTING.md, METADATA.md
```

## Build, test, run

Toolchain: JDK 17, Android SDK with `platforms;android-35` and `build-tools;35.0.0` (compileSdk 35, targetSdk 35, minSdk 30).

```bash
# one-time: point Gradle at the SDK (local.properties is git-ignored, never commit it)
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/Farrow-v<versionName>-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew lint                   # Android lint; abortOnError = true, so any lint *error* fails the build (0 errors, ~50 warnings at v1.0.0; no baseline)
./gradlew assembleDebug testDebugUnitTest lint   # exactly what CI runs
./gradlew testDebugUnitTest --tests 'com.farrow.app.data.crypto.*'   # a subset
```

Install and run on a device (USB or wireless debugging):

```bash
adb pair <phone-ip>:<pairing-port>     # Developer options → Wireless debugging → Pair with code
adb connect <phone-ip>:<port>
adb install -r app/build/outputs/apk/debug/Farrow-v*-debug.apk
adb shell am start -n com.farrow.app/.MainActivity
adb logcat --pid=$(adb shell pidof com.farrow.app)
```

Shizuku (needed for `run_shell`): start Shizuku from its app via **Wireless debugging**, or over adb:

```bash
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

`run_shell` binds a Shizuku UserService / newProcess (`shizuku/ShellExecutor.kt`). `rish_run` instead uses the `rish` + `rish_shizuku.dex` exported by Shizuku (*Use Shizuku in terminal apps*): Settings → Shizuku, accessibility & Git → rish → **Find rish** / **Pick rish file** stages them privately, then copies them through Shizuku to `/data/local/tmp/farrow_rish/` with `chmod +x` (`shizuku/RishStore.kt`). Manual check over adb:

```bash
adb shell 'RISH_APPLICATION_ID=com.termux sh /data/local/tmp/farrow_rish/rish -c id'   # expect uid=2000(shell)
```

## Coding conventions

- Kotlin official code style (`kotlin.code.style=official`), 4-space indent. No formatter/linter plugin is configured besides Android lint (TODO(maintainer): decide whether to add ktlint/detekt).
- Package naming: everything lives under `com.farrow.app.<layer>.<feature>` (`ui.*`, `data.*`, `domain.*`, `agent.*`, …). Tests use the same package as the class under test, in `app/src/test/java/com/farrow/app/...`.
- DI with Hilt (`@Singleton`, `@Inject constructor`, bindings in `di/AppModule.kt`). New agent tools are registered in the tool registry there and listed in `AgentLoop` `TOOL_GROUPS`.
- JSON with kotlinx.serialization; HTTP with OkHttp/Retrofit; persistence with Room (bump the DB version and add a migration + exported schema in `app/schemas/`) or DataStore.
- `domain/` must stay free of Android dependencies.
- Version bumps: `versionCode` + `versionName` in `app/build.gradle.kts`.
- Add or update unit tests for every logic change; keep pure logic out of Android classes so it is JVM-testable.

## Security rules

- Never commit secrets: API keys (`sk-or-…`, Kilo keys), GitHub tokens, crypto exchange keys, `local.properties`, keystores (`*.jks`, `*.keystore`, `keystore.properties`) or `.env` files. They are git-ignored; do not force-add them.
- Never hard-code keys or tokens in source, tests or fixtures; use obviously fake values (`sk-or-test`).
- API keys live only in EncryptedSharedPreferences at runtime; keep them masked in UI and redacted in logs.
- Crypto exchange keys live only in EncryptedSharedPreferences (`crypto_secure`); live trading tools stay off by default.
- No cleartext traffic (`res/xml/network_security_config.xml`).
- Keep `allowBackup=false` and the data-extraction exclusions.
- Release signing keys are not part of this repo (TODO(maintainer): document release signing if release builds are ever published).

## Verify before opening a PR

```bash
./gradlew assembleDebug testDebugUnitTest lint
git status --short          # no local.properties, keystores, APKs or build outputs staged
git diff --cached | grep -nEi 'sk-or-[a-z0-9]{8}|ghp_[A-Za-z0-9]{20}|BEGIN (RSA|EC|OPENSSH) PRIVATE KEY' && echo 'SECRET FOUND' || echo 'no secrets'
```

Termux tools wrap commands with `TermuxRunTool.capped(...)` (output to temp files, stdin `/dev/null`, leftovers killed) so they return as soon as the command exits; keep that wrapper when adding Termux-backed tools. `ebook_translate` chunks must stay below 5000 characters (tested).

Agent-initiated package installs (pip / apt / pkg / npm / gem / cargo) must go through `agent/tools/InstallConsent`: the tool returns `needs_install_confirmation` (packages, reason, estimate, `install_id`) and installs nothing; the agent asks the user and only re-calls with `confirm_install=true` + `install_id` after a yes (`false` = declined). Tools with a built-in setup run a probe first (`setupCommand(allowInstall = false)`). Settings → Tools add-ons the user taps Install on bypass the gate (the tap is the consent).

If you touched Shizuku, rish, Termux, Accessibility or chat heads, also test on a real device (these paths are not covered by JVM tests) and say so in the PR.

## Maintainer verification checklist (per release)

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint` passes locally and the CI run on `main` is green.
- [ ] `versionCode`/`versionName` bumped in `app/build.gradle.kts`; APK is named `Farrow-v<versionName>-debug.apk`.
- [ ] If the Room schema changed: migration added and `app/schemas/` committed.
- [ ] Fresh install on a device: onboarding, add API key, send a chat, a tool call runs.
- [ ] `web_search` returns fused results, and `web_fetch format=markdown` returns a public page.
- [ ] Shizuku **Test (id)** returns `uid=2000(shell)`; rish **Test (id)** too if rish is set up.
- [ ] `termux_run` with `echo hi` returns within a few seconds (not after `timeout_seconds`).
- [ ] `ebook_translate` on a small file in `Input/` returns an estimate first, then translates into `Output/` after confirming.
- [ ] With a package missing, `termux_run` `pip install …` / `pdf_info` / `ebook_translate` ask before installing; "no" installs nothing, "yes" installs and continues.
- [ ] No secrets in the diff (see command above); `git ls-files | grep -E 'local.properties|\.jks|\.keystore'` is empty.
- [ ] GitHub release created with the APK attached; README, METADATA.md and docs/ARCHITECTURE.md are up to date.
