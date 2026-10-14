# JMComicH

An offline-first Android client for JM comics: browse, download, read, and export
(zip / pdf). Everything runs on-device — no server required.

> The design originally covered two switchable modes (comics + Bilibili video).
> **The Bilibili mode has been removed entirely**; this project is comics-only now.
> The historical plan lives in `docs/android-app-plan.md` (kept as-is; its dual-mode
> sections no longer apply).

#### Software Architecture

A single `:app` Gradle module with a package layout split by logical module:

```
app/src/main/java/com/haruhi/jmcomic/
  JmComicApp.kt              process entry (logging, crash log, DI container)
  MainActivity.kt            the only Activity
  core/
    protocol/                JM protocol: signing, decryption, constants, URL rules
    codec/                   image decode/descramble, zip / pdf export
    network/                 OkHttp factory, error mapping, retry
    database/                Room entities / Dao / database
    download/                download engine, task repository, foreground service
    settings/                settings (global / comic scopes) + Keystore secret store
    storage/                 directory layout, file naming, export delivery
    di/                      hand-written DI container
  feature/
    api/                     JM API client and response models
    data/                    repositories, image store, download/export, reading position
    ui/                      Compose UI (library / recent / discover / favorites / tasks / reader)
  ui/theme/                  Compose theme
```

#### Installation

```powershell
# debug build (installs alongside the release build)
.\gradlew.bat :app:assembleDebug

# release build (needs keystore.properties + *.jks in the repo root, see docs/BUILD.md)
.\gradlew.bat :app:assembleRelease
```

Install with `adb install -r app\build\outputs\apk\debug\app-debug.apk`.

#### Usage

| Entry | Description |
|---|---|
| Library | Collected comics; filtering, batch actions, continue reading |
| Recent | Comics whose detail/reader page was opened |
| Discover | Online search (a purely numeric keyword also looks that album up directly) |
| Favorites | Favorite folder management (the default folder cannot be deleted or renamed) |
| Tasks | Download/export progress, pause/resume/cancel/retry |
| Reader | Vertical scroll / horizontal paging, pinch zoom, chapter switching, refill missing pages |

Exported zip / pdf files land in `Download/JMComicH/` (Android 10+, no storage permission needed).

#### Development

- Run before committing: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- Cross-cutting rules: `docs/CONVENTIONS.md`; change log: `docs/PROGRESS.md`;
  build / signing / on-device testing: `docs/BUILD.md`.

#### Contributing

1.  Fork this repository
2.  Create a `Feat_xxx` branch
3.  Commit your changes
4.  Create a Pull Request
