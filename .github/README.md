# VibedByKaKi/ForgeDroid

Forgejo client for Android. Fork of [OctoDroid (gh4a)](https://github.com/slapperwan/gh4a) with GitHub support replaced by multi-instance Forgejo login.

The upstream project README is kept in [README.md](../README.md) at the repository root (updated for this fork, with credit to OctoDroid).

| [![](assets/get-it-on-github.png)](https://github.com/VibedByKaKi/ForgeDroid/actions/workflows/build-apk.yml) |
| --- |

Debug APKs are built by CI on every push. Download the latest artifact from [Actions → Build APK](https://github.com/VibedByKaKi/ForgeDroid/actions/workflows/build-apk.yml) (`ForgeDroid-debug-<build-number>.apk`, uploaded without ZIP wrapping).

## What this fork changes

- **Forgejo accounts** — Add account picks a preset instance (`codeberg.org`, `gitdab.com`, `git.kaki87.net`, `bolha.dev`, `git.gay`, `opencommit.eu`, `sij.ai`) or a custom base URL.
- **WebView login** — Sign in or register in the dialog; after a session cookie appears, the app creates a long-lived API token (customizable name, default `ForgeDroid`) and stores it with the instance URL.
- **API base** — Requests go to `{instance}/api/v1/` with `Authorization: token …` (no GitHub OAuth client).
- **Application ID** — `com.vibedbykaki.forgedroid`

## CI

- **Build APK** — runs on push, pull request, and manual dispatch; uploads `ForgeDroid-debug-<run_number>.apk` with `archive: false`.

## Credits

OctoDroid was created by Azwan Adli, Danny Baumann and [contributors](https://github.com/slapperwan/gh4a/graphs/contributors). This repository remains a fork of that work.
