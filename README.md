# Zeekr Shortcut (Car Version)

A surround-view dash cam for the ZEEKR 7X head unit.

[中文说明](README.zh-CN.md) · [Changelog](CHANGELOG.md) · [Credits](NOTICE.md) · [Platform notes](docs/zeekr-platform-notes.md)

> [!IMPORTANT]
> **Known issue: App Lab lost after a restart.** Some users report that App Lab disappears after the head unit restarts and must be reinstalled. This is likely related to this app; the cause is under investigation. If this happens, or the app stutters or camera images are unavailable, **export a report from Settings → System → Diagnostics before restarting the head unit** and report it via a [GitHub issue](https://github.com/dts88/zeekr-shortcut-car/issues) or other channels. Apologies for the inconvenience.

> [!WARNING]
> Experimental, unofficial software. Not affiliated with, approved by, or endorsed by ZEEKR,
> and not certified for any vehicle safety function. It does **not** replace the factory dash cam,
> reversing camera, or blind-spot monitor. Do not operate it while driving.
> Full safety notice: [安全须知](README.zh-CN.md#安全须知).

---

## What it does

App Lab gives a third-party app the four surround cameras as **one pre-stitched stream**: four
square views packed into a single frame, which previews as a distorted vertical strip. This app
splits it back into a 2×2 grid and builds on that. Chinese, English and Malay interface.

### Dash cam

- Records the surround view as a 2×2 grid, plus the front and rear cabin cameras if you turn them on.
- *Record sound* (on by default): surround view recordings include the sound inside and outside the
  car, heard in playback.
- Three recording presets — save space (10 fps), balanced (20 fps), sharpest — each showing how
  many GB an hour it needs and how long your USB drive will last. Frame rate, bitrate, segment
  length and codec can also be tuned per camera.
- 1–10 minute segments. With a video storage cap set, the oldest unlocked clips are cleaned up when
  over the cap or low on space; with no cap, nothing is deleted and recording stops when space runs
  low.
- *Lock footage*: locked videos and photos are safe from loop recording. The floating button can lock
  the current recording, and recordings can also lock automatically when you flash your high beams or
  the hazard lights come on.
- Photos use each camera's largest size.
- Records to a **USB drive only**. Writing to the head unit's built-in flash sits behind developer
  options, because that storage cannot be replaced once worn out.
- *Driving info bar* (experimental, off by default): turn signals, steering, gear, pedals, speed,
  lamps and more, recorded in a strip under the video. You choose which items it shows.

On the main screen, tap any view to fill the preview with it and tap again to go back. The
**Straighten** button removes the fisheye bend, on the main screen and in playback. A floating
button shows whether recording is running and opens the app from anywhere.

### Super mirror

An electronic rear-view mirror: a floating, dockable window showing any one camera enlarged. Pinch
to zoom; swipe left or right to change camera; swipe up or down in the middle third to raise or
lower the framing. Or set *Switch views by* to *Tap* and tap the top, bottom, left or right of the
window to switch camera. *Views* can limit switching to front and rear, or to the rear view and the
rear cabin. Push it half off-screen to hide it at the edge, and tap to bring it back. The
rear camera is mirrored, like a real mirror. Fisheye correction with an adjustable field of view is
optional.

### Blind spot view

With the turn signal on, the side camera is shown on that side, on the center screen, the HUD or
both. Off by default. It is an aid only; please drive safely.

### Playback

Video playback shows every camera together, in sync; tap one to enlarge it, tap again to go back.
Photo playback uses the same layout.

### Send to your phone

While viewing a photo or a video segment, tap **Send to phone**: the app shows a QR code, your
phone's browser opens that file, and you save it. Nothing to install on the phone.

The file is served over your local network only while the dialog is open, from a random one-off
address, and only that one file. Both devices need to be on the same network — the easiest way is
to turn on the phone's hotspot and connect the car to it.

### Experimental

- *Smart buttons*: connect Bluetooth buttons named `ZEEKR_button`. Single and double press each run
  an action: anything the floating button can do, or switch the drive mode. Settings show each
  button's battery level.
- *Simulated drive sound*: an engine sound in Sport mode that follows throttle and speed, as an
  American V8 or a supersonic jet. It can also play in Standard mode. It is sometimes silent; so far
  it seems to play only while the radio or CarPlay is playing.

---

## Install

**Before you start:** you need to know how to sideload an app onto the head unit through App Lab.
This page does not cover that.

- **Recommended — the newest `-beta` on the [Releases](../../releases) page.** Once it is installed,
  the app can fetch later betas itself under Settings → Check for updates.
- The [latest stable release](../../releases/latest) is older and lacks some of the features on
  this page.

Download the `ZeekrShortcut-*.apk` asset from the release page and sideload it through App Lab.
When the installer finishes, tap **Back** at the top left, not **Open**. Otherwise the head unit's
installer gets stuck and later installs or updates fail.

Building it yourself needs JDK 17+ and the Android SDK (compileSdk 36):

```bash
git clone https://github.com/dts88/zeekr-shortcut-car.git
cd zeekr-shortcut-car && ./gradlew assembleRelease
```

The repo ships a public AOSP test signing key (password `android`). Override it with your own
through `ZEEKR_KEYSTORE`, `ZEEKR_KEYSTORE_PASSWORD`, `ZEEKR_KEY_ALIAS` and `ZEEKR_KEY_PASSWORD`.

---

## Recommended

**[Zeekr Companion](https://github.com/wysie/zeekr-shortcut-car)** is wysie's version of Zeekr Shortcut, optimised for the ZEEKR 009 head unit. Wysie has also shared a great deal of basic information about Zeekr cars, and it's a pleasure to explore what the Zeekr head unit can do together.

**[CAMCORDER](https://github.com/FerixzTanz/CAMCORDER)** is FerixzTanz's version of Zeekr Shortcut, with a series of features ahead of Zeekr Shortcut. This project's blind spot view started from FerixzTanz's code and on-car research, and showing it on the HUD comes from CAMCORDER: the approach, the HUD settings and his careful on-car calibration. Thanks to FerixzTanz for the support and contributions to this project.

**[CarPiano](https://github.com/jlunnn/CarPiano)** plays sound through the car's exterior speaker at any speed — no longer limited to Park like the factory feature. It's still early in development, but very promising.

Check your local rules before using the exterior speaker on public roads.

---

## Tips

### If the USB drive can't keep up

If recording stops working properly on the USB drive because the files have grown too large:

1. **Lower the frame rate to 10 fps first.** In **Settings → Recording → Edit stream profile**, tap
   each camera under *Cameras on* and set **Frame rate** to *10 fps (cap)*.
2. **Still not working? Then lower the bitrate** for those cameras.

Changes apply from the next recording.

A dash cam's main job is a clear picture. At 10 fps motion looks choppy, but each frame stays sharp.

For anything else, [open an issue](../../issues) and attach a report from **Settings → System →
Diagnostics**.

---

## Limitations

- Built for the ZEEKR 7X. Only the camera recognised as the composite stream is split; a head unit
  that does not provide one gets nothing split.
- Factory features come first: the built-in 360° view, reversing camera and parking cameras can
  reclaim a camera at any time.
- Recording on launch is off by default. With Sentry Mode on, the surround
  view keeps recording with the screen off and the rear cabin resumes when the screen turns on.
  Without Sentry Mode, recording pauses with the screen off and resumes when it turns on.
- For now, the front cabin camera is one-shot: it turns itself off when you leave the car.
- Cropping a surround view is switched off for now.
- On-vehicle validation is ongoing. Automated tests cover the pure logic only; test in a stationary
  vehicle first.

---

## Credits and license

**GPL-3.0**, inherited from [EVCam](https://github.com/suyunkai/EVCam) by suyunkai — the code base
this app is forked from. The first commit in this repository is EVCam's complete working tree, so
every change since then is visible as a diff.

Splitting App Lab's composite stream into a grid and rendering it is implemented here from scratch.

### Contributors

- **[wysie](https://github.com/wysie)** — a great deal of design and code.
- **[FerixzTanz](https://github.com/FerixzTanz)** — the original code and on-car research for Blind spot view, and
  showing it on the HUD (from his CAMCORDER). Its default angles and window size, and the HUD picture defaults, are the
  ones he tuned on his own 7X. Many thanks for his careful parameter tuning.

Origins, third-party licences and boundaries: [NOTICE.md](NOTICE.md).

### Capture session progress

Only results from the current capture session update its frame/progress and FPS
counters. Late results from a replaced session cannot postpone stall detection.
This does not change camera ownership, retry budgets or recording defaults.
