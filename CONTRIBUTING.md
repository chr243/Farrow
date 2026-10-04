# Contributing to Farrow

Thanks for helping! Farrow is a small personal project, so keep changes focused and well tested. AI coding agents: read [AGENTS.md](AGENTS.md) first, it has the repo map, conventions and security rules.

## Steps

1. **Open an issue first** for anything bigger than a small fix (use the bug or feature template).
2. **Fork and branch** from `main`: `git checkout -b fix/short-description` (or `feat/…`, `docs/…`).
3. **Set up:** JDK 17, Android SDK (`platforms;android-35`, `build-tools;35.0.0`), and `echo "sdk.dir=$ANDROID_HOME" > local.properties` (never commit it).
4. **Make the change** following the conventions in [AGENTS.md](AGENTS.md#coding-conventions). Add or update unit tests in `app/src/test/java/com/farrow/app/...`.
5. **Verify** (required, same as CI):

   ```bash
   ./gradlew assembleDebug testDebugUnitTest lint
   ```

   Lint runs with `abortOnError = true`: any lint error fails the build. Fix errors rather than suppressing them; if a suppression (`@SuppressLint`) is truly needed, add a comment explaining why. Don't add new warnings.
6. **Device-test** if you touched the Termux bridge, setup steps, Shizuku, Accessibility, chat heads or social automation (JVM tests don't cover these).
7. **Commit** with a clear message (imperative, e.g. `Fix bridge restart when port is busy`) and open a PR against `main` using the template.

## PR checklist

- [ ] `./gradlew assembleDebug testDebugUnitTest lint` passes locally
- [ ] Tests added/updated for logic changes
- [ ] No secrets, `local.properties`, keystores, APKs or build outputs committed
- [ ] Bridge `VERSION` bumped + `BridgeVersionsTest` updated (if `tbp_bridge.py` changed)
- [ ] Room migration + `app/schemas/` export (if the database changed)
- [ ] Device-tested (if runtime-only paths changed), with device/Android version noted
- [ ] Docs updated (README / AGENTS.md / docs/ARCHITECTURE.md) where behaviour changed

## Reporting security issues

Don't open a public issue for vulnerabilities (for example a way to bypass the bridge token). TODO(maintainer): add a private contact or enable GitHub private vulnerability reporting.
