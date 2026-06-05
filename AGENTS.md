# AGENTS.md — Working with the MiniCPM-V-Apps repo

> Conventions for AI coding agents (Cursor / Claude Code / Codex / etc.) working on this
> repository. Read this once before making your first commit or PR. Human contributors are
> welcome to follow the same conventions.

---

## 0. What this repo is

A cross-platform **iOS / Android / HarmonyOS** demo for the MiniCPM-V family of multimodal
LLMs. All three clients run fully on-device via a shared `llama.cpp-omni` submodule.

This repository holds **demo source code + docs only**. Model weights are distributed via
HuggingFace / ModelScope / OBS and are **not** stored here. Compiled artefacts
(`llama.xcframework`, Gradle / Hvigor / Xcode build outputs, IDE caches) are intentionally
**not tracked** — they are produced locally as part of the build (see §3 and the README).

---

## 1. Repository layout

```
MiniCPM-V-Apps/
├── MiniCPM-V-demo/              iOS Swift sources
├── MiniCPM-V-demo.xcodeproj/    Xcode project
├── MiniCPM-V-demo-Android/      Android Kotlin + NDK sources
├── MiniCPM-V-demo-HarmonyOS/    HarmonyOS ArkTS + NDK sources
├── llama.cpp-omni/              git submodule (inference backend, shared by all 3 clients)
├── scripts/                     build / test helpers
└── README{,_zh}.md              per-platform setup walkthroughs (read these first)
```

On first entry into this repo, the agent should `Read README.md` (or `README_zh.md`). It
contains the complete "install Xcode / Android Studio / DevEco Studio → first-time build →
open & run" walkthrough for each platform.

---

## 2. The `llama.cpp-omni` submodule

- URL: `https://github.com/tc-mb/llama.cpp-omni.git`
- Branch: `master`
- `.gitmodules` is marked `shallow = true`

Recommended clone (one-shot, parent + submodule both shallow):

```bash
git clone --recurse-submodules --shallow-submodules \
    https://github.com/OpenBMB/MiniCPM-V-Apps.git
```

If the parent repo is already cloned, init the submodule separately:

```bash
git submodule update --init --recursive --depth 1 --single-branch
```

**Agent notes**

- ❌ Do **not** switch the submodule branch (it is pinned to `master`).
- ❌ Do **not** commit upstream-only changes (e.g. random edits to `convert_hf_to_gguf.py`)
  inside the submodule without explicit user confirmation.
- ✅ If you do edit the submodule on purpose: `git commit` + `git push` to
  `tc-mb/llama.cpp-omni:master` first, then in the parent repo `git add llama.cpp-omni && git
  commit` to bump the pointer.

---

## 3. What is *not* in the repo (and must stay out)

Already covered by `.gitignore`. Agents must double-check the staging list before each
`git commit` and `git restore --staged` anything that slipped in:

| Category | Examples |
|---|---|
| Prebuilt llama framework | `MiniCPM-V-demo/thirdparty/llama.xcframework/` (~189 MB), `…/llama.xcframework.bak/` |
| Xcode build / debug | `build/`, `DerivedData/`, `*.dSYM/`, `xcuserdata/`, `build_tmp/` |
| Android build / cache | `MiniCPM-V-demo-Android/{app/,}build/`, `.cxx/`, `.gradle/`, `app/src/main/jniLibs/`, `local.properties` |
| HarmonyOS build / deps | `MiniCPM-V-demo-HarmonyOS/{entry/,}build/`, `.hvigor/`, `oh_modules/` |
| IDE per-user state | `MiniCPM-V-demo-Android/.idea/`, any `*.iml`, `.swiftpm/` |
| AI agent local notes | `CLAUDE.md`, `.claude/`, `.cursor/` (this file `AGENTS.md` **is** tracked) |

The `llama.xcframework` was deliberately removed from git history (see commit `5e358ab`)
to keep the repo lean. It must be re-built locally on first checkout, see §4.1.

---

## 4. Building from source

