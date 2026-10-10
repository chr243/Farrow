# Repository metadata — Verdroid

## Description (GitHub "About", one sentence)

On-device AI agent for Android: free OpenRouter/Kilo LLMs with tool calling that fetch web pages over HTTP, read crypto market data, run shell commands via Shizuku, control apps with Accessibility and use Git, all in a Kotlin + Jetpack Compose app with no backend.

## Topics (15)

`android` `kotlin` `jetpack-compose` `ai-agent` `llm` `openrouter` `tool-calling` `shizuku` `accessibility-service` `material3` `hilt` `on-device-ai`

## Social preview image (Settings → General → Social preview)

- Size **1200 × 630 px** (1.91:1), PNG or JPG, under 1 MB. Keep important content inside the central ~1000 × 500 px; some platforms crop the edges.
- Background: dark green `#133121`. Accent: leaf green (the leaf + circuit mark from `app/src/main/res/mipmap-*/ic_launcher_foreground.png`; a 512 px render is in `docs/images/icon-512.png`).
- Content: the leaf + circuit logo on the left, "Verdroid" as the title, and a short tagline such as "On-device AI agent for Android: browse, shell, automate". Optionally a phone frame with the chat UI on the right.
- High contrast, at least 48 px text, no small print, no API keys or personal data in screenshots.
- TODO(maintainer): create and upload the image (no social preview exists yet; GitHub has no API for it, so upload it in the web UI). `docs/images/screenshot-placeholder.png` is only a placeholder.

## Maintainer verification checklist

The per-release checklist lives in [AGENTS.md](AGENTS.md#maintainer-verification-checklist-per-release). Repository-level checks:

- [ ] Description and topics on GitHub match this file (`gh repo view chr243/Verdroid --json description,repositoryTopics`).
- [ ] Social preview uploaded (1200 × 630).
- [ ] README screenshot/GIF placeholder replaced with a real capture.
- [ ] CI badge/workflow green on `main` (`gh run list --workflow ci.yml --limit 1`).
- [ ] LICENSE chosen and added (TODO).
- [ ] Repo visibility is intended (currently private).
