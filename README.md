# Zeekr Shortcut (Car Version)

An app for the ZEEKR 7X head unit that puts the car's own surround cameras to work: it records them as a
dash cam and shows them where they help while driving.

**[Download](../../releases/latest)** · [Features (wiki)](../../wiki) · [Discussions](../../discussions) ·
[中文说明](README.zh-CN.md) · [Credits](NOTICE.md)

> [!WARNING]
> Experimental, unofficial software. Not affiliated with, approved by, or endorsed by ZEEKR,
> and not certified for any vehicle safety function. It does **not** replace the factory dash cam,
> reversing camera, or blind-spot monitor. Do not operate it while driving.
> Full safety notice: [安全须知](README.zh-CN.md#安全须知).

---

## What it is

Through App Lab, a third-party app gets the 7X's four surround cameras as one pre-stitched stream, which on
its own previews as a distorted strip. Zeekr Shortcut splits it back into four views and builds on that:

- **Dash cam** — records the surround view, and the cabin cameras if you turn them on, to a USB drive.
- **Super mirror** — a floating electronic rear-view mirror showing any one camera.
- **Blind spot view** — the side camera on the center screen or the HUD while the turn signal is on.
- **Playback** — every camera together, in sync; send a photo or clip to your phone.

Every feature, setting and tip is described in the **[wiki](../../wiki)**.

---

## Download

Get the APK from **[Releases](../../releases/latest)**. Once installed, the app can check for newer
versions itself: Settings → Updates & about → Check for updates.

- You need to know how to sideload an app through App Lab. This page does not cover that.
- When the installer finishes, tap **Back** at the top left, not **Open**. Otherwise the head unit's
  installer gets stuck and later installs or updates fail.

Building from source: see the [wiki](../../wiki/Build-from-source).

Questions and ideas: [Discussions](../../discussions). Problems: [open an issue](../../issues) and attach
a report from Settings → General → Diagnostics.

---

## Recommended

**[Zeekr Companion](https://github.com/wysie/zeekr-shortcut-car)** is wysie's version of Zeekr Shortcut, optimised for the ZEEKR 009 head unit. Wysie has also shared a great deal of basic information about Zeekr cars, and it's a pleasure to explore what the Zeekr head unit can do together.

**[CAMCORDER](https://github.com/FerixzTanz/CAMCORDER)** is FerixzTanz's version of Zeekr Shortcut, with a series of features ahead of Zeekr Shortcut. This project's blind spot view started from FerixzTanz's code and on-car research, and showing it on the HUD comes from CAMCORDER: the approach, the HUD settings and his careful on-car calibration. Thanks to FerixzTanz for the support and contributions to this project.

**[CarPiano](https://github.com/jlunnn/CarPiano)** plays sound through the car's exterior speaker at any speed — no longer limited to Park like the factory feature. It's still early in development, but very promising.

Check your local rules before using the exterior speaker on public roads.

---

## Credits and license

**GPL-3.0**, inherited from [EVCam](https://github.com/suyunkai/EVCam) by suyunkai — the code base
this app is forked from. The first commit in this repository is EVCam's complete working tree, so
every change since then is visible as a diff.

Splitting App Lab's composite stream into a grid and rendering it is implemented here from scratch.

The simulated engine sound (its synthesis model, the voices and their tuning) is dts88's original work. CarPiano and
EngineV1 only supplied facts (see [NOTICE.md](NOTICE.md)).

### Contributors

- **[wysie](https://github.com/wysie)** — a great deal of design and code.
- **[FerixzTanz](https://github.com/FerixzTanz)** — the original code and on-car research for Blind spot view, and
  showing it on the HUD (from his CAMCORDER). Its default angles and window size, and the HUD picture defaults, are the
  ones he tuned on his own 7X. Many thanks for his careful parameter tuning.
- **[jlunnn](https://github.com/jlunnn)** — the Rear fog lamp flash action for smart buttons (from his 7X-U) and a
  fix that lets the unit tests pass on Windows. Also the author of CarPiano.
- **[calvinbui](https://github.com/calvinbui)** — on-car findings from his Australian 7X: climate, driving and safety
  settings.
- **[lelelolo](https://github.com/lelelolo)** — two Blind spot view improvements: no black window while the car is
  using the camera, and an edge in the mirror light's colour while that light is on. Many thanks.
- **SW** — active testing and feedback. Many thanks.

Origins, third-party licences and boundaries: [NOTICE.md](NOTICE.md).

### Using this project's code

Zeekr Shortcut is GPL-3.0, with two additional terms under GPL-3.0 section 7 ([NOTICE.md](NOTICE.md),
section 8). If your project uses its code, modified or not, you must:

1. **Credit it in your app's About or credits screen**, visible to users:
   `Based on Zeekr Shortcut by dts88 (https://github.com/dts88/zeekr-shortcut-car)`,
   or `Zeekr Shortcut © 2026 dts88` for an unmodified copy.
2. **Keep the notices**: the copyright headers in the source files and the GPL-3.0 licence. Release your
   source under GPL-3.0 when you distribute it.
3. **Mark it as modified**: a modified version must not be presented as the original or as coming from
   dts88. Give it its own name, or label it as modified.

Please also put the same credit near the top of your README, and name the version or commit you started
from. Code from EVCam or a contributor keeps its own credits too (see [NOTICE.md](NOTICE.md)).

Findings first published here, such as the [platform notes](docs/zeekr-platform-notes.md), can't be
licensed. Crediting them the same way is a request — the same courtesy this project gives the sources it
learned from.