The full per-platform walkthrough lives in `README.md` / `README_zh.md`. Agents touching
build configuration should re-read the relevant section first.

### 4.1 iOS

First-time build requires producing the `llama.xcframework` from the submodule. Use the
one-shot script:

```bash
./scripts/build_xcframework.sh
```

It defaults to `MINIMAL_MODE=ios` (real device + simulator slices only, ~2–3 min on a
modern M-series Mac). Override with `MINIMAL_MODE={ios-sim,ios-device,all}` for other
build scopes. See README §1.1.2 for full details.

Re-run the script whenever the submodule pointer is bumped, or whenever you edit any
source under `llama.cpp-omni/` that affects the framework binary.

### 4.2 Android

```bash
cd MiniCPM-V-demo-Android
./gradlew assembleDebug         # quick local test
./gradlew :app:assembleRelease  # release artefact (signed via keystore in ~/.gradle/gradle.properties)
```

JBR used for the release build: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

### 4.3 HarmonyOS

DevEco Studio handles native + ArkTS + resource + PackageHap in one click. There is no
command-line release script in this repo today.

---

## 5. Release artefact naming (strict)

Every release goes out under a fixed name so end users can tell APKs / IPAs apart at a
glance. **No three-segment versions, no commit hashes, no `-release` / `-signed`
suffixes in user-facing filenames.**

### iOS

- Fields: `MARKETING_VERSION` + `CURRENT_PROJECT_VERSION` in `MiniCPM-V-demo.xcodeproj/project.pbxproj`.
  Both fields share the same value and each appears twice (= 4 places to edit in sync).
- Naming: `<MAJOR.MINOR>`, e.g. `2.1`. Hotfixes bump MINOR, not a third segment.
- Distribution: users `Archive` themselves; agents do not produce IPAs.

### Android

- Fields: `versionCode` + `versionName` in `MiniCPM-V-demo-Android/app/build.gradle.kts`.
- `versionName` matches the iOS `MARKETING_VERSION` (`<MAJOR.MINOR>`).
- `versionCode` is **monotonically increasing**, +1 per release, never reused.
- **Release APK filename (hard rule)**:
  ```
  MiniCPM-V-demo-Android-v<versionName>.apk
  ```
  - ✅ `MiniCPM-V-demo-Android-v2.1.apk`
  - ❌ `MiniCPM-V-demo-1.2.3.apk` (missing platform, three-segment version)
  - ❌ `MiniCPM-V-demo-Android-release-<sha>.apk` (commit hash, internal-only)
- Post-build verification:
  - `aapt dump badging <apk> | grep ^package` — confirm `versionCode` / `versionName`
    match the `build.gradle.kts` you just edited.
  - `apksigner verify -v <apk>` — confirm v2 / v3 signature schemes are present.

### HarmonyOS

- Field: `app.version` in `MiniCPM-V-demo-HarmonyOS/build-profile.json5`.
- Naming: `MiniCPM-V-demo-HarmonyOS-v<MAJOR.MINOR>.hap`.

---

## 6. Cross-platform model consistency (iOS ↔ Android)

iOS and Android share the same model files (gguf + mmproj) over the same OBS / HF / MS
distribution. The two clients must agree on the MD5 of every artefact, otherwise side-by-side
testing breaks.

When rotating a model or re-converting the mmproj, **edit both sides in the same commit**:

- iOS: `MiniCPM-V-demo/Sources/Settings/ModelConst/MiniCPMModelConst.swift` (the `*_MD5`
  constants).
