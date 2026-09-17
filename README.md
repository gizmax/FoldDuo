# Fold Duo

A launcher for the **Samsung Galaxy Z Fold 8** that brings the iPhone Duo experience to a book‑style foldable: one Home that folds, a frosted morph between the cover and the inner display, a Dynamic‑Island‑style pill around the camera, StandBy when the phone is stood up as a tent, and a Spotlight that lives on both panels.

No root, no Magisk, no hardware changes. It is a normal launcher app plus a small pose engine that reads the hinge, gravity and the magnetometer.

> Status: **experimental, built and tuned on one device** (SM‑F971B, Android 17 / One UI 9). Galaxy Z Fold 7 runs through a device profile that has only synthetic tests behind it. Expect rough edges.

## Download

Grab the latest APK from the [Releases](../../releases) page. The build is debug‑signed (there is no release keystore yet), so it installs over ADB or by sideloading, but it will not update over a Play Store install.

```
adb install -r -t FoldDuo-<version>.apk
```

Then set Fold Duo as the default Home, and in Android settings grant it **Notification access** (live activities, badges, media) and enable the **Fold Duo system shade** accessibility service (frost over third‑party apps during the fold, split‑view helpers). Android turns the accessibility service off on every sideload reinstall; `tools/install_fold.sh <adb-serial>` re‑enables it.

## What it does today

**The fold**
- Cover Home and inner Home are one workspace. Opening the phone frosts the cover, the inner lights up already frosted and clears with a perspective tilt driven by the real hinge angle; closing runs the same morph in reverse, starting from the first gyroscope sign of movement.
- Hinge angle is estimated continuously from the magnetometer, gated by the quantised system hinge sensor, with a paced fallback when the estimate is not confident. Everything degrades to a timed morph.
- The frost also covers third‑party apps during the fold (accessibility overlay), so the transition looks the same whatever is on screen.
- Optional experiment: ask the system for the OPENED device state early so the inner panel lights up from about 35° instead of 91°.

**Home**
- 4×7 grid on the cover, a spread of pages across both inner panes with pane identity (the right pane is the page you had on the cover).
- iOS‑style long‑press context menu, edit mode with page add/remove, drag and drop, folders as glass, app pairs that launch in split view.
- Liquid‑glass icons and tiles (AGSL shaders), colours pulled from the wallpaper, a seam palette, a wallpaper that bends as a sheet while the phone folds and breathes when a notification arrives.
- Today pane with notifications hub, predictions, suggestions, continuity chips, widgets in size classes, live icons.
- Reduce‑motion aware, 120 Hz aware, haptic language for every gesture.

**Dynamic Island around the camera**
- A pure black pill hugging the camera cutout that only appears while something is actually running: a call, navigation, a ride or delivery with ETA (Uber, Bolt, Rohlík, Wolt, Foodora, Mapy.cz, Google Maps), a timer, a workout, an in‑progress download, or music while it plays.
- Compact, two‑activity and expanded states with iPhone‑like morphs, per‑kind expanded cards (timer, call, ride with stage stepper and actions, music with artwork, progress and transport controls).
- Falls back to a rail‑mounted island if you prefer (setting).

**Spotlight**
- Pull down on Home to search apps, app pairs, contacts, settings shortcuts, launcher actions and the web.
- Two panes on the inner display: results and a live preview of the highlighted result. Swipe up to dismiss.

**StandBy**
- Stand the closed phone as a tent on a table and the cover becomes a night clock with two stacks (clock, calendar, photos, weather, world clock, alarm glow), a night mode that goes red and dims, and a frost morph in and out.
- A "Desk" mode on the inner display when the phone stands like a laptop.

**Everything else**
- Status card that slides out of the rail clock and battery, matte‑glass pills everywhere with one transparency setting.
- Settings pages for continuum, Today, colours and glass, motion and haptics, pages and modes, diagnostics, About.
- Diagnostics: main‑thread watchdog, recomposition counter, frame stats per swipe, morph episode logs.

## Privacy

Everything Fold Duo shows is read on the device: apps, notifications, media sessions, contacts, calendar, photos. There are no analytics, no accounts, no tracking.

Two Spotlight features reach the network, both behind the **Search online** setting you can turn off: place names are resolved through Android's own geocoder, and music results come from Apple's public iTunes Search API. Only the search term you typed is sent. Everything else, including the place gazetteer and the photo location index, is local.

Notification access and the accessibility service are used only for the features above and can be turned off in Android settings.

## Building

```
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:assembleDebug :app:testDebugUnitTest :pose:testDebugUnitTest :continuum:testDebugUnitTest
```

Modules: `app` (launcher), `pose` (pure Kotlin pose engine: hinge, gravity, magnetometer, classifier), `continuum` (AGSL shaders: fold morph, sheet bend, liquid glass, edge pulse).

## Credits and licences

Fold Duo is a fork of [Duo Launcher](https://github.com/jakesgoodapps/DuoLauncher) by Jake's Good Apps / Duo Launcher contributors (MIT). The fold effect (frost shader, tilt follower, panel detection) is ported from [duo‑open](https://github.com/marcoazeem/duo-open) by marcoazeem (MIT). Upstream licences and third‑party notices are in `docs/upstream/`.

Fold Duo itself is MIT licensed, © 2026 Tomáš Pflanzer ([@gizmax](https://github.com/gizmax)). Built with Claude Code.

Apple, iPhone and Dynamic Island are trademarks of Apple Inc. Samsung and Galaxy are trademarks of Samsung Electronics. This project is not affiliated with either.
