# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**مُتابِعي** (Mutabii) — an existing Arabic web app wrapped as an Android APK. The web app is shipped **verbatim** as a single asset file; the Kotlin layer only adds what a WebView cannot do: alarms that fire while the app is closed, a Quran media player in the notification shade / lock screen / Android Auto, native file save & share, and true fullscreen.

All UI text, notification strings, and code comments are Arabic (RTL).

## Build & release

There is **no Gradle wrapper** (`gradlew`) and **no test/lint setup** — no unit tests, no instrumentation tests, no external dependencies (`app/build.gradle` deps block is intentionally empty; only framework APIs are used).

Build requires a system Gradle 8.7 + JDK 17 + Android SDK 34:

```bash
gradle assembleDebug --stacktrace --no-daemon
```

The real release loop is CI: any push to `main` runs [.github/workflows/build.yml](.github/workflows/build.yml), which builds `assembleDebug`, renames the APK to `Mutabii.apk`, and publishes a GitHub Release tagged `v1.0.<run_number>`. Users install from that Release.

Both `debug` and `release` build types are signed with the committed keystore `app/mutabii-stable.keystore` (credentials are in plain text in `app/build.gradle`). This is deliberate: a stable signature lets every new APK install over the previous one without uninstalling. **Do not change or regenerate the keystore or its passwords** — doing so breaks in-place updates for every existing user.

## Architecture

### The web/native boundary

`app/src/main/assets/www/index.html` is ~2.4 MB / 4.7k lines and holds the entire app UI and logic. Treat everything before line ~4651 as the untouched upstream web app.

The **only** app-specific part is the final `<script>` block (`index.html:4652-4840`), guarded by `if(!window.MTBNative) return;` so the same file still runs as a plain web page. Changes to native behavior almost always belong in that block plus the matching Kotlin, not in the body of the web app.

Two directions of traffic:

- **JS → Kotlin**: `AlarmBridge` is injected as `window.MTBNative` (`MainActivity.configure`). Methods: `syncTimes`, `mediaUpdate`, `mediaStop`, `openSettings`, `setFullscreen`, `shareText`, `saveText`, `ping`.
- **Kotlin → JS**: `WebHolder.eval` / `WebHolder.cmd` call globals the bridge block defines — `window.__mtbCmd(cmd,arg)`, `window.__mtbSync()`, `window.__mtbMediaSync()`, `window.__mtbSurahList()`.

Anything the WebView refuses to do is routed through the bridge: blob downloads (`saveText` → MediaStore Downloads), `navigator.share` (`shareText`), `requestFullscreen` (`setFullscreen` → `FLAG_FULLSCREEN` on the window), and file pickers (`WebChromeClient.onShowFileChooser` in `MainActivity` — without it JSON restore, CSV import, and audio-file selection all silently fail).

### Alarms — the web computes, the native layer schedules

The web app owns the prayer-time math (`pTimes()`). The bridge block precomputes 60 days of absolute epoch times and pushes them via `MTBNative.syncTimes` on load, on `visibilitychange`, and hourly. The web app's own scheduler is neutered (`window.alrTick = function(){}`) so only the native layer fires alarms.

Flow: `AlarmBridge.syncTimes` → `Store` (SharedPreferences `mtb_times`) → `AlarmScheduler.rescheduleAll`.

`AlarmScheduler` expands stored days plus `Prefs` settings into events (`adhan`, `pre`, `iqama`, `wake`) over a **4-day horizon, capped at 200 alarms**, cancels the previously registered request codes (tracked as a comma list in `mtb_sched`), and re-registers via `setAlarmClock`, falling back to `setExactAndAllowWhileIdle` when exact alarms aren't permitted. A daily `maint` alarm at 00:35 plus a reschedule at the end of every `AlarmReceiver.onReceive` keep the horizon topped up even if the app is never opened. `BootReceiver` re-runs it after boot and after app replacement.