- Android: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ModelInfo.kt`
  (`ggufMd5` / `mmprojMd5`).

> **mmproj migration caveat**: this demo's branch of llama.cpp-omni expects
> `clip.projector_type=merger`, which differs from the upstream `minicpmv4_6` type
> currently exported by the standard conversion path. When swapping mmproj layouts,
> rename the file on disk (so legacy on-device copies are not blindly reused) and add
> the old filename to the client's "stale layout" purge list. MD5 fast-paths alone are
> not sufficient — some load paths check existence first and skip the MD5 check.

---

## 7. Git commit & PR conventions

### Commit message

- **Single-line subject**, no body. Fold scope + summary + affected platforms + version
  bumps into one sentence. If something genuinely needs prose, put it in the PR
  description, not the commit body.
- **No AI / tool attribution** — no `Co-authored-by: Cursor <…>`, no `Generated with
  Claude`, no `🤖 …` watermark, no `--trailer` variants. Same for code comments.
- **No emoji.**
- English subject with optional Chinese scope is fine:
  - ✅ `android/harmonyos: drop hardcoded English sys prompt`
  - ✅ `ios: bump app version 2.1 -> 2.2 (Metal-default + slimmer download)`
  - ❌ `feat: ...\n\n* bullet 1\n* bullet 2\nCo-authored-by: Cursor <…>`

### Force push

- `main` is **not** force-pushed routinely. The only legitimate force-push to `main` is
  history-rewriting cleanup (e.g. `git filter-repo`-driven removal of accidentally
  committed binaries), and only with explicit user authorization, using
  `git push --force-with-lease` (never `--force`).
- Feature branches may be force-pushed after user confirmation.

### PRs

- Target `OpenBMB/MiniCPM-V-Apps:main`.
- PR description should cover: motivation, files / platforms touched, whether the
  reviewer needs to re-run `./scripts/build_xcframework.sh` or re-pull a model, and
  any user-visible behaviour change.
- Reference open issues with `Fixes #N` so GitHub auto-links and auto-closes them.

---

## 8. Common pitfalls

| Symptom | Real cause / fix |
|---|---|
| Xcode: `There is no XCFramework found at '.../llama.xcframework'` | First-time build, you skipped README §1.1.2 — run `./scripts/build_xcframework.sh`. |
| Submodule clone pulls hundreds of MB | Used a plain `git submodule update --init` without `--depth 1 --single-branch`. Use the README §0 / §2 shallow command. |
| Xcode pauses inside `ggml_uncaught_exception` and looks like a ggml crash | Almost always an Objective-C `NSException` that escaped into C++ and was caught by the `std::terminate_handler` llama.cpp installs. Inspect the `__NSCFConstantString` in the exception's `name` / `reason` — the real culprit is usually UIKit (e.g. issue #14 was a `UIAlertController` rejected by the iOS 26 SDK assertion). |
| Android `INSTALL_FAILED_USER_RESTRICTED` | Xiaomi / HyperOS disables USB-side installation by default; enable both "USB debugging (security settings)" and "Install via USB" in Developer Options. |
| Android `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Previously installed APK was signed with a different key. `adb uninstall <pkg>` then reinstall. |
| Keyboard takes seconds to appear the first time on iOS | Cold-start of a 3rd-party keyboard extension. The home VC's `_warmUpKeyboardOnce()` already pre-fires 1.5 s after launch — this is expected behaviour, not a bug. |

---

## 9. Do-not list (recap)

- ❌ Commit any compiled artefact: `.xcframework/`, `build/`, `.cxx/`, `.idea/`,
  `oh_modules/`, `DerivedData/`, `*.dSYM/`, …
- ❌ Commit any file containing a local absolute path or credential: `local.properties`,
  `gradle.properties` with `signing.*`, keystore files, `*.p12`, `*.mobileprovision`.
- ❌ Commit message with AI / tool attribution trailers.
- ❌ Switch the `llama.cpp-omni` submodule branch.
- ❌ Decorate release artefact filenames with three-segment versions, commit hashes,
  `-release`, `-signed`, dates, etc.

---

## 10. Out of scope

For repository hygiene, the following are deliberately **not** documented here:

- GitHub PATs, API tokens, keystore passwords, OBS / HF / MS credentials.
- Internal maintainer SOPs and cross-repo workflows.
- Absolute paths / shell aliases on any specific developer's machine.

If an agent needs any of those (e.g. to comment on an issue as a maintainer, or to push
a release), it should ask the user rather than scanning the local filesystem for keys.
