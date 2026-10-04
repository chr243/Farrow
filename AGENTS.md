# AGENTS.md — Farrow

Farrow (`com.farrow.app`) is a native Android app (Kotlin, Jetpack Compose, Material 3, Hilt, Room) that runs a tool-using LLM agent on the phone itself. It talks to free OpenRouter and Kilo models with automatic model/key fallback and rate-limit recovery, and gives the agent real tools: a Firefox browser running in Termux (driven by Termux Browser Pilot `tbp` through the token-protected Python bridge `app/src/main/assets/tbp_bridge.py` on `127.0.0.1:8765`), shell commands via Shizuku, screen control via an Accessibility service, JGit, sandboxed file tools, memory and an MCP client. There is no backend server; everything runs on the device.

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
│       │   ├── assets/tbp_bridge.py        Termux bridge (Python stdlib only, VERSION constant)
│       │   ├── assets/selectors/*.json     X / Facebook selector definitions
│       │   ├── java/com/farrow/app/
│       │   │   ├── FarrowApp.kt, MainActivity.kt
│       │   │   ├── agent/        AgentLoop, AgentRunner, backoff, context/ (summarisation), tools/ (tool implementations, registry)
│       │   │   ├── chathead/     chat heads overlay + Android Bubbles
│       │   │   ├── data/         a11y, browser (bridge client, Termux setup, StepScripts), git, local (Room), mcp, memory,
│       │   │   │                 network (OpenRouter/Kilo clients, providers, model caps), notify, prefs, repository,
│       │   │   │                 secure (encrypted key store), settings (DataStore), social (X/Facebook), tools, work (WorkManager)
│       │   │   ├── di/           Hilt AppModule (incl. tool registry)
│       │   │   ├── domain/       models, repository interfaces, use cases (no Android deps)
│       │   │   ├── keepalive/    foreground keep-alive service, boot receiver
│       │   │   ├── shizuku/      ShizukuManager, ShellExecutor, ShellUserService
│       │   │   └── ui/           Compose screens + ViewModels (chats, chat, tasks, menu, browser, device, social, theme, …)
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
./gradlew testDebugUnitTest --tests 'com.farrow.app.data.browser.*'   # a subset
```

Install and run on a device (USB or wireless debugging):

```bash
adb pair <phone-ip>:<pairing-port>     # Developer options → Wireless debugging → Pair with code
adb connect <phone-ip>:<port>
adb install -r app/build/outputs/apk/debug/Farrow-v*-debug.apk
adb shell am start -n com.farrow.app/.MainActivity
adb logcat --pid=$(adb shell pidof com.farrow.app)
```

Termux side (what the in-app setup wizard runs; Menu → Internal browser setup):

```bash
# step 1, paste by hand once:
mkdir -p ~/.termux && (grep -q '^allow-external-apps *= *true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps = true' >> ~/.termux/termux.properties) && termux-reload-settings
# steps 2–3:
pkg update && pkg install -y x11-repo tur-repo
pkg update && pkg install -y git python firefox xorg-server-xvfb xdotool xclip openbox ca-certificates
git clone https://github.com/salviz/termux-browser-pilot ~/termux-browser-pilot && bash ~/termux-browser-pilot/setup.sh
# steps 4–5 are done by the app: ~/.farrow/tbp_bridge.py + ~/.farrow/token, bridge on 127.0.0.1:8765, log ~/.farrow/bridge.log
curl -s -H "X-Bridge-Token: $(cat ~/.farrow/token)" http://127.0.0.1:8765/health   # every endpoint needs the token
```

Shizuku (needed for `run_shell`): start Shizuku from its app via **Wireless debugging**, or over adb:

```bash
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

To get a shell-uid shell inside Termux for manual debugging, export `rish` from the Shizuku app (*Use Shizuku in terminal apps*), copy `rish` and `rish_shizuku.dex` to Termux, then:

```bash
RISH_APPLICATION_ID=com.termux sh ~/rish -c id      # expect uid=2000(shell)
```

(Farrow itself does not use rish; it binds a Shizuku UserService / newProcess, see `shizuku/ShellExecutor.kt`.)

## Coding conventions

- Kotlin official code style (`kotlin.code.style=official`), 4-space indent. No formatter/linter plugin is configured besides Android lint (TODO(maintainer): decide whether to add ktlint/detekt).
- Package naming: everything lives under `com.farrow.app.<layer>.<feature>` (`ui.*`, `data.*`, `domain.*`, `agent.*`, …). Tests use the same package as the class under test, in `app/src/test/java/com/farrow/app/...`.
- DI with Hilt (`@Singleton`, `@Inject constructor`, bindings in `di/AppModule.kt`). New agent tools are registered in the tool registry there and listed in `AgentLoop` `TOOL_GROUPS`.
- JSON with kotlinx.serialization; HTTP with OkHttp/Retrofit; persistence with Room (bump the DB version and add a migration + exported schema in `app/schemas/`) or DataStore.
- `domain/` must stay free of Android dependencies.
- Bridge changes: edit `assets/tbp_bridge.py`, bump its `VERSION` and update `BridgeVersionsTest`; the app auto-updates the installed bridge when the version differs.
- Selector changes for X/Facebook go into `assets/selectors/*.json` (bump the file's `version`).
- Version bumps: `versionCode` + `versionName` in `app/build.gradle.kts`.
- Add or update unit tests for every logic change; keep pure logic out of Android classes so it is JVM-testable.

## Security rules

- Never commit secrets: API keys (`sk-or-…`, Kilo keys), GitHub tokens, bridge tokens, cookies/session files, `local.properties`, keystores (`*.jks`, `*.keystore`, `keystore.properties`) or `.env` files. They are git-ignored; do not force-add them.
- Never hard-code keys or tokens in source, tests or fixtures; use obviously fake values (`sk-or-test`).
- API keys live only in EncryptedSharedPreferences at runtime; keep them masked in UI and redacted in logs.
- The bridge must keep requiring `X-Bridge-Token` and must only listen on `127.0.0.1`. Cleartext traffic stays limited to localhost (`res/xml/network_security_config.xml`).
- Keep `allowBackup=false` and the data-extraction exclusions.
- Release signing keys are not part of this repo (TODO(maintainer): document release signing if release builds are ever published).

## Verify before opening a PR

```bash
./gradlew assembleDebug testDebugUnitTest lint
git status --short          # no local.properties, keystores, APKs or build outputs staged
git diff --cached | grep -nEi 'sk-or-[a-z0-9]{8}|ghp_[A-Za-z0-9]{20}|BEGIN (RSA|EC|OPENSSH) PRIVATE KEY' && echo 'SECRET FOUND' || echo 'no secrets'
```

If you touched the bridge, the Termux steps, Shizuku, Accessibility or social automation, also test on a real device (these paths are not covered by JVM tests) and say so in the PR.

Known flaky test: `StepScriptsTest` "bridge daemon start is single-flight…" starts a local server and occasionally fails with *connection refused*; rerun once before investigating.

## Maintainer verification checklist (per release)

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint` passes locally and the CI run on `main` is green.
- [ ] `versionCode`/`versionName` bumped in `app/build.gradle.kts`; APK is named `Farrow-v<versionName>-debug.apk`.
- [ ] If `tbp_bridge.py` changed: `VERSION` bumped and `BridgeVersionsTest` updated.
- [ ] If the Room schema changed: migration added and `app/schemas/` committed.
- [ ] Fresh install on a device: onboarding, add API key, send a chat, a tool call runs.
- [ ] Internal browser setup steps 1–5 succeed in Termux; `web_scrape` and `web_screenshot` work.
- [ ] Shizuku **Test (id)** returns `uid=2000(shell)`.
- [ ] No secrets in the diff (see command above); `git ls-files | grep -E 'local.properties|\.jks|\.keystore'` is empty.
- [ ] GitHub release created with the APK attached; README, METADATA.md and docs/ARCHITECTURE.md are up to date.