**Alarm settings have exactly one source of truth: `Prefs`.** The web app also ships its own alarm screen (`alrModal`, driving `data.alerts`), but in the APK that data is inert — `alrTick` is stubbed out and nothing reads `data.alerts`. So the settings UI shown in the app (`alrScreen`) reads and writes `Prefs` directly through `AlarmBridge.getAlarms` / `setAlarm`, and every write reschedules immediately. `alrModal` is kept only as the plain-web fallback (`alrNative()` picks between them). Never wire a new alarm setting into `data.alerts` — it will silently do nothing on a phone.

Two behaviours protect the wake alarm from a half-asleep user, and both are deliberate: the wake notification carries **no stop action** (dismissing must go through `AlarmActivity`'s challenge), and `AlarmService` watches the alarm stream via a `ContentObserver` — a volume press stops the *adhan* (expected) but is re-asserted to max during *wake*. `AlarmActivity.onKeyDown` swallows the volume/headset keys for the same reason. If the wake alarm is ignored until `wakeAutoStop`, it re-fires up to `wakeRetries` times; retry and snooze intents carry `retry=true` so they don't refill the retry/snooze budgets that only a real dismissal (or the next day's alarm) resets.

`AlarmReceiver` dispatches to `AlarmService` (foreground service that owns the `MediaPlayer`, wake lock, forced stream volume, volume ramp, vibration, auto-stop) and, for `wake`, also raises a full-screen-intent notification launching `AlarmActivity` over the lock screen — dismissible only by the configured challenge (math / N taps / none, see `Prefs.K_WAKE_*`).

### Quran player — the WebView is the audio engine

`MediaService` plays nothing. It is a `MediaBrowserService` shell that mirrors state and proxies transport controls back into the page:

- JS reports playback state → `AlarmBridge.mediaUpdate` → `MediaState` (in-memory) → `MediaService.update` → notification + `MediaSession` metadata.
- Notification buttons, lock screen, Bluetooth/car keys, and Android Auto → `MediaSession.Callback` / service actions → `WebHolder.cmd(...)` → `window.__mtbCmd` → the page's `playSurah` / `pauseAudio` / `seekBy` / …

Because the page is the player, `WebHolder` holds the `WebView` on `applicationContext` and `MainActivity.onDestroy` **only destroys it when `WebHolder.audioActive` is false** — otherwise audio stops the moment the user leaves the app. `MainActivity` reuses that retained instance on relaunch instead of reloading `index.html`.

Android Auto browses `SurahNames.LIST` (114 entries) as media IDs `surah_<n>`; selecting one calls `__mtbCmd('playsurah', n)`.

Note in `MediaService.refresh`: after `startForegroundService`, `startForeground` **must** be called within ~5 s or the process is killed, including when playback is paused — hence the notify-then-`STOP_FOREGROUND_DETACH` sequence rather than skipping `startForeground`.

### Recorded adhkar voices

Users can record themselves (or anyone) reading each dhikr and have the app play those clips instead of device TTS. Audio blobs are far too big for `localStorage`, so they live in their own IndexedDB database `mtbVoiceDB` (store `clips`), keyed `<voiceId>|<cat>-<itemId>` (e.g. `v1a2b3|sabah-s1`). Only profile metadata (`data.voices`, `data.adhVoice`) goes in the normal save blob.

Playback reuses the existing car-mode engine: `attsStep` calls `sayItem`, which plays the recorded clip when one exists and otherwise falls back to `ttsSay` — the same fallback applies per-item, so a half-recorded profile still works. `ttsCancel` stops recorded audio too, so `ttsGen` remains the single cancellation authority for both paths.

Recording needs `RECORD_AUDIO`: `WebChromeClient.onPermissionRequest` alone is not enough, the OS permission must be granted first, so `AlarmBridge.ensureMic()` → `MainActivity.askMic()` requests it and `onRequestPermissionsResult` reports back via `window.__mtbMicResult`.

Clips are deliberately excluded from JSON backup (size); each profile exports separately as a base64 JSON via `vExport` / `importVoice`.

### Two listening tracks

Quran listening has two independent tracks and the distinction is load-bearing — mixing them was the source of a whole class of data-loss bugs.

`data.listenMode` is `'khatma'` or `'free'`. Enter khatma mode only through `startKhatma` (the ختمة card, or resuming something that was recorded as khatma); everything else — surah picker, saved positions, audio favourites, Android Auto surah selection — goes through `startFree`. `markListenProgress` returns immediately unless the mode is `khatma`, so free listening can never move, corrupt, or drift the khatma.

`data.samKhatma` carries `{pos, count, surah, time, reciter}`: `pos` is the last completed surah, `surah`/`time` the in-progress position inside the current one. Only khatma-mode playback writes `surah`/`time`, and `persistResume` exempts `samKhatma.surah` from the 20-entry eviction on `data.audioPos`. `data.audioResume` stores the `mode` that produced it so resuming returns to the right track.

A surah is credited at 95% (in the `timeupdate` handler) as well as on `ended`; `audioState._credited` keeps that idempotent. In khatma mode auto-advance follows `samKhatma.pos + 1`, never `currentSurah + 1`. `playSurah` calls `flushCurrent` first, which persists the outgoing surah's position and logs its session — without it, switching surahs discarded both.

### The player has two layouts

`playerCard` renders one of two arrangements off `ui.followTilawa`: the default card (ayah "artwork", seek bar, transport row, two secondary buttons, everything else inside a `<details class="plMore">`), or — when ayah-following is on — the mushaf layout, where the ayah list fills the card and a slim `.plDock` holds the controls. Both share the same `#tilawaBox`, which `paintTilawa` fills according to mode: a single ayah in the default layout, or a three-ayah strip (previous, current, next) in mushaf mode. Keep it to a strip — rendering the whole surah puts 286 nodes on screen, which is neither readable nor cheap to repaint on every `timeupdate`.

Every slider in the app uses the shared `.rng` class (`-webkit-appearance:none`) — without it the native control renders as a raw white bar in the WebView. The filled portion comes from a `--p` custom property (0–100) that must be set alongside `value`; changing one without the other desyncs the fill from the thumb. Two users today: the player seek bar (updated in the `timeupdate` handler) and the book reader's page slider (`.rng.rtl`, which reverses the gradient rather than the element, updated in the `input` listener so the fill tracks the finger during a drag). Text inputs all share `.inp`.

### State locations

| SharedPreferences file | Written by | Holds |
| --- | --- | --- |
| `mtb_alarm_prefs` | `Prefs` (source of truth for the native layer), `SettingsActivity` | all adhan/wake settings |
| `mtb_times` | `Store` | 60 days of prayer times + location name, from JS |
| `mtb_sched` | `AlarmScheduler` | request codes of currently registered alarms |
| `mtb_ui` | `MainActivity` | fullscreen flag, applied before first draw |

Web-app data (habits, backups) lives in the WebView's own DOM storage, separate from all of the above.

## Conventions

- The today screen's day arc (`dayThread` → `.arcCard`) is redrawn only by `render()`; its per-second motion (progress sweep, glowing now-marker, countdown) is done by `arcTick`, called from `updateClock`. Keep the two in sync — the geometry constants live in both.
- The web settings screen (`openSettings`) and the recording screen (`vScrHTML`) are full-page overlays: they emit a `.spg` root, which `mWrap()` detects to drop the modal's padding. Everything else keeps using the small centered `.mbox`.
- Native settings UI is built programmatically in `SettingsActivity` — there are no layout XML files; only drawables, colors, strings, and themes under `res/`.
- Kotlin code wraps nearly every Android call in `try { } catch (_: Exception) {}`. This is intentional for an app whose alarms must never crash the process on an unusual OEM; match it in new code on the alarm/media paths.
- Comments explaining *why* a non-obvious workaround exists (fullscreen flag choice, `startForeground` timing, file-chooser requirement) are load-bearing — keep them when editing those lines.
